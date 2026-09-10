package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user's own figure could not sleep. Not "slept badly" - there was no code path anywhere that ever
 * set its activity to {@code sleep}: the autopilot's night branch produced {@code "home"} under the
 * label 回家休息，灯光轻轻暗下来, {@link CompanionRules#KINDS} had no sleep for the user to ask for, and
 * {@link ResidentSeed} skips "self" so it never had sleeping hours to begin with. It sat in the living
 * room all night, drained energy at the waking rate, and stayed someone the town could walk up to at
 * three in the morning.
 */
class AvatarSleepTest {

    /** 02:00 local (Asia/Shanghai) - the middle of anybody's night. */
    private final Instant night = Instant.parse("2026-01-01T18:00:00Z");
    private final Instant noon = Instant.parse("2026-01-01T04:00:00Z");

    private CompanionWorld world(Instant at) {
        CompanionWorld w = CompanionRules.join("avatar-sleep", "我", "Asia/Shanghai", at, false);
        w.intents.clear();
        return w;
    }

    /** Pushes the world past the avatar's current activity so the autopilot picks the next one. */
    private void letTheAutopilotChoose(CompanionWorld w, Instant at) {
        CompanionRules.advance(w, at.plusSeconds(180));
    }

    @Test
    @DisplayName("夜里，玩家的小人是睡着的，不是在客厅坐着")
    void theAvatarIsAsleepAtNightRatherThanSittingUp() {
        CompanionWorld w = world(night);
        letTheAutopilotChoose(w, night);
        assertThat(w.avatar.activity()).isEqualTo("sleep");
        assertThat(TownPlaces.isHome(w.avatar.place())).isTrue();
    }

    @Test
    @DisplayName("白天不会被判成夜里")
    void staysAwakeInTheMiddleOfTheDay() {
        CompanionWorld w = world(noon);
        letTheAutopilotChoose(w, noon);
        assertThat(w.avatar.activity()).isNotEqualTo("sleep");
    }

    @Test
    @DisplayName("化身和别人一样有自己的作息，不再是全镇唯一没有作息的人")
    void theAvatarHasSleepingHoursLikeEverybodyElse() {
        CompanionWorld w = world(noon);
        ResidentState self = ResidentSimulation.ensureAvatarState(w);
        assertThat(self.sleepScheduleSeeded).isTrue();
        assertThat(ResidentSimulation.withinUsualSleepWindow(w, "self", night)).isTrue();
        assertThat(ResidentSimulation.withinUsualSleepWindow(w, "self", noon)).isFalse();
    }

    @Test
    @DisplayName("老存档里那个没有作息的化身，会自己长出作息来")
    void anOlderSaveHealsIntoHavingHours() {
        CompanionWorld w = world(noon);
        ResidentState self = ResidentSimulation.state(w, "self");
        self.sleepScheduleSeeded = false; self.usualSleepMinute = 0; self.usualWakeMinute = 0;
        assertThat(ResidentSimulation.ensureAvatarState(w).sleepScheduleSeeded).isTrue();
    }

    @Test
    @DisplayName("睡着的时候没人能过来搭话——这以前不成立，因为它的活动是「home」")
    void nobodyCanStrikeUpAConversationWithSomeoneAsleep() {
        CompanionWorld w = world(night);
        letTheAutopilotChoose(w, night);
        assertThat(w.avatar.activity()).isEqualTo("sleep");
        // The same exclusion set every "is this person available / present" check in the town uses.
        assertThat(Set.of("walk", "travel", "sleep", "rest", "away", "tend"))
                .contains(w.avatar.activity());
    }

    @Test
    @DisplayName("用户自己也能说「我去睡了」")
    void theUserCanAskToGoToBed() {
        assertThat(CompanionRules.KINDS).contains("sleep");
    }

    @Test
    @DisplayName("睡着的人躺在床上，不是站在屋里")
    void sleepingClaimsTheBed() {
        CompanionWorld w = world(night);
        letTheAutopilotChoose(w, night);
        assertThat(w.positions.stream()
                .filter(p -> p.occupantIds.contains("self"))
                .map(p -> p.kind))
                .as("占的是床").containsExactly("bed");
    }
}
