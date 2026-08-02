package com.betterself.growth.insight;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class WeeklyMetricsCalculator {

    public WeeklyMetrics calculate(WeeklyFacts facts) {
        long effective = facts.events().stream()
            .filter(event -> !event.reversed())
            .filter(event -> event.type().equals("COMPLETED") || event.type().equals("PARTIAL"))
            .count();
        BigDecimal rate = facts.plannedActions() == 0
            ? BigDecimal.ZERO.setScale(3)
            : BigDecimal.valueOf(effective)
                .divide(BigDecimal.valueOf(facts.plannedActions()), 3, RoundingMode.HALF_UP);
        return new WeeklyMetrics(facts.plannedActions(), (int) effective, rate);
    }

    public record WeeklyFacts(int plannedActions, List<MetricEvent> events) {
    }

    public record MetricEvent(String type, boolean reversed) {
    }

    public record WeeklyMetrics(int plannedActions, int effectiveActions, BigDecimal fulfillmentRate) {
    }
}
