package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TownNpcDailyStateTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);
    private static final LocalTime MORNING = LocalTime.of(9, 0);

    @Test
    void isStableForTheSameDaySegmentAndNpc() {
        TownNpcDailyState first = TownNpcDailyState.forMoment(DAY, MORNING, TownPersonas.GUIDE);
        TownNpcDailyState second = TownNpcDailyState.forMoment(DAY, MORNING, TownPersonas.GUIDE);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void variesAcrossDays() {
        boolean anyDifference = false;
        TownNpcDailyState base = TownNpcDailyState.forMoment(DAY, MORNING, TownPersonas.POSTMAN);
        for (int offset = 1; offset <= 10; offset++) {
            if (!TownNpcDailyState.forMoment(DAY.plusDays(offset), MORNING, TownPersonas.POSTMAN).equals(base)) {
                anyDifference = true;
                break;
            }
        }
        assertThat(anyDifference).isTrue();
    }

    @Test
    void variesBetweenNpcsOnTheSameDay() {
        assertThat(TownNpcDailyState.forMoment(DAY, MORNING, TownPersonas.GUIDE))
            .isNotEqualTo(TownNpcDailyState.forMoment(DAY, MORNING, TownPersonas.POSTMAN));
    }

    @Test
    void advancesTheSituationAsTheDayMovesThroughSegments() {
        // Same day, same NPC, but talked to in the morning versus late at night: the small
        // event has to have moved on, not repeat the same line the whole day.
        TownNpcDailyState morning = TownNpcDailyState.forMoment(DAY, LocalTime.of(8, 0), TownPersonas.GUIDE);
        TownNpcDailyState night = TownNpcDailyState.forMoment(DAY, LocalTime.of(21, 0), TownPersonas.GUIDE);

        assertThat(morning.situation()).isNotEqualTo(night.situation());
    }

    @Test
    void segmentsCoverTheWholeDayInOrder() {
        assertThat(TownNpcDailyState.segmentFor(LocalTime.of(5, 0))).isEqualTo(TownNpcDailyState.Segment.EARLY_MORNING);
        assertThat(TownNpcDailyState.segmentFor(LocalTime.of(9, 0))).isEqualTo(TownNpcDailyState.Segment.MORNING);
        assertThat(TownNpcDailyState.segmentFor(LocalTime.of(13, 0))).isEqualTo(TownNpcDailyState.Segment.AFTERNOON);
        assertThat(TownNpcDailyState.segmentFor(LocalTime.of(17, 0))).isEqualTo(TownNpcDailyState.Segment.EVENING);
        assertThat(TownNpcDailyState.segmentFor(LocalTime.of(22, 0))).isEqualTo(TownNpcDailyState.Segment.NIGHT);
    }

    @Test
    void guideIsBusyOverLunchOnly() {
        assertThat(TownNpcDailyState.busyNow(TownPersonas.GUIDE, LocalTime.of(12, 30))).isTrue();
        assertThat(TownNpcDailyState.busyNow(TownPersonas.GUIDE, LocalTime.of(11, 59))).isFalse();
        assertThat(TownNpcDailyState.busyNow(TownPersonas.GUIDE, LocalTime.of(13, 0))).isFalse();
    }

    @Test
    void postmanIsBusyDuringTheDeliveryWindowOnly() {
        assertThat(TownNpcDailyState.busyNow(TownPersonas.POSTMAN, LocalTime.of(9, 30))).isTrue();
        assertThat(TownNpcDailyState.busyNow(TownPersonas.POSTMAN, LocalTime.of(8, 59))).isFalse();
        assertThat(TownNpcDailyState.busyNow(TownPersonas.POSTMAN, LocalTime.of(11, 0))).isFalse();
    }
}
