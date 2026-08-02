package com.betterself.growth.auth;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

public final class AgePolicy {

    private AgePolicy() {
    }

    public static boolean isAdult(LocalDate birthDate, ZoneId userZone, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(userZone));
        return !birthDate.plusYears(18).isAfter(today);
    }
}
