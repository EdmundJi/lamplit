package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TownNpcCadenceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOON = Instant.parse("2026-09-05T04:00:00Z"); // 12:00 Asia/Shanghai

    @Test
    void isFreshWithNoHistoryAtAll() {
        assertThat(TownNpcCadence.isFreshMeeting(List.of(), ZONE, NOON)).isTrue();
    }

    @Test
    void isFreshWhenTheOnlyHistoryIsFromAnEarlierDay() {
        List<TownNpcService.MessageView> history = List.of(message("ASSISTANT", NOON.minusSeconds(30 * 3600)));

        assertThat(TownNpcCadence.isFreshMeeting(history, ZONE, NOON)).isTrue();
    }

    @Test
    void isNotFreshRightAfterTheAssistantAlreadySpokeToday() {
        List<TownNpcService.MessageView> history = List.of(
            message("ASSISTANT", NOON.minusSeconds(600)),
            message("USER", NOON.minusSeconds(60))
        );

        assertThat(TownNpcCadence.isFreshMeeting(history, ZONE, NOON)).isFalse();
    }

    @Test
    void isFreshAgainAfterALongGapEvenLaterTheSameDay() {
        List<TownNpcService.MessageView> history = List.of(message("ASSISTANT", NOON.minusSeconds(3 * 3600)));

        assertThat(TownNpcCadence.isFreshMeeting(history, ZONE, NOON)).isTrue();
    }

    @Test
    void staysNotFreshJustBelowTheRegreetGap() {
        List<TownNpcService.MessageView> history = List.of(message("ASSISTANT", NOON.minusSeconds(3600)));

        assertThat(TownNpcCadence.isFreshMeeting(history, ZONE, NOON)).isFalse();
    }

    private static TownNpcService.MessageView message(String role, Instant createdAt) {
        return new TownNpcService.MessageView("id", role, "内容", List.of(), List.of(), "COMPLETED", createdAt);
    }
}
