package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateConfidantReplyGeneratorTest {

    private final TemplateConfidantReplyGenerator generator = new TemplateConfidantReplyGenerator();

    @Test
    void neverThrowsAndAlwaysReturnsNonBlankText() {
        List<TownConfidantReplyGenerator.Reply> result = generator.reply(List.of(
            new TownConfidantReplyGenerator.Request("1", ""),
            new TownConfidantReplyGenerator.Request("2", "今天有点累"),
            new TownConfidantReplyGenerator.Request("3", "为什么最近总是这样？"),
            new TownConfidantReplyGenerator.Request("4", "x".repeat(200))
        ));

        assertThat(result).hasSize(4);
        assertThat(result).allSatisfy(reply -> assertThat(reply.text()).isNotBlank());
    }

    @Test
    void sameMessageAlwaysProducesTheSameReply() {
        TownConfidantReplyGenerator.Request request = new TownConfidantReplyGenerator.Request("1", "今天很难过");
        String first = generator.reply(List.of(request)).get(0).text();
        String second = generator.reply(List.of(request)).get(0).text();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void aQuestionGetsAReflectiveReplyRatherThanAnAnswer() {
        String reply = generator.reply(List.of(
            new TownConfidantReplyGenerator.Request("1", "我是不是做错了？")
        )).get(0).text();

        // 只倾听、不给建议：模板池里没有一句是祈使句式的行动建议。
        assertThat(reply).doesNotContain("你应该").doesNotContain("建议你").doesNotContain("试试");
    }

    @Test
    void resultsNeverContainDigits() {
        for (TownConfidantReplyGenerator.Reply reply : generator.reply(List.of(
            new TownConfidantReplyGenerator.Request("1", "短"),
            new TownConfidantReplyGenerator.Request("2", "一段比较长的话".repeat(10)),
            new TownConfidantReplyGenerator.Request("3", "问一句？")
        ))) {
            assertThat(reply.text()).doesNotContainPattern("\\d");
        }
    }
}
