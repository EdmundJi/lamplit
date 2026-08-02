package com.betterself.growth.ai;

import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "qwen")
public class QwenHttpProvider implements QwenProvider {

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public QwenHttpProvider(
        ObjectMapper objectMapper,
        @Value("${app.ai.base-url}") String baseUrl,
        @Value("${app.ai.api-key}") String apiKey,
        @Value("${app.ai.model}") String model,
        @Value("${app.ai.timeout:PT30S}") Duration timeout
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("QWEN_API_KEY is required when app.ai.provider=qwen");
        }
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/$", "") + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public StructuredResult generateStructured(StructuredPrompt prompt) {
        long started = System.nanoTime();
        JsonNode root = call(List.of(
            Map.of("role", "system", "content", "Return only JSON matching this schema: " + prompt.schemaJson()),
            Map.of("role", "user", "content", prompt.instruction())
        ), true);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (!validJson(content)) {
            root = call(List.of(
                Map.of("role", "system", "content", "Repair the following value into JSON only. Schema: " + prompt.schemaJson()),
                Map.of("role", "user", "content", content)
            ), true);
            content = root.path("choices").path(0).path("message").path("content").asText();
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
        JsonNode root = call(List.of(
            Map.of("role", "system", "content", prompt.systemPrompt()),
            Map.of("role", "user", "content", prompt.userMessage())
        ), false);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        for (int offset = 0; offset < content.length(); offset += 32) {
            deltaConsumer.accept(content.substring(offset, Math.min(content.length(), offset + 32)));
        }
        return new StreamMetadata(
            root.path("model").asText(model), root.path("id").asText(),
            root.path("usage").path("prompt_tokens").asInt(), root.path("usage").path("completion_tokens").asInt(),
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
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.2);
            if (jsonMode) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("AI_PROVIDER_ERROR");
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
}
