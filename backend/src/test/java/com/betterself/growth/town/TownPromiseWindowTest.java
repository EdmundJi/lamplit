package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TownPromiseWindowTest {

    @Test
    void excludesTheJustRecordedRowByIdEvenWhenATimestampComparisonWouldWronglyAllowIt() {
        // What actually happened in production: made_at is written from an in-memory Instant
        // that carries a sub-millisecond remainder, but the DATETIME(3) column floors it away
        // on write. Reading it back and comparing against a fresh "now" from later in the very
        // same turn is therefore true almost every time — the trap this test pins down.
        Instant now = Instant.parse("2026-09-05T01:00:00.135723891Z");
        Instant storedMadeAt = Instant.ofEpochMilli(now.toEpochMilli()); // what DATETIME(3) actually persists

        assertThat(storedMadeAt.isBefore(now)).isTrue(); // the trap: a timestamp-only check would wrongly allow this
        assertThat(TownPromiseWindow.canSurface(42L, 42L, storedMadeAt, now)).isFalse(); // the id check is what actually saves it
    }

    @Test
    void allowsADifferentRowOnceTheMinimumGapHasPassed() {
        Instant madeAt = Instant.parse("2026-09-05T01:00:00Z");
        Instant now = madeAt.plus(TownPromiseWindow.FOLLOWUP_GAP);

        assertThat(TownPromiseWindow.canSurface(1L, 2L, madeAt, now)).isTrue();
    }

    @Test
    void rejectsADifferentRowBeforeTheMinimumGapHasPassed() {
        Instant madeAt = Instant.parse("2026-09-05T01:00:00Z");
        Instant now = madeAt.plus(TownPromiseWindow.FOLLOWUP_GAP).minusSeconds(1);

        assertThat(TownPromiseWindow.canSurface(1L, 2L, madeAt, now)).isFalse();
    }
}
