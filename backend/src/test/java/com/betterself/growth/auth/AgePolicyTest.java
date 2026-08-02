package com.betterself.growth.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security")
class AgePolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-31T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsUserWhoIsNotEighteenInTheirTimezone() {
        assertThat(AgePolicy.isAdult(
            LocalDate.parse("2008-08-01"),
            ZoneId.of("Asia/Shanghai"),
            clock
        )).isFalse();
    }

    @Test
    void acceptsUserOnEighteenthBirthday() {
        assertThat(AgePolicy.isAdult(
            LocalDate.parse("2008-07-31"),
            ZoneId.of("Asia/Shanghai"),
            clock
        )).isTrue();
    }
}
