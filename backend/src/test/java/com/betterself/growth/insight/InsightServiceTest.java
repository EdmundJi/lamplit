package com.betterself.growth.insight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsightServiceTest {

    private final WeeklyMetricsCalculator calculator = new WeeklyMetricsCalculator();

    @Test
    void countsOnlyUnreversedCompletedAndPartialEventsAsEffective() {
        WeeklyMetricsCalculator.WeeklyFacts facts = new WeeklyMetricsCalculator.WeeklyFacts(4, List.of(
            event("COMPLETED", false), event("PARTIAL", false), event("SKIPPED", false), event("COMPLETED", true)
        ));

        WeeklyMetricsCalculator.WeeklyMetrics metrics = calculator.calculate(facts);

        assertThat(metrics.effectiveActions()).isEqualTo(2);
        assertThat(metrics.fulfillmentRate()).isEqualByComparingTo("0.500");
    }

    private WeeklyMetricsCalculator.MetricEvent event(String type, boolean reversed) {
        return new WeeklyMetricsCalculator.MetricEvent(type, reversed);
    }
}
