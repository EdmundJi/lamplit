package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-8 树洞回信生成器：必须有模板兜底，且 prompt 里除了来信正文之外不能塞进任何别的东西——
 * {@link TownConfidantReplyGenerator.Request} 本身就只有 {@code key}/{@code message} 两个字段，
 * 这里再从"生成结果"这一侧钉一遍行为契约（校验失败/异常/畸形 JSON 都要有兜底，且不抛异常）。
 */
class QwenConfidantReplyGeneratorTest {

    private final TemplateConfidantReplyGenerator fallback = new TemplateConfidantReplyGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void happyPathReturnsTheModelsText() {
        QwenProvider provider = respondingWith("""
            {"items":[{"key":"1","text":"这句话我听到了，谢谢你愿意讲给我听。"}]}
            """);
        QwenConfidantReplyGenerator generator = new QwenConfidantReplyGenerator(provider, fallback, objectMapper);

        List<TownConfidantReplyGenerator.Reply> result = generator.reply(List.of(
            new TownConfidantReplyGenerator.Request("1", "今天有点累")
        ));

        assertThat(result).containsExactly(
            new TownConfidantReplyGenerator.Reply("1", "这句话我听到了，谢谢你愿意讲给我听。"));
    }

    @Test
    void aReplyContainingADigitFallsBackToTemplate() {
        QwenProvider provider = respondingWith("""
            {"items":[{"key":"1","text":"你已经连续3天没提到这个了"}]}
            """);
        QwenConfidantReplyGenerator generator = new QwenConfidantReplyGenerator(provider, fallback, objectMapper);
        TownConfidantReplyGenerator.Request request = new TownConfidantReplyGenerator.Request("1", "随便写点什么");

        List<TownConfidantReplyGenerator.Reply> result = generator.reply(List.of(request));

        assertThat(result.get(0).text()).doesNotContain("3");
        assertThat(result.get(0).text()).isEqualTo(fallback.reply(List.of(request)).get(0).text());
    }

    @Test
    void malformedJsonFallsBackToTemplateForTheWholeBatch() {
        QwenProvider provider = respondingWith("not json at all");
        QwenConfidantReplyGenerator generator = new QwenConfidantReplyGenerator(provider, fallback, objectMapper);
        TownConfidantReplyGenerator.Request request = new TownConfidantReplyGenerator.Request("1", "今天心情不太好");

        List<TownConfidantReplyGenerator.Reply> result = generator.reply(List.of(request));

        assertThat(result).containsExactly(fallback.reply(List.of(request)).get(0));
    }

    @Test
    void aThrownProviderExceptionNeverPropagates() {
        QwenProvider throwingProvider = new QwenProvider() {
            @Override
            public StructuredResult generateStructured(StructuredPrompt prompt) {
                throw new RuntimeException("gateway is down");
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
        QwenConfidantReplyGenerator generator = new QwenConfidantReplyGenerator(throwingProvider, fallback, objectMapper);

        List<TownConfidantReplyGenerator.Reply> result = generator.reply(
            List.of(new TownConfidantReplyGenerator.Request("1", "写点什么试试")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isNotBlank();
    }

    @Test
    void resultSizeAlwaysEqualsRequestSizeEvenWhenTheModelReturnsFewer() {
        QwenProvider provider = respondingWith("""
            {"items":[{"key":"1","text":"我听到了"}]}
            """);
        QwenConfidantReplyGenerator generator = new QwenConfidantReplyGenerator(provider, fallback, objectMapper);

        List<TownConfidantReplyGenerator.Reply> result = generator.reply(List.of(
            new TownConfidantReplyGenerator.Request("1", "第一条"),
            new TownConfidantReplyGenerator.Request("2", "第二条")
        ));

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(TownConfidantReplyGenerator.Reply::key)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void promptNeverCarriesAnythingBeyondTheMessageItself() {
        // Request 本身只有两个字段（key、message），所以 instruction 里能出现的玩家相关内容
        // 上限就是这一句信——这条测试把"结构上不可能夹带别的数据"钉成一个可执行的事实。
        var components = TownConfidantReplyGenerator.Request.class.getRecordComponents();
        assertThat(components).hasSize(2);
        assertThat(components[0].getName()).isEqualTo("key");
        assertThat(components[1].getName()).isEqualTo("message");
    }

    private QwenProvider respondingWith(String json) {
        return new QwenProvider() {
            @Override
            public StructuredResult generateStructured(StructuredPrompt prompt) {
                assertThat(prompt.scene()).isEqualTo("TOWN_CONFIDANT");
                return new StructuredResult(json, "qwen-stub", "stub-request", 10, 10, 1);
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
    }
}
