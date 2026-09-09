package com.betterself.growth.town.companion.application;

import java.util.List;
import java.util.Map;

/** Read side of the token usage ledger: "how much did today cost, and which call type is priciest". */
public interface ModelUsageQuery {
    List<DailyUsage> forDay(long userId, String day);

    /**
     * Short codes for the provider tag folded into a stored call_type (see {@link #encodeCallType}).
     * Kept short on purpose: the persisted column is VARCHAR(16) and a migration is out of scope for
     * this change, so "deepseek"/"qwen" become "ds"/"q3" rather than growing the schema. The old
     * "qwen3" provider tag maps to the same Qwen code for stored-history compatibility.
     */
    Map<String, String> PROVIDER_CODES = Map.of("deepseek", "ds", "qwen", "q3", "qwen3", "q3");

    /**
     * Folds a provider tag into a call type for storage, e.g. ("decision","qwen") -> "decision@q3".
     * A null/blank provider (usage that was never attributed to a specific supplier - the mock
     * provider, an unmeasured call, a pre-routing recorder) leaves the call type untouched.
     */
    static String encodeCallType(String callType, String provider) {
        if (provider == null || provider.isBlank()) return callType;
        return callType + "@" + PROVIDER_CODES.getOrDefault(provider, provider);
    }

    record DailyUsage(String callType, int callCount, long inputTokens, long outputTokens) {
        /** The call type with any "@provider" tag stripped, e.g. "decision@q3" -> "decision". */
        public String baseCallType() {
            int at = callType.indexOf('@');
            return at < 0 ? callType : callType.substring(0, at);
        }

        /** The provider tag, decoded back to its full name, or null when the row carries none. */
        public String provider() {
            int at = callType.indexOf('@');
            if (at < 0) return null;
            String code = callType.substring(at + 1);
            return switch(code) {
                case "q3" -> "qwen"; // includes rows written under the historical qwen3 tag
                case "ds" -> "deepseek";
                default -> code;
            };
        }
    }
}
