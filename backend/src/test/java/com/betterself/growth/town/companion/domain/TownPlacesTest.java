package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
        for(int index=2;index<=6;index++)TownPlaces.position(w,"cafe-window-"+index).capacity=0;
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

    @Test void theAllocatorHasNoViewAboutWhoseSeatItIs() {
        // It used to: unowned spots sorted strictly first, so 小川's window seat was never offered to
        // anybody else while a public one was free. Two readers who had never seen this repository then
        // read a stretch of the town's life and both named possession as its clearest rule - and the
        // rule-only control showed 131 seatings with somebody else's spot free and not one taken.
        // Nobody was being considerate. They were never offered the choice, and a rule that manufactures
        // the appearance of a norm gets read afterwards as a finding.
        //
        // Asserted as a property rather than a seat id: whose chair anybody ends up in is now supposed
        // to be undetermined by us, so pinning one would put the rule straight back.
        Set<String> landings = new java.util.LinkedHashSet<>();
        for (String who : TownPlaces.RESIDENT_IDS) {
            var w = world();
            assertThat(TownPlaces.claim(w, who, "cafe", "seat", now)).isEqualTo(TownPlaces.Outcome.SEATED);
            landings.add(ResidentSimulation.state(w, who).positionId);
        }
        assertThat(landings).as("每个人都还是坐得下").doesNotContainNull();
        assertThat(landings).as("小川那个位子不再被系统性地绕开").contains("cafe-window-seat");
    }

    @Test void thesameResidentKeepsLandingOnTheSameSeatWhenNothingElseDistinguishesThem() {
        // The tie-break is a hash of (world, resident, spot), not catalogue order - otherwise everybody
        // would pile onto whichever spot happens to be listed first, which is just a quieter written rule.
        String first = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var w = world();
            TownPlaces.claim(w, "artist", "cafe", "seat", now);
            String landed = ResidentSimulation.state(w, "artist").positionId;
            if (first == null) first = landed; else assertThat(landed).isEqualTo(first);
        }
    }

    @Test void anOwnedSeatCanBeBorrowedButAnOwnedPieceOfEquipmentCannot() {
        var w = world();
        // Asking specifically for equipment still lands somewhere else entirely - the counter is simply
        // never a candidate for anyone but its owner. Which of the other spots she ends up in is not our
        // business any more (see theAllocatorHasNoViewAboutWhoseSeatItIs); that it is not the counter is.
        assertThat(TownPlaces.claim(w, "artist", "cafe", "equipment", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(ResidentSimulation.state(w, "artist").positionId).isNotEqualTo("cafe-counter");
        // Now fill every *borrowable* cafe spot (compare
        // aFullSharedSpotMeansWaitingRatherThanBeingSeatedOnTopOfSomeone above, same technique): with
        // the table full and the window seat taken, a latecomer still cannot fall back onto the
        // owner's equipment the way they could fall back onto someone else's chair - they wait instead.
        // Where the artist landed is no longer ours to know, so close the room by capacity rather than
        // by assuming which spot she took.
        TownPlaces.position(w, "cafe-worktable").capacity = 0;
        TownPlaces.position(w, "cafe-window-seat").capacity = 0;
        for(int index=2;index<=6;index++)TownPlaces.position(w,"cafe-window-"+index).capacity=0;
        assertThat(TownPlaces.claim(w, "gardener", "cafe", null, now)).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(TownPlaces.position(w, "cafe-counter").occupantIds).isEmpty();
    }

    @Test void theOwnerReclaimsTheCounterOnDemandWhenAskingForItButNotWhenAskingForSomethingElse() {
        var w = world();
        // Asking specifically for equipment lands the owner on their own counter, same "let" moment as
        // any other owned spot getting reclaimed.
        assertThat(TownPlaces.claim(w, "owner", "cafe", "equipment", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.position(w, "cafe-counter").occupantIds).containsExactly("owner");
        // Asking for their own project work instead (a table) lands them at the shared table, not
        // hijacked onto the counter just because they happen to own something at this place too.
        assertThat(TownPlaces.claim(w, "owner", "cafe", "table", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(ResidentSimulation.state(w, "owner").positionId).isEqualTo("cafe-worktable");
        assertThat(TownPlaces.position(w, "cafe-counter").occupantIds).isEmpty(); // released when they moved on
    }

    @Test void anOlderSaveWithoutTheCounterSelfHealsItOnTheNextSeedCall() {
        var w = world(); // already has the full catalog, including the counter, from world()
        w.positions.removeIf(p -> p.id.equals("cafe-counter")); // simulate a save from before it existed
        assertThat(TownPlaces.position(w, "cafe-counter")).isNull();
        TownPlaces.seed(w);
        assertThat(TownPlaces.position(w, "cafe-counter")).isNotNull();
        assertThat(TownPlaces.position(w, "cafe-counter").ownerId).isEqualTo("owner");
    }

    @Test void sixWindowStudyPlacesAreIndependentAndOnlyTheOriginalBelongsToTheStudent(){
        var w=world();
        assertThat(TownPlaces.position(w,"cafe-window-seat").ownerId).isEqualTo("student");
        assertThat(TownPlaces.position(w,"cafe-window-2").ownerId).isNull();
        assertThat(TownPlaces.position(w,"cafe-window-3").ownerId).isNull();
        for(int index=2;index<=6;index++){var position=TownPlaces.position(w,"cafe-window-"+index);assertThat(position.ownerId).isNull();assertThat(position.capacity).isEqualTo(1);}
        TownPlaces.position(w,"cafe-worktable").capacity=0;
        assertThat(TownPlaces.claim(w,"student","cafe","seat",now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.claim(w,"artist","cafe","seat",now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.claim(w,"gardener","cafe","seat",now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(java.util.Set.of(ResidentSimulation.state(w,"student").positionId,ResidentSimulation.state(w,"artist").positionId,ResidentSimulation.state(w,"gardener").positionId))
            .containsExactlyInAnyOrder("cafe-window-seat","cafe-window-2","cafe-window-3");

        w.positions.removeIf(position->position.id.matches("cafe-window-[2-6]"));
        TownPlaces.seed(w);
        for(int index=2;index<=6;index++)assertThat(TownPlaces.position(w,"cafe-window-"+index)).isNotNull();
    }

    @Test void cafeReadingWritingAndMakingClaimARealSeatInsteadOfOnlyDrawingOne(){
        for(String action:java.util.List.of("read","work","make")){
            CompanionWorld w=CompanionRules.join("cafe-seat-"+action,"我","Asia/Shanghai",now,true);
            w.conversations.stream().filter(c->"active".equals(c.status)).forEach(c->ConversationLifecycle.finish(w,c,now,"测试准备"));
            var artist=ResidentSimulation.state(w,"artist");artist.plan=null;TownPlaces.release(w,"artist");ResidentSimulation.replaceActor(w,"artist","cafe","idle","等下一步",now.plusSeconds(60));
            assertThat(ResidentSimulation.applyDecision(w,"artist",artist.revision,w.intentRevision,"cafe",action,null,"在这里做一会儿",null,java.util.List.of(),now)).isTrue();
            assertThat(artist.positionId).isNotNull();
            assertThat(TownPlaces.position(w,artist.positionId).place).isEqualTo("cafe");
            assertThat(TownPlaces.position(w,artist.positionId).kind).isEqualTo("seat");
        }
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
