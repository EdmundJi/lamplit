package com.betterself.growth.achievement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AchievementConditionEvaluatorTest {

    private final AchievementConditionEvaluator evaluator = new AchievementConditionEvaluator(new ObjectMapper());

    private AchievementService.GrowthMetrics metrics(
        int effectiveActions, double fulfillmentRate, int recoveryCount,
        int totalExperience, int longestStreak, Map<String, Integer> roleLevels
    ) {
        return new AchievementService.GrowthMetrics(
            effectiveActions, BigDecimal.valueOf(fulfillmentRate), recoveryCount,
            totalExperience, longestStreak, roleLevels
        );
    }

    @Test
    void evaluatesWeeklyActionCounts() {
        String condition = "{\"type\":\"effective_actions\",\"value\":3}";
        assertThat(evaluator.isMet(condition, metrics(3, 0.5, 0, 0, 0, Map.of()))).isTrue();
        assertThat(evaluator.isMet(condition, metrics(2, 0.5, 0, 0, 0, Map.of()))).isFalse();
    }

    @Test
    void evaluatesFulfillmentRateWithDecimalTolerance() {
        String condition = "{\"type\":\"fulfillment\",\"value\":0.75}";
        assertThat(evaluator.isMet(condition, metrics(3, 0.75, 0, 0, 0, Map.of()))).isTrue();
        assertThat(evaluator.isMet(condition, metrics(3, 0.749, 0, 0, 0, Map.of()))).isFalse();
    }

    @Test
    void evaluatesRecoveryAndStreakMilestones() {
        assertThat(evaluator.isMet("{\"type\":\"recovery_count\",\"value\":3}", metrics(0, 0, 3, 0, 0, Map.of()))).isTrue();
        assertThat(evaluator.isMet("{\"type\":\"recovery_count\",\"value\":4}", metrics(0, 0, 3, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"longest_streak\",\"value\":7}", metrics(0, 0, 0, 0, 7, Map.of()))).isTrue();
    }

    @Test
    void evaluatesCumulativeExperience() {
        assertThat(evaluator.isMet("{\"type\":\"total_experience\",\"value\":100}", metrics(0, 0, 0, 100, 0, Map.of()))).isTrue();
        assertThat(evaluator.isMet("{\"type\":\"total_experience\",\"value\":101}", metrics(0, 0, 0, 100, 0, Map.of()))).isFalse();
    }

    @Test
    void evaluatesSingleRoleLevel() {
        String condition = "{\"type\":\"role_level\",\"roleCode\":\"STUDENT\",\"value\":2}";
        Map<String, Integer> roles = Map.of("STUDENT", 3, "WORKER", 1);
        assertThat(evaluator.isMet(condition, metrics(0, 0, 0, 0, 0, roles))).isTrue();
        assertThat(evaluator.isMet(condition, metrics(0, 0, 0, 0, 0, Map.of("STUDENT", 1)))).isFalse();
        assertThat(evaluator.isMet(condition, metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
    }

    @Test
    void evaluatesRoleCountAndBestRoleLevel() {
        Map<String, Integer> roles = Map.of("STUDENT", 6, "WORKER", 3, "FITNESS_USER", 1);
        assertThat(evaluator.isMet("{\"type\":\"role_count\",\"minLevel\":2,\"value\":2}", metrics(0, 0, 0, 0, 0, roles))).isTrue();
        assertThat(evaluator.isMet("{\"type\":\"role_count\",\"minLevel\":5,\"value\":2}", metrics(0, 0, 0, 0, 0, roles))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"best_role_level\",\"value\":6}", metrics(0, 0, 0, 0, 0, roles))).isTrue();
        assertThat(evaluator.isMet("{\"type\":\"best_role_level\",\"value\":10}", metrics(0, 0, 0, 0, 0, roles))).isFalse();
    }

    @Test
    void rejectsUnknownOrMalformedConditions() {
        assertThat(evaluator.isMet("{\"type\":\"unknown\",\"value\":1}", metrics(1, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("not-json", metrics(1, 0, 0, 0, 0, Map.of()))).isFalse();
    }

    @Test
    void rejectsMissingOrInvalidConditionFields() {
        assertThat(evaluator.isMet("{\"type\":\"effective_actions\"}", metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"effective_actions\",\"value\":0}", metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"effective_actions\",\"value\":1.5}", metrics(2, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"fulfillment\",\"value\":0}", metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"fulfillment\",\"value\":1.1}", metrics(0, 1.1, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"role_level\",\"value\":2}", metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet("{\"type\":\"role_count\",\"minLevel\":0,\"value\":1}", metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
        assertThat(evaluator.isMet(null, metrics(0, 0, 0, 0, 0, Map.of()))).isFalse();
    }
}
