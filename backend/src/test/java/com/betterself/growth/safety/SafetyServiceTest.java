package com.betterself.growth.safety;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyServiceTest {

    private final SafetyService safety = new SafetyService(new RiskClassifier());

    @ParameterizedTest
    @ValueSource(strings = {"我不想活了", "我准备伤害自己", "我可能会伤害别人"})
    void sendsCrisisSignalsToL3(String input) {
        SafetyService.SafetyDecision decision = safety.classifyInput("EMOTIONAL_SUPPORT", input);
        assertThat(decision.level()).isEqualTo(RiskLevel.L3);
        assertThat(decision.allowGeneration()).isFalse();
        assertThat(decision.actions()).contains("CALL_LOCAL_EMERGENCY", "CONTACT_TRUSTED_PERSON");
    }
}
