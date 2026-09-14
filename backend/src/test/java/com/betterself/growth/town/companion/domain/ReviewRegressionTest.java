package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression cases found during the independent v2 domain review. */
class ReviewRegressionTest {
    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");

    @Test void abandoningAnExactResourceWaitRemovesTheResidentFromItsFifoQueue() {
        CompanionWorld w = CompanionRules.join("review-zombie-queue", "我", "Asia/Shanghai", NOW, true);
        for (String id : List.of("fixer", "weaver")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null;
            TownPlaces.release(w, id);
            ResidentSimulation.replaceActor(w, id, "shop", "idle", "在商店", NOW.plusSeconds(3600));
        }

        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "shop", "shop-workroom", "work", "shop-workbench", "先修一会儿", null, List.of(), NOW)).isTrue();
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "shop", "shop-workroom", "work", "shop-workbench", "等工作台", null, List.of(), NOW)).isTrue();
        assertThat(TownPlaces.position(w, "shop-workbench").waitingIds).containsExactly("weaver");

        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "shop", "shop-workroom", "none", null, "先不做了", null, List.of(), NOW.plusSeconds(1))).isTrue();

        assertThat(TownPlaces.position(w, "shop-workbench").waitingIds)
            .as("a resident who abandons the wait must not retain priority over later arrivals")
            .doesNotContain("weaver");
    }

    @Test void choosingNoneKeepsThePositionOfAnExistingPlan() {
        CompanionWorld w = CompanionRules.join("review-none-position", "我", "Asia/Shanghai", NOW, true);
        ResidentState student = ResidentSimulation.state(w, "student");
        student.plan = null;
        TownPlaces.release(w, "student");
        ResidentSimulation.replaceActor(w, "student", "cafe", "idle", "在咖啡馆", NOW.plusSeconds(3600));
        student.roomId = "cafe-main";

        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "cafe", "cafe-main", "study", "cafe-window-seat", "继续复习", null, List.of(), NOW)).isTrue();
        assertThat(student.positionId).isEqualTo("cafe-window-seat");
        assertThat(student.plan.action()).isEqualTo("study");

        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "cafe", "cafe-main", "none", null, "先不改主意", null, List.of(), NOW.plusSeconds(1))).isTrue();

        assertThat(student.positionId).isEqualTo("cafe-window-seat");
        assertThat(student.plan.action()).isEqualTo("study");
        assertThat(TownPlaces.position(w, "cafe-window-seat").occupantIds).containsExactly("student");
    }

    @Test void aBathroomWaiterStaysInTheCommonRoomUntilTheFixtureIsFree() {
        CompanionWorld w = CompanionRules.join("review-bathroom-wait", "我", "Asia/Shanghai", NOW, true);
        w.conversations.clear();
        String home = TownPlaces.homeOf("artist"), bathroom = home + "-bathroom";
        for (String id : List.of("artist", "weaver")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null;
            TownPlaces.release(w, id);
            ResidentSimulation.replaceActor(w, id, home, "idle", "在家", NOW.plusSeconds(3600));
            r.roomId = home + "-room-" + id;
        }

        ResidentState artist = ResidentSimulation.state(w, "artist");
        assertThat(ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "home", bathroom, "bathe", bathroom, "洗个澡", null, List.of(), NOW)).isTrue();
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "home", bathroom, "bathe", bathroom, "等一会儿再洗", null, List.of(), NOW)).isTrue();

        assertThat(TownPlaces.position(w, bathroom).occupantIds).containsExactly("artist");
        assertThat(weaver.roomId).isEqualTo(home + "-common").isNotEqualTo(bathroom);
        assertThat(weaver.desiredRoomId).isEqualTo(bathroom);

        TownPlaces.release(w, "artist");
        ResidentSimulation.step(w, NOW.plusSeconds(12));

        assertThat(TownPlaces.position(w, bathroom).occupantIds).containsExactly("weaver");
        assertThat(weaver.roomId).isEqualTo(bathroom);
    }

    @Test void anAcceptedCafeHelperLearnsTheCounterNeededToTend() {
        CompanionWorld w = CompanionRules.join("review-helper-counter", "我", "Asia/Shanghai", NOW, true);
        assertThat(ResidentSimulation.proposeWorkArrangement(w, "artist", "assist", "owner", "我可以帮忙", NOW)).isTrue();
        String arrangement = w.workArrangements.getLast().id;
        assertThat(ResidentSimulation.acceptWorkArrangement(w, "owner", arrangement, NOW.plusSeconds(1))).isTrue();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        assertThat(artist.knownPositionIds).contains("cafe-counter");

        for (String id : List.of("artist", "student")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null;
            TownPlaces.release(w, id);
            ResidentSimulation.replaceActor(w, id, "cafe", "idle", "在咖啡馆", NOW.plusSeconds(3600));
            r.roomId = "cafe-main";
        }
        CafeService.request(w, ResidentSimulation.state(w, "student"), NOW.plusSeconds(2));

        assertThat(ResidentSimulation.availableActions(w, "artist", NOW.plusSeconds(2))).contains("tend");
        assertThat(ResidentSimulation.availableDecisionOptions(w, "artist", NOW.plusSeconds(2)))
            .anyMatch(option -> "tend".equals(option.action()) && "cafe-counter-room".equals(option.roomId())
                && option.targetIds().size() == 1);
    }

    @Test void aLockedDoorIsNotWitnessedThroughAFlatmatesBedroomWall() {
        CompanionWorld w = CompanionRules.join("review-door-witness", "我", "Asia/Shanghai", NOW, true);
        String home = TownPlaces.homeOf("artist");
        for (String id : List.of("artist", "weaver")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null;
            TownPlaces.release(w, id);
            ResidentSimulation.replaceActor(w, id, home, "idle", "在家", NOW.plusSeconds(3600));
            r.roomId = home + "-room-" + id;
        }
        DoorService.homeDoor(w, home).locked = false;

        assertThat(DoorService.setHomeLocked(w, ResidentSimulation.state(w, "artist"), true, NOW)).isTrue();

        assertThat(w.memories).noneMatch(memory -> "weaver".equals(memory.ownerId())
            && "home-door".equals(memory.topicId()));
    }

    @Test void aBrokenCafeCounterIsNotOfferedAsAServiceThatCanStartPreparing() {
        CompanionWorld w = CompanionRules.join("review-broken-counter", "我", "Asia/Shanghai", NOW, true);
        for (String id : List.of("owner", "student")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null;
            TownPlaces.release(w, id);
            ResidentSimulation.replaceActor(w, id, "cafe", "idle", "在咖啡馆", NOW.plusSeconds(3600));
            r.roomId = "cafe-main";
        }
        CafeService.request(w, ResidentSimulation.state(w, "student"), NOW);
        TownPlaces.position(w, "cafe-counter").condition = "broken";

        assertThat(ResidentSimulation.availableActions(w, "owner", NOW)).doesNotContain("tend");
    }
}
