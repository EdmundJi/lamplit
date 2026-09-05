package com.betterself.growth.town;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Whether this turn is the start of a conversation, as opposed to one more reply in an
 * already-running one. A real person states where they are and how they feel once, when
 * you first find them that day — not again three replies later just because the fact hasn't
 * changed. "Fresh" means either nobody has heard from this NPC yet today, or the exchange
 * went quiet long enough that picking it back up deserves restating where things stand.
 */
final class TownNpcCadence {

    private static final Duration REGREET_GAP = Duration.ofHours(2);

    private TownNpcCadence() {
    }

    static boolean isFreshMeeting(List<TownNpcService.MessageView> history, ZoneId zone, Instant now) {
        if (history.isEmpty()) {
            return true;
        }
        LocalDate today = now.atZone(zone).toLocalDate();
        boolean spokeToday = history.stream().anyMatch(item ->
            "ASSISTANT".equals(item.role()) && item.createdAt().atZone(zone).toLocalDate().equals(today)
        );
        if (!spokeToday) {
            return true;
        }
        Instant lastMessageAt = history.get(history.size() - 1).createdAt();
        return Duration.between(lastMessageAt, now).compareTo(REGREET_GAP) > 0;
    }
}
