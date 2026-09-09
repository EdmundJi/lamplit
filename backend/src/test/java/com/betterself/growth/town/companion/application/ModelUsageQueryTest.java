package com.betterself.growth.town.companion.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageQueryTest {
    @Test void canonicalQwenAndHistoricalQwen3TagsShareCompactStorageButDecodeAsQwen() {
        assertThat(ModelUsageQuery.encodeCallType("decision","qwen")).isEqualTo("decision@q3");
        assertThat(ModelUsageQuery.encodeCallType("turn","qwen3")).isEqualTo("turn@q3");
        assertThat(new ModelUsageQuery.DailyUsage("summary@q3",1,10,4).provider()).isEqualTo("qwen");
        assertThat(new ModelUsageQuery.DailyUsage("turn@ds",1,10,4).provider()).isEqualTo("deepseek");
    }
}
