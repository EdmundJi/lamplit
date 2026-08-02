package com.betterself.growth.admin;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

public final class TelemetryPolicy {

    private static final Set<String> ALLOWED = Set.of("eventType", "scene", "status", "count", "durationBucket", "riskLevel");
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of("email", "title", "note", "message", "text", "prompt", "content");

    private TelemetryPolicy() {
    }

    public static Map<String, Object> validate(Map<String, Object> properties) {
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String lower = entry.getKey().toLowerCase();
            if (!ALLOWED.contains(entry.getKey()) || SENSITIVE_FRAGMENTS.stream().anyMatch(lower::contains)
                || !(entry.getValue() instanceof Number || entry.getValue() instanceof Boolean || entry.getValue() instanceof Enum<?> || entry.getValue() instanceof String string && string.matches("[A-Z0-9_]{1,40}"))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SENSITIVE_TELEMETRY_REJECTED", "Telemetry properties are not allowed");
            }
        }
        return Map.copyOf(properties);
    }
}
