package com.betterself.growth.ai;

import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenContractTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsCompatibleAuthAndModelAndRepairsJsonOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = calls.incrementAndGet();
            String content = call == 1 ? "not-json" : "{\"items\":[]}";
            json(exchange, """
                {"id":"provider-request","model":"qwen-contract","choices":[{"message":{"content":"%s"}}],"usage":{"prompt_tokens":%d,"completion_tokens":%d}}
                """.formatted(content.replace("\"", "\\\""), call == 1 ? 12 : 5, call == 1 ? 7 : 3));
        });
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        var budget = new QwenHttpProvider.WireRequestBudget(2);
        QwenHttpProvider provider = new QwenHttpProvider(new ObjectMapper(),url,"unit-test-provider-key","qwen-contract",
            Duration.ofSeconds(2),Duration.ofSeconds(2),true,"qwen",false,budget);

        QwenProvider.StructuredResult result = provider.generateStructured(
            new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{\"type\":\"object\"}")
        );

        assertThat(authorization.get()).isEqualTo("Bearer unit-test-provider-key");
        assertThat(bodies).hasSize(2).allMatch(body -> body.contains("\"model\":\"qwen-contract\""));
        assertThat(result.json()).isEqualTo("{\"items\":[]}");
        assertThat(result.inputTokens()).isEqualTo(17);
        assertThat(result.outputTokens()).isEqualTo(10);
        assertThat(budget.requests()).extracting(QwenHttpProvider.WireRequest::kind)
            .containsExactly("structured", "structured-json-repair");
    }

    @Test
    void invalidJsonRepairCannotDispatchPastTheWireBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            json(exchange, "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}");
        });
        var budget = new QwenHttpProvider.WireRequestBudget(1);
        var provider = budgetedProvider(budget, true);

        assertThatThrownBy(() -> provider.generateStructured(
            new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{}")
        )).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("AI_WIRE_REQUEST_BUDGET_REACHED");

        assertThat(calls).hasValue(1);
        assertThat(budget.started()).isEqualTo(1);
        assertThat(budget.requests()).extracting(QwenHttpProvider.WireRequest::kind)
            .containsExactly("structured");
    }

    @Test
    void aResponseFormatFallbackClientCannotBypassTheSharedWireBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            calls.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        var budget = new QwenHttpProvider.WireRequestBudget(1);
        var withResponseFormat = budgetedProvider(budget, true);
        var withoutResponseFormat = budgetedProvider(budget, false);
        var prompt = new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{}");

        assertThatThrownBy(() -> {
            try {
                withResponseFormat.generateStructured(prompt);
            } catch (ApiException rejected) {
                assertThat(rejected.code()).isEqualTo("AI_PROVIDER_REQUEST_REJECTED");
                withoutResponseFormat.generateStructured(prompt);
            }
        }).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("AI_WIRE_REQUEST_BUDGET_REACHED");

        assertThat(calls).hasValue(1);
        assertThat(bodies).singleElement().asString().contains("response_format");
        assertThat(budget.started()).isEqualTo(1);
    }

    @Test
    void concurrentCallsCannotReserveMoreWireRequestsThanTheBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            json(exchange, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");
        });
        int maximum = 4;
        int contenders = 16;
        var budget = new QwenHttpProvider.WireRequestBudget(maximum);
        var provider = budgetedProvider(budget, true);
        var gate = new CountDownLatch(1);
        var successful = new AtomicInteger();
        var budgetRejected = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(contenders);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        gate.await();
                        provider.generateStructured(new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{}"));
                        successful.incrementAndGet();
                    } catch (ApiException error) {
                        if ("AI_WIRE_REQUEST_BUDGET_REACHED".equals(error.code())) budgetRejected.incrementAndGet();
                        else throw error;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }));
            }
            gate.countDown();
            for (Future<?> future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successful).hasValue(maximum);
        assertThat(budgetRejected).hasValue(contenders - maximum);
        assertThat(calls).hasValue(maximum);
        assertThat(budget.started()).isEqualTo(maximum);
        assertThat(budget.requests()).hasSize(maximum);
    }

    @Test
    void rejectsAfterSecondInvalidJson() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            json(exchange, "{\"id\":\"bad\",\"choices\":[{\"message\":{\"content\":\"still invalid\"}}]}");
        });

        assertThatThrownBy(() -> provider().generateStructured(
            new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{\"type\":\"object\"}")
        )).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("AI_INVALID_JSON");
        assertThat(calls).hasValue(2);
    }

    @Test
    void parsesRealProviderServerSentEventsAndUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        start(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"id\":\"stream-request\",\"model\":\"qwen-stream\",\"choices\":[{\"delta\":{\"content\":\"你好，\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{\"content\":\"今天先做一步。\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write(("data: {\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":8},\"choices\":[]}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        QwenHttpProvider provider = new QwenHttpProvider(new ObjectMapper(),url,"unit-test-provider-key","qwen-contract",
            Duration.ofSeconds(2),Duration.ofSeconds(2),true,"qwen",false);
        List<String> deltas = new ArrayList<>();

        QwenProvider.StreamMetadata result = provider.stream(
            new QwenProvider.ChatPrompt("STUDY", "system", "user"), deltas::add
        );

        assertThat(requestBody.get()).contains("\"stream\":true");
        assertThat(requestBody.get()).contains("\"include_usage\":true");
        assertThat(requestBody.get()).contains("\"enable_thinking\":false");
        assertThat(deltas).containsExactly("你好，", "今天先做一步。");
        assertThat(result.model()).isEqualTo("qwen-stream");
        assertThat(result.requestId()).isEqualTo("stream-request");
        assertThat(result.inputTokens()).isEqualTo(11);
        assertThat(result.outputTokens()).isEqualTo(8);
    }

    @Test
    void dropsResponseFormatWhenTheGatewayCannotHandleJsonMode() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, """
                {"id":"provider-request","model":"qwen-contract","choices":[{"message":{"content":"{\\"items\\":[]}"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}
                """);
        });

        QwenHttpProvider provider = new QwenHttpProvider(
            new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "unit-test-provider-key", "qwen-contract", Duration.ofSeconds(2), Duration.ofSeconds(2), false, null
        );
        provider.generateStructured(new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{\"type\":\"object\"}"));

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).doesNotContain("response_format");
        assertThat(bodies.get(0)).contains("\"model\":\"qwen-contract\"");
    }

    @Test
    void thinkingSwitchIsVendorAgnosticAtTheCallSiteButVendorSpecificOnTheWire() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        // A caller with no opinion on thinking (the 3-arg StructuredPrompt) must behave exactly as
        // before this switch existed: nothing sent, matching every gateway that predates it.
        var qwenProvider = new QwenHttpProvider(new ObjectMapper(), url, "unit-test-provider-key", "qwen3-contract", Duration.ofSeconds(2), Duration.ofSeconds(2), true, "qwen");
        qwenProvider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT", "hello", "{}"));
        assertThat(bodies.get(0)).doesNotContain("enable_thinking").doesNotContain("\"thinking\"");

        // Same vendor-agnostic Boolean, translated per vendor: qwen gets enable_thinking, deepseek gets
        // {"thinking":{"type":...}} - the call site never names either field itself (see QwenProvider).
        qwenProvider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT", "hello", "{}", false));
        assertThat(bodies.get(1)).contains("\"enable_thinking\":false");

        var deepseekProvider = new QwenHttpProvider(new ObjectMapper(), url, "unit-test-provider-key", "deepseek-contract", Duration.ofSeconds(2), Duration.ofSeconds(2), true, "deepseek");
        deepseekProvider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT", "hello", "{}", false));
        assertThat(bodies.get(2)).contains("\"thinking\":{\"type\":\"disabled\"}").doesNotContain("enable_thinking");

        deepseekProvider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT", "hello", "{}", true));
        assertThat(bodies.get(3)).contains("\"thinking\":{\"type\":\"enabled\"}");

        // The app-wide Qwen primary defaults to non-thinking even for older callers using the
        // three-argument prompt. An explicit per-call value still takes precedence above.
        var defaultOffQwen = new QwenHttpProvider(new ObjectMapper(),url,"unit-test-provider-key","qwen3.8-flash",
            Duration.ofSeconds(2),Duration.ofSeconds(2),true,"qwen",false);
        defaultOffQwen.generateStructured(new QwenProvider.StructuredPrompt("GOAL_TEMPLATE","hello","{}"));
        assertThat(bodies.get(4)).contains("\"enable_thinking\":false").doesNotContain("\"thinking\"");
    }

    @Test
    void structuredResultReportsReasoningFieldsFromTheActualProviderResponse() throws Exception {
        start(exchange -> json(exchange,"""
            {"model":"qwen3.8-flash","choices":[{"message":{"content":"{}","reasoning_content":"internal"}}],
             "usage":{"prompt_tokens":9,"completion_tokens":6,"completion_tokens_details":{"reasoning_tokens":4}}}
            """));
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        var provider=new QwenHttpProvider(new ObjectMapper(),url,"unit-test-provider-key","qwen3.8-flash",Duration.ofSeconds(2),Duration.ofSeconds(2),true,"qwen");

        var result=provider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_RESIDENT","hello","{}",false));

        assertThat(result.model()).isEqualTo("qwen3.8-flash");
        assertThat(result.reasoningContentPresent()).isTrue();
        assertThat(result.reasoningTokens()).isEqualTo(4);
    }

    @Test
    void rejectsPlaceholderApiKeyBeforeStartingRealProvider() {
        assertThatThrownBy(() -> new QwenHttpProvider(
            new ObjectMapper(), "http://127.0.0.1/v1", "replace-with-a-real-key", "qwen-plus", Duration.ofSeconds(2)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("QWEN_API_KEY");
    }

    @Test
    void mapsProviderAuthenticationFailureWithoutExposingProviderBody() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().write("provider secret details".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        assertThatThrownBy(() -> provider().stream(
            new QwenProvider.ChatPrompt("STUDY", "system", "user"), ignored -> { }
        )).isInstanceOf(ApiException.class)
            .satisfies(error -> {
                ApiException apiError = (ApiException) error;
                assertThat(apiError.code()).isEqualTo("AI_PROVIDER_AUTH_FAILED");
                assertThat(apiError.getMessage()).doesNotContain("provider secret details");
            });
    }

    @Test
    void rejectsSuccessfulEmptyStream() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        assertThatThrownBy(() -> provider().stream(
            new QwenProvider.ChatPrompt("STUDY", "system", "user"), ignored -> { }
        )).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("AI_EMPTY_RESPONSE");
    }

    @Test
    void deepSeekV4UsesNonThinkingAndIgnoresReasoningContent() throws Exception {
        AtomicReference<String> requestBody=new AtomicReference<>();
        start(exchange->{requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));json(exchange,"{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\",\"reasoning_content\":\"not part of the answer\"}}]}");});
        var provider=new QwenHttpProvider(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","unit-test-provider-key","deepseek-v4-flash",Duration.ofSeconds(2));
        var result=provider.generateStructured(new QwenProvider.StructuredPrompt("COMPANION_DIALOGUE","hello","{}"));
        assertThat(new ObjectMapper().readTree(requestBody.get()).path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(result.json()).isEqualTo("{\"ok\":true}");
        assertThat(result.json()).doesNotContain("not part of the answer");
    }

    private QwenHttpProvider provider() {
        return new QwenHttpProvider(
            new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "unit-test-provider-key", "qwen-contract", Duration.ofSeconds(2)
        );
    }

    private QwenHttpProvider budgetedProvider(QwenHttpProvider.WireRequestBudget budget, boolean jsonMode) {
        return new QwenHttpProvider(
            new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "unit-test-provider-key", "qwen-contract", Duration.ofSeconds(2), Duration.ofSeconds(2),
            jsonMode, null, null, budget
        );
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange));
        server.start();
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
