package com.betterself.growth.ai;

import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "qwen")
public class QwenHttpProvider implements QwenProvider {

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Duration streamTimeout;
    private final boolean jsonMode;

    public QwenHttpProvider(
        ObjectMapper objectMapper,
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout
    ) {
        this(objectMapper, baseUrl, apiKey, model, timeout, timeout, true);
    }

    @Autowired
    public QwenHttpProvider(
        ObjectMapper objectMapper,
        @Value("${app.ai.base-url}") String baseUrl,
        @Value("${app.ai.api-key}") String apiKey,
        @Value("${app.ai.model}") String model,
        @Value("${app.ai.timeout:PT120S}") Duration timeout,
        // Streaming replies can hold the connection open well past a single request:
        // the model keeps generating the option/action tail after the prose is done.
        @Value("${app.ai.stream-timeout:PT130S}") Duration streamTimeout,
        // Not every OpenAI-compatible gateway implements response_format; some hang on it.
        // Turning this off falls back to schema-in-the-prompt plus the repair pass below.
        @Value("${app.ai.json-mode:true}") boolean jsonMode
    ) {
        String normalizedApiKey = apiKey == null ? "" : apiKey.trim();
        if (normalizedApiKey.isBlank() || isPlaceholder(normalizedApiKey)) {
            throw new IllegalStateException("QWEN_API_KEY is required when app.ai.provider=qwen");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("QWEN_BASE_URL is required when app.ai.provider=qwen");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("QWEN_MODEL is required when app.ai.provider=qwen");
        }
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.objectMapper = objectMapper;
        try {
            this.endpoint = URI.create(baseUrl.trim().replaceAll("/$", "") + "/chat/completions");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("QWEN_BASE_URL is invalid", exception);
        }
        if (!"http".equalsIgnoreCase(this.endpoint.getScheme()) && !"https".equalsIgnoreCase(this.endpoint.getScheme())) {
            throw new IllegalStateException("QWEN_BASE_URL must use HTTP or HTTPS");
        }
        this.apiKey = normalizedApiKey;
        this.model = model.trim();
        this.timeout = timeout;
        this.streamTimeout = streamTimeout;
        this.jsonMode = jsonMode;
    }

    @Override
    public StructuredResult generateStructured(StructuredPrompt prompt) {
        long started = System.nanoTime();
        JsonNode root = call(List.of(
            Map.of("role", "system", "content", "Return only JSON matching this schema: " + prompt.schemaJson()),
            Map.of("role", "user", "content", prompt.instruction())
        ), true);
        String content = stripCodeFence(root.path("choices").path(0).path("message").path("content").asText());
        if (!validJson(content)) {
            root = call(List.of(
                Map.of("role", "system", "content", "Repair the following value into JSON only. Schema: " + prompt.schemaJson()),
                Map.of("role", "user", "content", content)
            ), true);
            content = stripCodeFence(root.path("choices").path(0).path("message").path("content").asText());
            if (!validJson(content)) {
                throw unavailable("AI_INVALID_JSON");
            }
        }
        return new StructuredResult(
            content,
            root.path("model").asText(model), root.path("id").asText(),
            root.path("usage").path("prompt_tokens").asInt(), root.path("usage").path("completion_tokens").asInt(),
            Duration.ofNanos(System.nanoTime() - started).toMillis()
        );
    }

