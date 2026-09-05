package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class QwenRetellGeneratorTest {

    private final TemplateRetellGenerator fallback = new TemplateRetellGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void happyPathReturnsTheModelsText() {
        StubQwenProvider provider = StubQwenProvider.respondingWithItemsJson(requests -> """
            {"items":[{"key":"k1","text":"好像有人说小吉最近老往健身房跑"}]}
            """);
        QwenRetellGenerator generator = new QwenRetellGenerator(provider, fallback, objectMapper);

        List<TownRetellGenerator.Retold> result = generator.retell(List.of(
            new TownRetellGenerator.Request("k1", "柯云", "高好奇心", List.of(), 2, "小吉最近挺常往健身房跑")
        ));

        assertThat(result).containsExactly(new TownRetellGenerator.Retold("k1", "好像有人说小吉最近老往健身房跑"));
        assertThat(provider.callCount()).isEqualTo(1);
    }

    @Test
    void aResponseContainingADigitFallsBackToTemplateForThatItemOnly() {
        StubQwenProvider provider = StubQwenProvider.respondingWithItemsJson(requests -> """
            {"items":[
              {"key":"k1","text":"好像听说他连续打卡3天了"},
              {"key":"k2","text":"好像有人说温晴最近挺开心的"}
            ]}
            """);
        QwenRetellGenerator generator = new QwenRetellGenerator(provider, fallback, objectMapper);

        TownRetellGenerator.Request badRequest =
            new TownRetellGenerator.Request("k1", "柯云", "p", List.of(), 2, "小吉最近挺常往健身房跑");
        TownRetellGenerator.Request goodRequest =
            new TownRetellGenerator.Request("k2", "陆夏", "p", List.of(), 1, "温晴最近挺开心的");

        List<TownRetellGenerator.Retold> result = generator.retell(List.of(badRequest, goodRequest));
        Map<String, String> byKey = toMap(result);

        // The digit-bearing item must fall back to whatever the template alone would produce.
        assertThat(byKey.get("k1")).isEqualTo(fallback.generate(badRequest));
        assertThat(byKey.get("k1")).doesNotContain("3");
        // The clean item keeps the model's own text untouched.
        assertThat(byKey.get("k2")).isEqualTo("好像有人说温晴最近挺开心的");
    }

    @Test
    void malformedJsonFallsBackToTemplateForTheWholeBatch() {
        StubQwenProvider provider = StubQwenProvider.respondingWithItemsJson(requests -> "not even json");
        QwenRetellGenerator generator = new QwenRetellGenerator(provider, fallback, objectMapper);

        TownRetellGenerator.Request r1 = new TownRetellGenerator.Request("k1", "柯云", "p", List.of(), 1, "小吉最近挺常往健身房跑");
        TownRetellGenerator.Request r2 = new TownRetellGenerator.Request("k2", "陆夏", "p", List.of(), 2, "温晴最近挺开心的");

        List<TownRetellGenerator.Retold> result = generator.retell(List.of(r1, r2));
        Map<String, String> byKey = toMap(result);

        assertThat(byKey.get("k1")).isEqualTo(fallback.generate(r1));
        assertThat(byKey.get("k2")).isEqualTo(fallback.generate(r2));
    }

    @Test
    void aThrownProviderExceptionFallsBackToTemplateAndDoesNotPropagate() {
        QwenProvider throwingProvider = new QwenProvider() {
            @Override
            public StructuredResult generateStructured(StructuredPrompt prompt) {
                throw new RuntimeException("provider is down");
            }

            @Override
            public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Classification classify(ClassificationPrompt prompt) {
                throw new UnsupportedOperationException();
            }
        };
        QwenRetellGenerator generator = new QwenRetellGenerator(throwingProvider, fallback, objectMapper);
        TownRetellGenerator.Request request =
            new TownRetellGenerator.Request("k1", "柯云", "p", List.of(), 1, "小吉最近挺常往健身房跑");

        List<TownRetellGenerator.Retold> result = generator.retell(List.of(request));

        assertThat(result).containsExactly(new TownRetellGenerator.Retold("k1", fallback.generate(request)));
    }

    @Test
    void resultSizeAlwaysEqualsRequestSize() {
        StubQwenProvider provider = StubQwenProvider.respondingWithItemsJson(requests -> """
            {"items":[{"key":"k1","text":"好像有人说小吉最近老往健身房跑"}]}
            """); // deliberately returns fewer items than requested
        QwenRetellGenerator generator = new QwenRetellGenerator(provider, fallback, objectMapper);

        List<TownRetellGenerator.Request> requests = List.of(
            new TownRetellGenerator.Request("k1", "柯云", "p", List.of(), 1, "小吉最近挺常往健身房跑"),
            new TownRetellGenerator.Request("k2", "陆夏", "p", List.of(), 2, "温晴最近挺开心的"),
            new TownRetellGenerator.Request("k3", "沈牧", "p", List.of(), 3, "安禾今天在公园坐了一下午")
        );

        List<TownRetellGenerator.Retold> result = generator.retell(requests);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(TownRetellGenerator.Retold::key)).containsExactlyInAnyOrder("k1", "k2", "k3");
    }

    @Test
    void batchingSplitsMoreThanTwentyRequestsIntoMultipleProviderCalls() {
        StubQwenProvider provider = StubQwenProvider.respondingWithItemsJson(requests -> {
            StringBuilder json = new StringBuilder("{\"items\":[");
            for (int i = 0; i < requests.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"key\":\"").append(requests.get(i).key()).append("\",\"text\":\"好像有人说了点事\"}");
            }
            json.append("]}");
            return json.toString();
        });
        QwenRetellGenerator generator = new QwenRetellGenerator(provider, fallback, objectMapper);

        List<TownRetellGenerator.Request> requests = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            requests.add(new TownRetellGenerator.Request("k" + i, "柯云", "p", List.of(), 1, "小吉最近挺常往健身房跑"));
        }

        List<TownRetellGenerator.Retold> result = generator.retell(requests);

        assertThat(result).hasSize(45);
        // 45 requests at a batch size of 20 must take three provider calls (20 + 20 + 5).
        assertThat(provider.callCount()).isEqualTo(3);
        assertThat(provider.batchSizes()).containsExactly(20, 20, 5);
    }

    private Map<String, String> toMap(List<TownRetellGenerator.Retold> retold) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (TownRetellGenerator.Retold item : retold) {
            map.put(item.key(), item.text());
        }
        return map;
    }

    /** Hand-written stub — no Mockito. Records every batch it was asked to answer. */
    private static final class StubQwenProvider implements QwenProvider {

        private final Function<List<TownRetellGenerator.Request>, String> responder;
        private final List<Integer> batchSizes = new ArrayList<>();

        // The instruction text embeds "key=<k>" once per request, in order, which is enough to
        // recover which requests were in this call without needing the real prompt-building code.
        private StubQwenProvider(Function<List<TownRetellGenerator.Request>, String> responder) {
            this.responder = responder;
        }

        static StubQwenProvider respondingWithItemsJson(Function<List<TownRetellGenerator.Request>, String> responder) {
            return new StubQwenProvider(responder);
        }

        int callCount() {
            return batchSizes.size();
        }

        List<Integer> batchSizes() {
            return batchSizes;
        }

        @Override
        public StructuredResult generateStructured(StructuredPrompt prompt) {
            assertThat(prompt.scene()).isEqualTo("TOWN_RETELL");
            List<String> keysInOrder = new ArrayList<>();
            for (String line : prompt.instruction().split("\n")) {
                int at = line.indexOf("key=");
                if (at >= 0) {
                    String rest = line.substring(at + 4);
                    int end = rest.indexOf('；');
                    keysInOrder.add(end >= 0 ? rest.substring(0, end) : rest);
                }
            }
            batchSizes.add(keysInOrder.size());
            List<TownRetellGenerator.Request> requests = keysInOrder.stream()
                .map(key -> new TownRetellGenerator.Request(key, "", "", List.of(), 1, ""))
                .toList();
            return new StructuredResult(responder.apply(requests), "qwen-stub", "stub-request", 10, 10, 1);
        }

        @Override
        public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Classification classify(ClassificationPrompt prompt) {
            throw new UnsupportedOperationException();
        }
    }
}
