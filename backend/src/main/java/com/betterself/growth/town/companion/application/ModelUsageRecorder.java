package com.betterself.growth.town.companion.application;

/**
 * Records how many prompt/completion tokens one model call spent, so the town's token bill is
 * finally visible instead of discarded. Kept to writes only (see {@link ModelUsageQuery} for reads)
 * so it stays a single-method port a caller can no-op with a lambda when there is nothing to record -
 * mock mode and any {@link ResidentMind} that never measures usage both take that path.
 *
 * day is the caller's own "yyyy-MM-dd" bucket (the companion world already computes one for its daily
 * budget window); callType is "decision", "turn" or "summary", matching ResidentDirector's work kinds.
 */
public interface ModelUsageRecorder {
    void record(long userId, String day, String callType, int inputTokens, int outputTokens);

    /**
     * Provider-aware variant: same counters, but the call type is tagged with which supplier
     * (e.g. "qwen", "deepseek") actually served the call - see {@link ModelUsageQuery#encodeCallType}.
     * Additive on purpose: the default folds the tag into {@code callType} and forwards to the plain
     * method above, so every existing implementation (JdbcModelUsage, the accelerated run's in-memory
     * ledger, test fakes) keeps compiling and behaving unchanged unless it opts in by overriding this.
     */
    default void record(long userId, String day, String callType, String provider, int inputTokens, int outputTokens) {
        record(userId, day, ModelUsageQuery.encodeCallType(callType, provider), inputTokens, outputTokens);
    }
}
