package com.betterself.growth.town;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether one pending promise is fair game to bring up right now. Deliberately plain Java,
 * not SQL: {@code town_npc_promise.made_at} is a {@code DATETIME(3)} column, so a row written
 * with an in-memory {@link Instant} loses everything finer than a millisecond on the way in.
 * Comparing that stored value against a fresh, full-precision {@code Instant} from the very
 * same turn ("made_at < now") is true almost every time — the stored value only ever rounds
 * down — which is exactly how a promise once got asked about in the same breath it was made.
 * The id check below doesn't care about any of that; the gap check only needs minute-scale
 * precision, so the same rounding is nowhere near enough to trip it.
 */
final class TownPromiseWindow {

    static final Duration FOLLOWUP_GAP = Duration.ofMinutes(5);

    private TownPromiseWindow() {
    }

    static boolean canSurface(long candidateId, long justRecordedId, Instant madeAt, Instant now) {
        if (candidateId == justRecordedId) {
            return false;
        }
        return !Duration.between(madeAt, now).minus(FOLLOWUP_GAP).isNegative();
    }
}
