package com.betterself.growth.goal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class GoalPolicy {

    private GoalPolicy() {
    }

    public static boolean hasValidDuration(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return false;
        }
        long inclusiveDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        return inclusiveDays >= 14 && inclusiveDays <= 84;
    }

    public static boolean canCreateActiveGoal(int activeGoalCount) {
        return activeGoalCount < 3;
    }
}
