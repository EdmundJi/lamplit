package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The places a resident may name, and - just as importantly - the places they may still name even
 * though they would not get in.
 *
 * <p>{@code ResidentDirector.knownPlaces} is derived from {@code w.locations} instead of being written
 * out as a fixed list, and {@code QwenResidentMind} builds the decision schema's {@code place} enum
 * from exactly that derived list. Before this, both were the literal {@code
 * ["home","cafe","street","garden"]} in two separate files, which made a seventh building
 * unnameable however thoroughly it existed - docs/04-decisions.md 「定位是"规则收窄候选集 + 一次调用"
 * …非法项压根不在选项里」.
 */
class KnownPlacesTest {
    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");

    private static CompanionWorld town() {
        return CompanionRules.join("known-places", "我", "Asia/Shanghai", NOW, true);
    }

    @Test void everyResidentIsOfferedTheSharedPlacesAndExactlyOneHome() {
        CompanionWorld w = town();
        for (CompanionWorld.ResidentState r : w.residentStates) {
            List<String> ids = ResidentDirector.knownPlaces(w, r.id).stream()
                .map(ResidentMind.KnownPlaceView::id).toList();
            assertThat(ids).as(r.id + " should be offered the shared places")
                .contains("cafe", "street", "garden");
            // "home" resolves to this resident's own home in the rules - see ResidentMind.modelPlace
            // and ResidentSimulation.applyDecision - so exactly one home entry, never one per house.
            assertThat(ids).filteredOn("home"::equals).as(r.id + " gets one home, not six").hasSize(1);
            assertThat(ids).as("nobody else's house is addressable yet")
                .noneMatch(id -> id.startsWith("home-"));
        }
    }

    @Test void aBuildingAddedToTheWorldBecomesNameableWithoutTouchingThisCode() {
        CompanionWorld w = town();
        w.locations.add(new CompanionWorld.Location("bathhouse", "bathhouse", null));

        List<ResidentMind.KnownPlaceView> places = ResidentDirector.knownPlaces(w, "owner");
        assertThat(places).extracting(ResidentMind.KnownPlaceView::id).contains("bathhouse");
        // Plainly described rather than left out: being addressable matters more than being described
        // well, and a building nobody can name is worse than one described in one flat sentence.
        assertThat(places).filteredOn(p -> p.id().equals("bathhouse")).singleElement()
            .satisfies(p -> assertThat(p.description()).isNotBlank());
    }

    /**
     * The one narrowing that must never happen. A locked cafe door is, in {@code DoorService}'s own
     * words, "this town's first real information asymmetry": whoever was standing there saw it happen,
     * and everybody else finds out only by walking up to it ({@code DoorService.perceiveLockedOut}).
     * Dropping "cafe" from the offered places while the door is locked would hand that fact to every
     * resident for free, and they would stop even trying - which deletes the asymmetry the door exists
     * to create. Rules narrow to what is <b>addressable</b>, never to what will <b>succeed</b>.
     */
    @Test void aLockedDoorDoesNotQuietlyRemoveTheCafeFromWhatAResidentMayName() {
        CompanionWorld w = town();
        // Locked directly on the world rather than through DoorService (package-private in domain):
        // what is under test is what a resident may NAME, and that must not consult this bit at all.
        CompanionWorld.Door door = w.doors.stream().filter(d -> "cafe".equals(d.place)).findFirst()
            .orElseGet(() -> { CompanionWorld.Door d = new CompanionWorld.Door(); d.id = "cafe-door"; d.place = "cafe"; w.doors.add(d); return d; });
        door.locked = true; door.lockedBy = "owner"; door.lockedAt = NOW;
        assertThat(door.locked).isTrue();

        assertThat(ResidentDirector.knownPlaces(w, "student"))
            .as("a resident who was not there must still be able to walk to a locked door")
            .extracting(ResidentMind.KnownPlaceView::id).contains("cafe");
    }
}
