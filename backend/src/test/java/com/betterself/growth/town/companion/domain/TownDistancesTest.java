package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Pins TownDistances - the pixel-accurate table {@link ResidentSimulation#travelSeconds} now reads
 * instead of the old abstract "street position" units - to the numbers it was actually built from
 * (see that class's own doc comment: measured on the frontend's real pathfinder, not estimated).
 */
class TownDistancesTest {

    @Test void tableIsSymmetric(){
        // Every place TownPlaces knows about, paired with every other one: distancePixels(a,b) must
        // equal distancePixels(b,a) exactly, because TownDistances.link() only ever records both
        // directions from one source line - an asymmetric entry would mean two different measured
        // numbers for what is physically the same walk there and back.
        List<String> places = List.copyOf(TownPlaces.places());
        for (int i = 0; i < places.size(); i++)
            for (int j = i + 1; j < places.size(); j++) {
                String a = places.get(i), b = places.get(j);
                assertThat(TownDistances.distancePixels(a, b))
                    .as("distance(%s,%s) vs distance(%s,%s)", a, b, b, a)
                    .isEqualTo(TownDistances.distancePixels(b, a));
            }
    }

    @Test void everyPairAmongTheStaticPlacesIsMeasured(){
        // TownPlaces.places() is the fixed catalogue (street, cafe, garden, and the five RESIDENT_IDS
        // homes) - every one of its 28 unordered pairs must resolve to an actual measured pixel
        // distance, never a silent null that would send ResidentSimulation.travelSeconds down the
        // unmeasured-place fallback for a place that has been in the game since day one.
        List<String> places = List.copyOf(TownPlaces.places());
        assertThat(places).hasSize(8);
        for (int i = 0; i < places.size(); i++)
            for (int j = i + 1; j < places.size(); j++) {
                String a = places.get(i), b = places.get(j);
                assertThat(TownDistances.distancePixels(a, b))
                    .as("measured distance between %s and %s", a, b)
                    .isNotNull();
            }
    }

    @Test void everyPairInTheExpandedTwentyFiveResidentTownIsMeasured(){
        CompanionWorld w=CompanionRules.join("distance-v2","我","Asia/Shanghai",java.time.Instant.parse("2026-09-14T06:00:00Z"),false);
        List<String> places=w.locations.stream().map(CompanionWorld.Location::id).toList();
        assertThat(places).hasSize(23);
        for(int i=0;i<places.size();i++)for(int j=i+1;j<places.size();j++)
            assertThat(TownDistances.distancePixels(places.get(i),places.get(j))).as("%s to %s",places.get(i),places.get(j)).isNotNull();
    }

    @Test void sharedHomeCollapsesToTheSamePlaceIdNotAZeroDistanceEntry(){
        // The artist and the weaver share one physical room (TownPlaces.HOME_SHARED_WITH), so
        // homeOf("weaver") already resolves to the literal id "home-artist" - there never is a
        // distinct "home-weaver" place for TownDistances to store a distance for. Walking "there" is
        // the same-place case travelSeconds floors to MIN_TRAVEL_SECONDS, never zero.
        assertThat(TownPlaces.homeOf("weaver")).isEqualTo(TownPlaces.homeOf("artist")).isEqualTo("home-artist");
        assertThat(TownDistances.distancePixels("home-artist", "home-artist")).isNull();
        assertThat(ResidentSimulation.travelSeconds(TownPlaces.homeOf("weaver"), TownPlaces.homeOf("artist")))
            .as("same room, still a positive floor, never an instant zero-second trip")
            .isEqualTo(10)
            .isNotEqualTo(0);
    }

    @Test void longestAndShortestMeasuredTripsLandInTheirActualComputedSeconds(){
        // home-fixer to garden is the single longest entry in the whole table: 1500px, measured by
        // walking the frontend's real pathfinder between the exact points travelAnchor() returns
        // (see TownDistances' doc comment) - at WALK_PIXELS_PER_SECOND (32px/s) that is 1500/32 = 46
        // (integer division truncates the .87).
        assertThat(TownDistances.distancePixels("home-fixer", "garden")).isEqualTo(1500);
        assertThat(ResidentSimulation.travelSeconds("home-fixer", "garden")).isEqualTo(46);

        // home-student to street is the shortest entry that involves a resident's home: 152px, which
        // at 32px/s is under five seconds (152/32 = 4) - short enough that MIN_TRAVEL_SECONDS (10)
        // is what actually governs the answer, not the raw division. This is deliberate: even the
        // closest walk in town still takes a person a few real seconds to make.
        assertThat(TownDistances.distancePixels("home-student", "street")).isEqualTo(152);
        assertThat(ResidentSimulation.travelSeconds("home-student", "street")).isEqualTo(10);

        assertThat(ResidentSimulation.travelSeconds("home-fixer", "garden"))
            .as("the longest measured walk in town still takes far longer than the shortest one")
            .isGreaterThan(ResidentSimulation.travelSeconds("home-student", "street"));
    }

    @Test void unmeasuredPlaceFallsBackRatherThanFailing(){
        // A hypothetical future building (academy, gym - not yet part of the town) has no measured
        // entry at all: distancePixels returns null, and travelSeconds must not throw or silently
        // return an implausibly short trip for it.
        assertThat(TownDistances.distancePixels("home-owner", "bathhouse")).isNull();
        assertThat(ResidentSimulation.travelSeconds("home-owner", "bathhouse")).isEqualTo(79);
    }
}
