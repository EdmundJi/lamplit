package com.betterself.growth.shared.api;

import java.util.Map;

public record ApiError(int status, String code, String message, Map<String, Object> details) {

    public ApiError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, Map.of());
    }
}
