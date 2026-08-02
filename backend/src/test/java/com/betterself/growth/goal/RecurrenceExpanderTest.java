package com.betterself.growth.goal;

import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceExpanderTest {

    private final RecurrenceExpander expander = new RecurrenceExpander();

    @Test
    void expandsWeeklyRuleInUserTimezoneAcrossDst() {
        RecurrenceRule rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR");
        List<PlannedOccurrence> result = expander.expand(
            rule,
            LocalDate.parse("2026-03-02"),
            LocalTime.parse("07:30"),
            ZoneId.of("America/New_York"),
            LocalDate.parse("2026-03-15")
        );

        assertThat(result).hasSize(6);
        assertThat(result.get(3).localDate()).isEqualTo(LocalDate.parse("2026-03-09"));
        assertThat(result.get(3).instant()).isEqualTo(Instant.parse("2026-03-09T11:30:00Z"));
    }

    @Test
    void rejectsUnsupportedRruleProperties() {
        assertThatThrownBy(() -> RecurrenceRule.parse("FREQ=WEEKLY;BYHOUR=7"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).code())
            .isEqualTo("INVALID_RRULE");
    }
}
