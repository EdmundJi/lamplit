package com.betterself.growth.insight;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    @Test
    void usesTheUsersLocalDateForTheCurrentWeekAtUtcDateBoundaries() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-02T16:30:00Z"), ZoneOffset.UTC);

        InsightService.WeekRange shanghaiWeek = InsightService.currentWeek(clock, ZoneId.of("Asia/Shanghai"));
        InsightService.WeekRange losAngelesWeek = InsightService.currentWeek(clock, ZoneId.of("America/Los_Angeles"));

        assertThat(shanghaiWeek.start()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(shanghaiWeek.end()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(losAngelesWeek.start()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(losAngelesWeek.end()).isEqualTo(LocalDate.of(2026, 8, 2));
    }

    @Test
    void convertsExperienceIntoBoundedAttributeLevelsAndRadarScores() {
        assertThat(InsightService.attributeLevel(0)).isEqualTo(1);
        assertThat(InsightService.attributeLevel(100)).isEqualTo(2);
        assertThat(InsightService.attributeLevel(1_000_000)).isEqualTo(20);
        assertThat(InsightService.attributeScore(0)).isZero();
        assertThat(InsightService.attributeScore(100)).isBetween(1, 99);
        assertThat(InsightService.attributeScore(1_000_000)).isEqualTo(100);
    }

    private WeeklyMetricsCalculator.MetricEvent event(String type, boolean reversed) {
        return new WeeklyMetricsCalculator.MetricEvent(type, reversed);
    }
}
