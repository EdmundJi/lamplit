package com.betterself.growth.achievement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class AchievementConditionEvaluator {

    private final ObjectMapper objectMapper;

    public AchievementConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isMet(String conditionJson, AchievementService.GrowthMetrics metrics) {
        if (conditionJson == null || conditionJson.isBlank()) {
            return false;
        }
        JsonNode condition;
        try {
            condition = objectMapper.readTree(conditionJson);
        } catch (JsonProcessingException exception) {
            return false;
        }
        if (condition == null || !condition.isObject() || !condition.path("type").isTextual()) {
            return false;
        }

        return switch (condition.path("type").textValue()) {
            case "effective_actions" -> meetsIntegerThreshold(condition, metrics.effectiveActions());
            case "fulfillment" -> meetsRateThreshold(condition, metrics.fulfillmentRate());
            case "recovery_count" -> meetsIntegerThreshold(condition, metrics.recoveryCount());
            case "longest_streak" -> meetsIntegerThreshold(condition, metrics.longestStreak());
            case "total_experience" -> meetsIntegerThreshold(condition, metrics.totalExperience());
            case "role_level" -> meetsRoleLevel(condition, metrics);
            case "role_count" -> meetsRoleCount(condition, metrics);
            case "best_role_level" -> meetsIntegerThreshold(
                condition,
                metrics.roleLevels().values().stream().mapToInt(Integer::intValue).max().orElse(0)
            );
            default -> false;
        };
    }

    private boolean meetsRoleLevel(JsonNode condition, AchievementService.GrowthMetrics metrics) {
        JsonNode roleCode = condition.get("roleCode");
        if (roleCode == null || !roleCode.isTextual() || roleCode.textValue().isBlank()) {
            return false;
        }
        return meetsIntegerThreshold(condition, metrics.roleLevels().getOrDefault(roleCode.textValue(), 0));
    }

    private boolean meetsRoleCount(JsonNode condition, AchievementService.GrowthMetrics metrics) {
        Integer requiredCount = positiveInteger(condition.get("value"));
        Integer minimumLevel = 1;
        if (condition.has("minLevel")) {
            minimumLevel = positiveInteger(condition.get("minLevel"));
        }
        if (requiredCount == null || minimumLevel == null) {
            return false;
        }
        int levelThreshold = minimumLevel;
        long actualCount = metrics.roleLevels().values().stream()
            .filter(level -> level >= levelThreshold)
            .count();
        return actualCount >= requiredCount;
    }

    private boolean meetsIntegerThreshold(JsonNode condition, int actual) {
        Integer required = positiveInteger(condition.get("value"));
        return required != null && actual >= required;
    }

    private boolean meetsRateThreshold(JsonNode condition, BigDecimal actual) {
        JsonNode value = condition.get("value");
        if (value == null || !value.isNumber()) {
            return false;
        }
        BigDecimal required = value.decimalValue();
        return required.compareTo(BigDecimal.ZERO) > 0
            && required.compareTo(BigDecimal.ONE) <= 0
            && actual.compareTo(required) >= 0;
    }

    private Integer positiveInteger(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        int parsed = value.intValue();
        return parsed > 0 ? parsed : null;
    }
}
