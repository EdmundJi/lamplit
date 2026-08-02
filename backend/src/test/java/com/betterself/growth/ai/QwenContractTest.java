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
            String content = calls.incrementAndGet() == 1 ? "not-json" : "{\"items\":[]}";
            json(exchange, """
                {"id":"provider-request","model":"qwen-contract","choices":[{"message":{"content":"%s"}}],"usage":{"prompt_tokens":12,"completion_tokens":7}}
                """.formatted(content.replace("\"", "\\\"")));
        });
        QwenHttpProvider provider = provider();

        QwenProvider.StructuredResult result = provider.generateStructured(
            new QwenProvider.StructuredPrompt("STUDY", "create tasks", "{\"type\":\"object\"}")
        );

        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(bodies).hasSize(2).allMatch(body -> body.contains("\"model\":\"qwen-contract\""));
        assertThat(result.json()).isEqualTo("{\"items\":[]}");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(7);
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

    private QwenHttpProvider provider() {
        return new QwenHttpProvider(
            new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "test-key", "qwen-contract", Duration.ofSeconds(2)
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