    private String stripCodeFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private boolean validJson(String value) {
        try {
            return objectMapper.readTree(value) != null;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) {
        long started = System.nanoTime();
        AtomicBoolean receivedContent = new AtomicBoolean(false);
        AtomicReference<String> requestId = new AtomicReference<>("");
        AtomicReference<String> responseModel = new AtomicReference<>(model);
        AtomicReference<Integer> inputTokens = new AtomicReference<>(0);
        AtomicReference<Integer> outputTokens = new AtomicReference<>(0);
        HttpRequest request = request(
            List.of(
                Map.of("role", "system", "content", prompt.systemPrompt()),
                Map.of("role", "user", "content", prompt.userMessage())
            ),
            false,
            true
        );
        try {
            HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerFailure(response.statusCode());
            }
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).trim())
                    .filter(payload -> !payload.isEmpty() && !"[DONE]".equals(payload))
                    .forEach(payload -> consumeChunk(
                        payload, requestId, responseModel, inputTokens, outputTokens, receivedContent, deltaConsumer
                    ));
            }
            if (!receivedContent.get()) {
                throw unavailable("AI_EMPTY_RESPONSE");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("AI_PROVIDER_UNAVAILABLE");
        }
        return new StreamMetadata(
            responseModel.get(), requestId.get(), inputTokens.get(), outputTokens.get(),
            Duration.ofNanos(System.nanoTime() - started).toMillis()
        );
    }

    @Override
    public Classification classify(ClassificationPrompt prompt) {
        StructuredResult result = generateStructured(new StructuredPrompt(
            "SAFETY", "Classify the text into one label: " + prompt.labels() + ". Text: " + prompt.text(),
            "{\"type\":\"object\",\"required\":[\"label\"]}"
        ));
        try {
            String label = objectMapper.readTree(result.json()).path("label").asText("L0");
            return new Classification(label, Map.of(label, 1.0));
        } catch (Exception exception) {
            throw unavailable("AI_INVALID_JSON");
        }
    }

    private JsonNode call(List<Map<String, String>> messages, boolean jsonMode) {
        try {
            HttpRequest request = request(messages, jsonMode, false);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerFailure(response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("AI_PROVIDER_UNAVAILABLE");
        }
    }

    private ApiException unavailable(String code) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "AI service is temporarily unavailable");
    }

    private boolean isPlaceholder(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("replace-with-")
            || normalized.startsWith("your-")
            || normalized.equals("test-key")
            || normalized.equals("changeme")
            || normalized.equals("change-me");
    }

    private HttpRequest request(List<Map<String, String>> messages, boolean jsonMode, boolean stream) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.2);
            body.put("max_tokens", 2000);
            if (jsonMode && this.jsonMode) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            if (stream) {
                body.put("stream", true);
                body.put("stream_options", Map.of("include_usage", true));
            }
            return HttpRequest.newBuilder(endpoint)
                .timeout(stream ? streamTimeout : timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        } catch (Exception exception) {
            throw unavailable("AI_PROVIDER_UNAVAILABLE");
        }
    }

    private void consumeChunk(
        String payload,
        AtomicReference<String> requestId,
        AtomicReference<String> responseModel,
        AtomicReference<Integer> inputTokens,
        AtomicReference<Integer> outputTokens,
        AtomicBoolean receivedContent,
        Consumer<String> deltaConsumer
    ) {
        try {
            JsonNode chunk = objectMapper.readTree(payload);
            if (chunk.has("error")) {
                throw unavailable("AI_PROVIDER_ERROR");
            }
            if (chunk.hasNonNull("id")) requestId.set(chunk.path("id").asText());
            if (chunk.hasNonNull("model")) responseModel.set(chunk.path("model").asText(model));
            JsonNode usage = chunk.path("usage");
            if (usage.hasNonNull("prompt_tokens")) inputTokens.set(usage.path("prompt_tokens").asInt());
            if (usage.hasNonNull("completion_tokens")) outputTokens.set(usage.path("completion_tokens").asInt());
            for (JsonNode choice : chunk.path("choices")) {
                String text = choice.path("delta").path("content").asText("");
                if (!text.isEmpty()) {
                    receivedContent.set(true);
                    deltaConsumer.accept(text);
                }
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid AI stream payload", exception);
        }
    }

    private ApiException providerFailure(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return unavailable("AI_PROVIDER_AUTH_FAILED");
        }
        if (statusCode == 429) {
            return unavailable("AI_PROVIDER_RATE_LIMITED");
        }
        if (statusCode >= 400 && statusCode < 500) {
            return unavailable("AI_PROVIDER_REQUEST_REJECTED");
        }
        return unavailable("AI_PROVIDER_ERROR");
    }
}
