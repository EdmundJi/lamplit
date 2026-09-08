package com.betterself.growth.town.companion.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} the accelerated runner drives by hand: every tick calls {@link #advanceTo} on
 * the main run loop's thread, while background {@code ResidentDirector} worker threads read
 * {@link #instant()} concurrently while applying a model reply. The {@link AtomicReference} makes
 * both directions safe without a lock.
 */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void advanceTo(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this; // never used by companion code, which always reasons in the world's own timezone
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
