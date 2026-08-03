package com.betterself.growth.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoalTemplateNormalizeTest {

    @Test
    void acceptsExactCodes() {
        assertThat(GoalTemplateService.normalizeDimension("KNOWLEDGE")).isEqualTo("KNOWLEDGE");
        assertThat(GoalTemplateService.normalizeDimension("WELLBEING")).isEqualTo("WELLBEING");
    }

    @Test
    void normalizesCaseAndAliases() {
        assertThat(GoalTemplateService.normalizeDimension("emotional")).isEqualTo("WELLBEING");
        assertThat(GoalTemplateService.normalizeDimension("WELL-BEING")).isEqualTo("WELLBEING");
        assertThat(GoalTemplateService.normalizeDimension("knowledge")).isEqualTo("KNOWLEDGE");
        assertThat(GoalTemplateService.normalizeDimension("health")).isEqualTo("HEALTH");
        assertThat(GoalTemplateService.normalizeDimension("career")).isEqualTo("CAREER");
        assertThat(GoalTemplateService.normalizeDimension("relationship")).isEqualTo("RELATIONSHIP");
    }

    @Test
    void normalizesChineseNames() {
        assertThat(GoalTemplateService.normalizeDimension("情绪")).isEqualTo("WELLBEING");
        assertThat(GoalTemplateService.normalizeDimension("智力")).isEqualTo("KNOWLEDGE");
        assertThat(GoalTemplateService.normalizeDimension("体力")).isEqualTo("HEALTH");
        assertThat(GoalTemplateService.normalizeDimension("职场")).isEqualTo("CAREER");
        assertThat(GoalTemplateService.normalizeDimension("社交")).isEqualTo("RELATIONSHIP");
    }

    @Test
    void rejectsUnknownValues() {
        assertThat(GoalTemplateService.normalizeDimension("magic")).isNull();
        assertThat(GoalTemplateService.normalizeDimension("")).isNull();
        assertThat(GoalTemplateService.normalizeDimension(null)).isNull();
    }
}

class GoalTemplateClampTest {
    @Test
    void clampsSchemaDriftInsteadOfFailing() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = new com.betterself.growth.ai.QwenProvider.StructuredResult(
            """
            {"title":"建立每日心态觉察习惯","description":"通过每天固定时间进行简短的心态回顾，提升对情绪和思维模式的觉察能力，逐步培养更积极、稳定的内在状态。","dimensionCode":"mindset","durationDays":7,"weeklyFocus":"培养每日情绪觉察与自我对话能力","starterTasks":[{"title":"每天早晨花10分钟写下当前最关注的三件事","estimatedMinutes":2,"difficulty":5},{"title":"午间休息时进行5分钟深呼吸并记录情绪状态","estimatedMinutes":5,"difficulty":1}]}
            """,
            "qwen-test", "req-1", 10, 20, 500L
        );
        var view = new GoalTemplateService(null, null, null, mapper).parse("session-1", result);
        assertThat(view.dimensionCode()).isEqualTo("WELLBEING");
        assertThat(view.durationDays()).isEqualTo(14);
        assertThat(view.starterTasks().get(0).estimatedMinutes()).isEqualTo(5);
        assertThat(view.starterTasks().get(0).difficulty()).isEqualTo(3);
    }
}
