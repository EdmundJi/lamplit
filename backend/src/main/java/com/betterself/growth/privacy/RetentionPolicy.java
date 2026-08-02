package com.betterself.growth.privacy;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Set;

public final class RetentionPolicy {

    public static final Duration EXPORT_OBJECT_TTL = Duration.ofHours(24);
    public static final Duration EXPORT_DOWNLOAD_TTL = Duration.ofMinutes(15);
    public static final Duration DELETION_COOLING_OFF = Duration.ofDays(7);
    public static final Set<Integer> AI_RETENTION_DAYS = Set.of(7, 30, 90);

    private RetentionPolicy() {
    }

    public static int requireAiRetentionDays(int days) {
        if (!AI_RETENTION_DAYS.contains(days)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_RETENTION", "AI retention must be 7, 30, or 90 days");
        }
        return days;
    }
}
