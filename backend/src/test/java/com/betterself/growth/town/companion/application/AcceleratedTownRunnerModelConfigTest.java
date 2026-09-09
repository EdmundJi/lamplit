package com.betterself.growth.town.companion.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcceleratedTownRunnerModelConfigTest {
    @Test void defaultsToTheQwenPrimaryAndReadsItsModelGroup() {
        var env=Map.of("QWEN_MODEL","qwen3.8-flash");
        assertThat(AcceleratedTownRunner.modelProvider(env)).isEqualTo("qwen");
        assertThat(AcceleratedTownRunner.modelName(env)).isEqualTo("qwen3.8-flash");
    }

    @Test void deepseekMustBeSelectedExplicitlyAndThenReadsOnlyItsOwnGroup() {
        var env=Map.of("COMPANION_RUN_MODEL_PROVIDER","deepseek","QWEN_MODEL","qwen3.8-flash","DEEPSEEK_MODEL","deepseek-v4-flash");
        assertThat(AcceleratedTownRunner.modelProvider(env)).isEqualTo("deepseek");
        assertThat(AcceleratedTownRunner.modelName(env)).isEqualTo("deepseek-v4-flash");
    }

    @Test void oldQwen3SelectionIsCanonicalizedAndUnknownProviderIsRejected() {
        assertThat(AcceleratedTownRunner.modelProvider(Map.of("COMPANION_RUN_MODEL_PROVIDER","qwen3"))).isEqualTo("qwen");
        assertThat(AcceleratedTownRunner.modelName(Map.of("QWEN3_MODEL","qwen3.8-flash"))).isEqualTo("qwen3.8-flash");
        assertThatThrownBy(()->AcceleratedTownRunner.modelProvider(Map.of("COMPANION_RUN_MODEL_PROVIDER","auto")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("qwen or deepseek");
    }
}
