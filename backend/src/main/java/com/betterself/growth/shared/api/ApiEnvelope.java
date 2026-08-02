package com.betterself.growth.shared.api;

import java.time.Clock;
import java.time.Instant;

public record ApiEnvelope<T>(T data, String requestId, Instant timestamp) {

    public static <T> ApiEnvelope<T> of(T data, String requestId, Clock clock) {
        return new ApiEnvelope<>(data, requestId, clock.instant());
    }
}
