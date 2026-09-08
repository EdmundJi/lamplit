package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.application.ModelUsageQuery;
import com.betterself.growth.town.companion.application.ModelUsageRecorder;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mirror of {@code JdbcModelUsage}: same "one counter row per (user, day, call type)"
 * shape, same additive semantics, but backed by a map instead of {@code town_companion_model_usage}.
 * Used as both the {@link ModelUsageRecorder} handed to {@code ResidentDirector} and the
 * {@link ModelUsageQuery} handed to {@code CompanionService} for an accelerated run, so token
 * spend is counted with zero writes to any real table.
 */
public final class InMemoryModelUsage implements ModelUsageRecorder, ModelUsageQuery {
    private record Key(long userId, String day, String callType) {}
    private static final class Counter {
        int calls;
        long inputTokens;
        long outputTokens;
    }
    private final Map<Key, Counter> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void record(long userId, String day, String callType, int inputTokens, int outputTokens) {
        if (inputTokens <= 0 && outputTokens <= 0) return; // nothing measured, nothing to write - matches JdbcModelUsage
        Counter c = rows.computeIfAbsent(new Key(userId, day, callType), k -> new Counter());
        c.calls++;
        c.inputTokens += inputTokens;
        c.outputTokens += outputTokens;
    }

    @Override
    public List<DailyUsage> forDay(long userId, String day) {
        return rows.entrySet().stream()
            .filter(e -> e.getKey().userId() == userId && e.getKey().day().equals(day))
            .sorted(Comparator.comparing(e -> e.getKey().callType()))
            .map(e -> new DailyUsage(e.getKey().callType(), e.getValue().calls, e.getValue().inputTokens, e.getValue().outputTokens))
            .toList();
    }

    /**
     * Every row ever recorded across every day of the run. {@link ModelUsageQuery#forDay} only
     * ever answers "today" (right for the product's usage screen), but a multi-day accelerated run
     * needs the whole ledger for its report.
     */
    public List<Map<String, Object>> allRows() {
        return rows.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Key, Counter>, String>comparing(e -> e.getKey().day())
                .thenComparing(e -> e.getKey().callType()))
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("day", e.getKey().day());
                row.put("callType", e.getKey().callType());
                row.put("calls", e.getValue().calls);
                row.put("inputTokens", e.getValue().inputTokens);
                row.put("outputTokens", e.getValue().outputTokens);
                return row;
            }).toList();
    }

    public long totalCalls() { return rows.values().stream().mapToLong(c -> c.calls).sum(); }
    public long totalInputTokens() { return rows.values().stream().mapToLong(c -> c.inputTokens).sum(); }
    public long totalOutputTokens() { return rows.values().stream().mapToLong(c -> c.outputTokens).sum(); }
}
