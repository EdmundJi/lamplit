package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;

import java.util.function.Consumer;

/**
 * Stands in for a {@link QwenProvider} that has no usable credentials configured (e.g. QWEN3_API_KEY
 * unset in an environment that has not been updated yet). Every call fails the same way a live
 * provider's network/auth failure would, so {@code RoutingResidentMind}'s ordinary cross-provider
 * failover handles "not configured" and "down" identically - no separate code path needed.
 */
public final class UnavailableModelProvider implements QwenProvider {
    private final String reason;
    public UnavailableModelProvider(String reason) { this.reason = reason; }

    @Override public StructuredResult generateStructured(StructuredPrompt prompt) { throw unavailable(); }
    @Override public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) { throw unavailable(); }
    @Override public Classification classify(ClassificationPrompt prompt) { throw unavailable(); }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED", reason);
    }
}
