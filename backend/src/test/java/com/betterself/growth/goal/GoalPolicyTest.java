package com.betterself.growth.goal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GoalPolicyTest {

    @Test
    void acceptsInclusiveDurationsFromFourteenToEightyFourDays() {
        LocalDate start = LocalDate.parse("2026-08-03");

        assertThat(GoalPolicy.hasValidDuration(start, start.plusDays(13))).isTrue();
        assertThat(GoalPolicy.hasValidDuration(start, start.plusDays(83))).isTrue();
        assertThat(GoalPolicy.hasValidDuration(start, start.plusDays(12))).isFalse();
        assertThat(GoalPolicy.hasValidDuration(start, start.plusDays(84))).isFalse();
    }

    @Test
    void allowsAtMostThreeActiveGoals() {
        assertThat(GoalPolicy.canCreateActiveGoal(2)).isTrue();
        assertThat(GoalPolicy.canCreateActiveGoal(3)).isFalse();
    }
}
