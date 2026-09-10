package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resident looking around used to see only things. The reflection prompt asks them to write down a
 * standing view when they can see something recurring in their own memories, and offers "某个人总是坐在
 * 某个位置" as the example - while nothing in the world had ever written down where anybody sat. Three
 * simulated days of a model run produced three beliefs, all three about the resident themselves.
 * A norm is a fact about other people; nobody forms one from a diary.
 */
class WitnessPeopleTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld twoInTheGarden(int watcherSensitivity) {
        CompanionWorld w = CompanionRules.join("witness", "住客", "Asia/Shanghai", now, false);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = now;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(7200));
        }
        ResidentSimulation.state(w, "owner").sensitivity = watcherSensitivity;
        ResidentSimulation.state(w, "gardener").positionId = "garden-plot";
        ResidentSimulation.replaceActor(w, "gardener", "garden", "make", "手上的活", now.plusSeconds(7200));
        // join() already ran a tick's worth of the world, so the cafe crowd has been noticed once
        // before this test has said anything. Start the watching from a clean slate.
        w.memories.removeIf(m -> "who-was-here".equals(m.topicId()));
        for (ResidentState r : w.residentStates) { r.lastSeenOfOthers.clear(); r.lastWitnessOfOthersAt.clear(); }
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    /** Only what {@code owner} wrote down about {@code about} - the rest of the town is in the cafe
     * and is being noticed too, which is correct and not what any of these tests is asking. */
    private List<Memory> sightings(CompanionWorld w, String owner, String about) {
        return w.memories.stream()
                .filter(m -> m.ownerId().equals(owner) && "who-was-here".equals(m.topicId())
                        && m.sourceId().equals(about))
                .toList();
    }

    @Test
    @DisplayName("同一个屋子里的另一个人，会被记成一条事实：谁、在哪、在做什么")
    void writesDownWhoWasHereAndWhatTheyWereDoing() {
        CompanionWorld w = twoInTheGarden(80);
        CompanionRules.advance(w, now.plusSeconds(12));
        assertThat(sightings(w, "owner", "gardener")).singleElement().satisfies(m -> {
            assertThat(m.sourceId()).isEqualTo("gardener");
            assertThat(m.sourceType()).isEqualTo("observed");
            assertThat(m.text()).contains("青叔").contains("苗圃");
        });
    }

    @Test
    @DisplayName("不留意细节的人，什么也没记下——事情发生过，但对他没留下痕迹")
    void someoneWhoDoesNotNoticeWritesNothing() {
        CompanionWorld w = twoInTheGarden(20);
        CompanionRules.advance(w, now.plusSeconds(12));
        assertThat(sightings(w, "owner", "gardener")).isEmpty();
    }

    @Test
    @DisplayName("对方还是那样坐着，就不会一遍遍记")
    void doesNotWriteTheSameSightingTwice() {
        CompanionWorld w = twoInTheGarden(80);
        for (int i = 1; i <= 40; i++) CompanionRules.advance(w, now.plusSeconds(i * 60L));
        assertThat(sightings(w, "owner", "gardener")).hasSize(1);
    }

    @Test
    @DisplayName("他走了又回来，坐回同一个地方——这一次要重新记下，因为这才是「又」")
    void recordsHimAgainWhenHeComesBackToTheSameSeat() {
        CompanionWorld w = twoInTheGarden(80);
        CompanionRules.advance(w, now.plusSeconds(12));
        assertThat(sightings(w, "owner", "gardener")).hasSize(1);

        // Away for a while, then back to the same plot doing the same thing.
        ResidentSimulation.replaceActor(w, "gardener", "cafe", "rest", "去坐坐", now.plusSeconds(3600));
        CompanionRules.advance(w, now.plusSeconds(1800));
        ResidentSimulation.replaceActor(w, "gardener", "garden", "make", "手上的活", now.plusSeconds(9000));
        CompanionRules.advance(w, now.plusSeconds(4000));

        assertThat(sightings(w, "owner", "gardener")).hasSize(2);
        assertThat(sightings(w, "owner", "gardener")).allSatisfy(m -> assertThat(m.text()).contains("苗圃"));
    }

    @Test
    @DisplayName("四十五分钟之内不会再记同一个人，哪怕他换了个姿势")
    void keepsAFloorBetweenTwoSightingsOfTheSamePerson() {
        CompanionWorld w = twoInTheGarden(80);
        CompanionRules.advance(w, now.plusSeconds(12));
        ResidentSimulation.replaceActor(w, "gardener", "garden", "read", "看会儿书", now.plusSeconds(7200));
        CompanionRules.advance(w, now.plusSeconds(600));
        assertThat(sightings(w, "owner", "gardener")).hasSize(1);
    }

    @Test
    @DisplayName("记的是事实，不是对方心里的理由——label 是他自己的，不跟着走")
    void neverCopiesTheOtherPersonsOwnReasonForBeingThere() {
        CompanionWorld w = twoInTheGarden(80);
        ResidentSimulation.replaceActor(w, "gardener", "garden", "make",
                "想趁天没热把新芽移完，昨天答应过知夏要留一株给她", now.plusSeconds(7200));
        CompanionRules.advance(w, now.plusSeconds(12));
        assertThat(sightings(w, "owner", "gardener")).singleElement().satisfies(m -> {
            assertThat(m.text()).doesNotContain("答应").doesNotContain("知夏").doesNotContain("昨天");
        });
    }

    @Test
    @DisplayName("睡着的人和路上走着的人不算「在这儿」")
    void doesNotWitnessSomeoneAsleepOrOnTheirWay() {
        CompanionWorld w = twoInTheGarden(80);
        // A sleeping resident has a sleep plan running; without one the simulation quite correctly
        // tidies the bare activity back to idle, and the test would be asserting against a state
        // nobody is ever actually in.
        ResidentState sleeper = ResidentSimulation.state(w, "gardener");
        sleeper.plan = new Plan("p-test-sleep", "sleep", "garden", null, "睡了", now, now.plusSeconds(7200));
        ResidentSimulation.replaceActor(w, "gardener", "garden", "sleep", "睡了", now.plusSeconds(7200));
        CompanionRules.advance(w, now.plusSeconds(12));
        assertThat(sightings(w, "owner", "gardener")).isEmpty();

        ResidentSimulation.replaceActor(w, "gardener", "garden", "walk", "路过", now.plusSeconds(7200));
        CompanionRules.advance(w, now.plusSeconds(60));
        assertThat(sightings(w, "owner", "gardener")).isEmpty();
    }
}
