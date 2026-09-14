package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import com.betterself.growth.town.companion.domain.ResidentSimulation.PositionUseView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "谁在用什么" (docs/04-decisions.md 2026-09-14 「对话出口与物品使用状态」): {@link
 * ResidentSimulation#positionUses} is wholly derived off {@link Position#occupantIds}, {@link
 * ResidentState#activitySince} and {@link WorldObject#holderId} - the same facts {@code TownPlaces}
 * and {@code ResidentSimulation.replaceActor} already keep for their own reasons, never a second
 * free-text copy that could drift from them. These tests exercise the derivation directly, the same
 * style {@code SeatClaimEventTest} already uses for {@code TownPlaces} itself.
 */
class PositionUseViewTest {
    private final Instant now = Instant.parse("2026-09-14T10:00:00Z");

    private CompanionWorld world() {
        var w = CompanionRules.join("position-use-test", "住客", "Asia/Shanghai", now, true);
        w.conversations.clear();
        return w;
    }

    /** Seats a real resident on a real named position through the same rule-owned machinery the live
     * simulation uses ({@code TownPlaces.claimExact} for occupancy, the timed {@code replaceActor} for
     * {@code activitySince}) - never writing either field by hand, so this test exercises the exact
     * choke points the derivation reads. */
    private void seatAt(CompanionWorld w, String id, String positionId, String activity, Instant since) {
        Position p = TownPlaces.position(w, positionId);
        ResidentSimulation.replaceActor(w, id, p.place, activity, activity, now.plusSeconds(3600), since);
        TownPlaces.claimExact(w, id, positionId, now);
        ResidentSimulation.state(w, id).roomId = p.roomId;
    }
    private void standIn(CompanionWorld w, String id, String place, String roomId) {
        ResidentSimulation.replaceActor(w, id, place, "observe", "四下看看", now.plusSeconds(600), now);
        ResidentSimulation.state(w, id).roomId = roomId;
    }

    /** The live scenario docs/04-decisions.md asks for: a neighbour sharing a room sees not just what
     * someone is doing but how long, and what they are holding while they do it - 老谭那本书, in the
     * docs' own phrasing; here 阿禾 (owner) is the one holding it, since this town's own six residents
     * are who ResidentDirectorDialogueTest already reuses for the live-reported 顾雁/时安 scene. */
    @Test void aNeighbourSeesWhatSomeoneIsUsingHowLongAndWhatTheyAreHolding() {
        var w = world();
        seatAt(w, "owner", "cafe-worktable", "read", now.minusSeconds(40 * 60));
        seatAt(w, "artist", "cafe-window-2", "observe", now.minusSeconds(5 * 60));
        w.objects.add(new WorldObject("taner-book", "book", "cafe", "cafe-main", "书", "open", null, "owner", "owner"));

        var seenByArtist = ResidentSimulation.positionUses(w, "artist", now);
        PositionUseView ownerUse = seenByArtist.stream().filter(v -> v.occupantId().equals("owner")).findFirst().orElseThrow();
        assertThat(ownerUse.positionId()).isEqualTo("cafe-worktable");
        assertThat(ownerUse.label()).isEqualTo("那张长桌");
        assertThat(ownerUse.occupantName()).isEqualTo(ResidentSimulation.actor(w, "owner").name());
        assertThat(ownerUse.activity()).isEqualTo("看书");
        assertThat(ownerUse.minutesSoFar()).isEqualTo(40);
        assertThat(ownerUse.heldObjectLabel()).isEqualTo("书");
        // "顾雁 在长桌 看书 40分钟" - the exact shape, in this town's own resident and words.
        assertThat(ownerUse.occupantName() + " 在" + ownerUse.label() + " " + ownerUse.activity() + " " + ownerUse.minutesSoFar() + "分钟")
            .isEqualTo(ResidentSimulation.actor(w, "owner").name() + " 在那张长桌 看书 40分钟");

        // "Also include what the resident themself is using."
        PositionUseView selfUse = seenByArtist.stream().filter(v -> v.occupantId().equals("artist")).findFirst().orElseThrow();
        assertThat(selfUse.positionId()).isEqualTo("cafe-window-2");
        assertThat(selfUse.minutesSoFar()).isEqualTo(5);
    }

    @Test void aOneCapacitySpotShowsExactlyOneUserAndASecondResidentSeesItOccupied() {
        var w = world();
        seatAt(w, "owner", "cafe-window-2", "read", now.minusSeconds(10 * 60));
        String room = TownPlaces.position(w, "cafe-window-2").roomId;
        standIn(w, "artist", "cafe", room);

        var seenByArtist = ResidentSimulation.positionUses(w, "artist", now);
        assertThat(seenByArtist).filteredOn(v -> v.positionId().equals("cafe-window-2"))
            .hasSize(1).extracting(PositionUseView::occupantId).containsExactly("owner");

        // The existing capacity rule, unmoved by this batch: a second resident cannot take an occupied
        // one-capacity spot, they only join its wait queue.
        var outcome = TownPlaces.claimExact(w, "artist", "cafe-window-2", now);
        assertThat(outcome).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(TownPlaces.position(w, "cafe-window-2").waitingIds).contains("artist");
        // Still exactly one occupant from either side - the perception never outruns the physical fact.
        assertThat(ResidentSimulation.positionUses(w, "owner", now))
            .filteredOn(v -> v.positionId().equals("cafe-window-2")).hasSize(1);
    }

    @Test void leavingThePositionDropsItFromANeighboursViewBecauseItIsDerivedNotStored() {
        var w = world();
        seatAt(w, "owner", "cafe-worktable", "read", now.minusSeconds(15 * 60));
        standIn(w, "artist", "cafe", TownPlaces.position(w, "cafe-worktable").roomId);
        assertThat(ResidentSimulation.positionUses(w, "artist", now)).anyMatch(v -> v.occupantId().equals("owner"));

        TownPlaces.release(w, "owner", now);
        assertThat(ResidentSimulation.positionUses(w, "artist", now)).noneMatch(v -> v.occupantId().equals("owner"));
    }

    @Test void someoneInADifferentRoomDoesNotAppearInTheView() {
        var w = world();
        seatAt(w, "gardener", "home-gardener-bed", "sleep", now.minusSeconds(60 * 60));
        standIn(w, "artist", "cafe", "cafe-main");

        assertThat(ResidentSimulation.positionUses(w, "artist", now)).noneMatch(v -> v.occupantId().equals("gardener"));
    }
}
