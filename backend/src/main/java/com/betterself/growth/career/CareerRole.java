package com.betterself.growth.career;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Map;

public enum CareerRole {
    STUDENT("学生", "STUDY", "KNOWLEDGE"),
    FITNESS_USER("健身用户", "FITNESS", "HEALTH"),
    WORKER("打工人", "CAREER", "CAREER"),
    EMOTIONAL_SUPPORT_USER("情绪支持用户", "EMOTIONAL_SUPPORT", "WELLBEING");

    private final String displayName;
    private final String scene;
    private final String primaryDimension;

    CareerRole(String displayName, String scene, String primaryDimension) {
        this.displayName = displayName;
        this.scene = scene;
        this.primaryDimension = primaryDimension;
    }

    public String displayName() {
        return displayName;
    }

    public String scene() {
        return scene;
    }

    public String primaryDimension() {
        return primaryDimension;
    }

    public static CareerRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAREER_ROLE_REQUIRED", "请选择职业");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CAREER_ROLE", "职业无效");
        }
    }

    public static CareerRole infer(Map<String, Integer> dimensionWeights) {
        if (dimensionWeights != null) {
            if (dimensionWeights.containsKey("KNOWLEDGE")) return STUDENT;
            if (dimensionWeights.containsKey("HEALTH")) return FITNESS_USER;
            if (dimensionWeights.containsKey("CAREER")) return WORKER;
            if (dimensionWeights.containsKey("WELLBEING") || dimensionWeights.containsKey("RELATIONSHIP")) {
                return EMOTIONAL_SUPPORT_USER;
            }
        }
        return STUDENT;
    }
}
