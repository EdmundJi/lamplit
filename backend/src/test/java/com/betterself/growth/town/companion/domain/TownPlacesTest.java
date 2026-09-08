package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class TownPlacesTest {
    final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld world() {
        CompanionWorld w = new CompanionWorld();
        w.id = "places-test"; w.timezone = "Asia/Shanghai";
        TownPlaces.seed(w);
        for (String id : TownPlaces.RESIDENT_IDS) {
            CompanionWorld.ResidentState r = new CompanionWorld.ResidentState();
            r.id = id;
            w.residentStates.add(r);
        }
        return w;
    }

    @Test void eachResidentHasItsOwnHomeAndTheAvatarHasOneTooWithoutAddingAnyPlaces() {
        assertThat(TownPlaces.places()).containsExactlyInAnyOrder(
            "street", "cafe", "garden", "home-owner", "home-student", "home-artist", "home-gardener", "home-self");
        assertThat(TownPlaces.homeOf("owner")).isEqualTo("home-owner");
        assertThat(TownPlaces.homeOf("self")).isEqualTo("home-self");
        assertThat(TownPlaces.isHome("home-owner")).isTrue();
        assertThat(TownPlaces.isHome("cafe")).isFalse();
    }

    @Test void anOwnerAlwaysGetsTheirOwnSpotEvenIfSomeoneElseIsThere() {
        var w = world();
        // With the shared table full, a non-owner sits at the (otherwise free) window seat instead
        // of being told to wait.
        TownPlaces.position(w, "cafe-worktable").capacity = 0;
        assertThat(TownPlaces.claim(w, "owner", "cafe", "seat", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.position(w, "cafe-window-seat").occupantIds).containsExactly("owner");
        // The seat's actual owner arrives and wants it back - the visitor yields it.
        var outcome = TownPlaces.claim(w, "student", "cafe", "seat", now);
        assertThat(outcome).isEqualTo(TownPlaces.Outcome.YIELDED);
        assertThat(TownPlaces.position(w, "cafe-window-seat").occupantIds).containsExactly("student");
        // The displaced visitor no longer claims any position.
        assertThat(ResidentSimulation.state(w, "owner").positionId).isNull();
        assertThat(ResidentSimulation.state(w, "student").positionId).isEqualTo("cafe-window-seat");
    }

    @Test void aFullSharedSpotMeansWaitingRatherThanBeingSeatedOnTopOfSomeone() {
        var w = world();
        TownPlaces.position(w, "garden-bench").capacity = 1;
        assertThat(TownPlaces.claim(w, "owner", "garden", null, now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.claim(w, "gardener", "garden", "plot", now)).isEqualTo(TownPlaces.Outcome.SEATED); // fills the last spot
        // Every position in the garden is now occupied, and this resident owns none of them.
        assertThat(TownPlaces.claim(w, "student", "garden", null, now)).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(ResidentSimulation.state(w, "student").positionId).isNull();
        assertThat(TownPlaces.position(w, "garden-bench").occupantIds).containsExactly("owner");
    }

    @Test void whenTheOwnedSpotIsUnavailableAVisitorSwitchesToAnUnownedOneInstead() {
        var w = world();
        // The window seat belongs to the student; anyone else prefers the shared table instead.
        assertThat(TownPlaces.claim(w, "artist", "cafe", "seat", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(ResidentSimulation.state(w, "artist").positionId).isEqualTo("cafe-worktable");
    }

    @Test void releasingFreesTheSpotForSomeoneElse() {
        var w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now);
        assertThat(TownPlaces.position(w, "garden-plot").occupantIds).containsExactly("gardener");
        TownPlaces.release(w, "gardener");
        assertThat(TownPlaces.position(w, "garden-plot").occupantIds).isEmpty();
        assertThat(ResidentSimulation.state(w, "gardener").positionId).isNull();
    }
}
