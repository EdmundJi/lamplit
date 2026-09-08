package com.betterself.growth.town.companion.application;

import java.util.List;

/** Read side of the token usage ledger: "how much did today cost, and which call type is priciest". */
public interface ModelUsageQuery {
    List<DailyUsage> forDay(long userId, String day);

    record DailyUsage(String callType, int callCount, long inputTokens, long outputTokens) {}
}
