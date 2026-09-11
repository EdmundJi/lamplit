package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/06 · "谁先开口，一半是列表下标决定的": {@code maybeEncounter} used to scan {@code w.residentStates}
 * in its fixed registration order for both "who gets to lock in this tick's one pendingEncounter" and
 * "which of several people in the same room does a resident notice first". Whoever the town happened
 * to register earliest always won that race whenever two eligible people shared a room at once - a
 * measured run put one resident's initiation rate at 91% and another's at 3%, purely from list
 * position. Both orderings are now a deterministic hash of (worldId, residentId, other, time bucket),
 * the same discipline {@code TownPlaces}' own {@code seatPick} already uses for a tied seat choice.
 */
class ResidentSimulationTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld twoInTheGarden(String worldId) {
        CompanionWorld w = CompanionRules.join(worldId, "住客", "Asia/Shanghai", now, true);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = null;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(900));
        }
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    @Test
    void theOuterTurnOrderIsNotAlwaysTheSameFixedResident() {
        Set<String> initiators = new HashSet<>();
        for (int seed = 0; seed < 40; seed++) {
            CompanionWorld w = twoInTheGarden("encounter-hash-" + seed);
            CompanionRules.advance(w, now.plusSeconds(12));
            if (!w.pendingEncounters.isEmpty()) initiators.add(w.pendingEncounters.getFirst().residentId);
        }
        assertThat(initiators).as("who gets to speak first must rotate, not be pinned to one list position")
                .containsExactlyInAnyOrder("owner", "gardener");
    }

    private CompanionWorld threeInTheGarden(String worldId) {
        CompanionWorld w = CompanionRules.join(worldId, "住客", "Asia/Shanghai", now, true);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "student", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = null;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(900));
        }
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    @Test
    void theCandidateOrderInARoomOfThreeIsNotAlwaysTheSamePair() {
        Set<String> pairs = new HashSet<>();
        for (int seed = 0; seed < 40; seed++) {
            CompanionWorld w = threeInTheGarden("encounter-hash3-" + seed);
            CompanionRules.advance(w, now.plusSeconds(12));
            if (!w.pendingEncounters.isEmpty()) {
                var pending = w.pendingEncounters.getFirst();
                pairs.add(pending.residentId + ">" + pending.otherId);
            }
        }
        assertThat(pairs.size()).as("neither who speaks first nor who they approach should be one fixed pair")
                .isGreaterThan(1);
    }
}
