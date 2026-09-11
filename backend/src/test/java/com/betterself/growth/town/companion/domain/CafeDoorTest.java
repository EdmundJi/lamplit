package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The town's first stateful physical object with real consequences (docs/06-society.md "物件要有自己
 * 的类"): the cafe's door. Proves the six things the batch called out as load-bearing - locking is a
 * real, both-directions-open decision by whoever is physically there (never a clock); a locked door
 * genuinely stops an arrival; the person turned away gets one fact and nothing resembling an
 * instruction; who locked it is known only to whoever was actually standing there at the time; the
 * person who locked it is never shut out by their own lock; and a resident turned away is left free
 * to decide again, never stuck retrying the same walk.
 */
class CafeDoorTest {
    private final Instant start = Instant.parse("2026-09-08T10:00:00Z");

    private static CompanionWorld world(String id, Instant at) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", at);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, at, "测试准备"));
        return w;
    }

    // ---- who can lock, and only as a real decision ---------------------------------------------

    @Test void aResidentActuallyStandingInTheCafeMayLockItsDoorAndTheDoorRemembersWhoAndWhen() {
        CompanionWorld w = world("door-lock", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60)); owner.plan = null;

        // Deliberately NOT in availableActions - see Occasions and OccasionMenuExclusionTest. Locking
        // up is asked at the one moment it means anything, not offered on every decision taken inside
        // the cafe; that version measured 470 offers and 0 takers against 7 real occasions in two
        // days. What is pinned here is what the action does once somebody has chosen it.
        assertThat(ResidentSimulation.availableActions(w, "owner", start)).doesNotContain("lock_door");
        assertThat(ResidentSimulation.applyDecision(w, "owner", owner.revision, w.intentRevision,
            "cafe", "lock_door", null, "锁上门再走", null, List.of(), start)).isTrue();

        assertThat(DoorService.isLocked(w)).isTrue();
        CompanionWorld.Door door = DoorService.cafeDoor(w);
        assertThat(door.lockedBy).isEqualTo("owner");
        assertThat(door.lockedAt).isEqualTo(start);
        // Already locked is not a fresh choice: re-locking applies nothing.
        assertThat(DoorService.lock(w, owner, start.plusSeconds(1))).isFalse();
    }

    @Test void notTheOperatorAloneAnybodyActuallyPresentMayLockItButNobodyElsewhereCan() {
        CompanionWorld w = world("door-lock-anybody", start);
        ResidentState artist = ResidentSimulation.state(w, "artist"); // not the operator
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "在看画", start.plusSeconds(60)); artist.plan = null;
        assertThat(DoorService.lock(w, artist, start)).isTrue();
        assertThat(DoorService.cafeDoor(w).lockedBy).isEqualTo("artist");
    }

    @Test void nobodyAwayFromTheCafeCanLockItsDoor() {
        CompanionWorld w = world("door-lock-elsewhere", start);
        ResidentState artist = ResidentSimulation.state(w, "artist");
        ResidentSimulation.replaceActor(w, "artist", "garden", "observe", "在花园里", start.plusSeconds(60)); artist.plan = null;
        assertThat(ResidentSimulation.availableActions(w, "artist", start)).doesNotContain("lock_door");
        assertThat(DoorService.lock(w, artist, start)).isFalse();
        assertThat(DoorService.isLocked(w)).isFalse();
    }

    // ---- the consequence: an arrival is genuinely blocked, and told only the fact ----------------

    @Test void anArrivingResidentCannotReachALockedCafeAndLearnsOnlyTheFactNeverAnInstruction() {
        CompanionWorld w = world("door-blocked", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentState student = ResidentSimulation.state(w, "student");
        // Away from the cafe before the door is even locked, so this scenario's whole point - a
        // resident who was not there - actually holds.
        ResidentSimulation.replaceActor(w, "student", "street", "walk", "正往咖啡馆走", start.plusSeconds(60)); student.plan = null;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60)); owner.plan = null;
        assertThat(DoorService.lock(w, owner, start)).isTrue();

        // Straight into schedule() - the exact hand-off point a completed travel plan uses (see
        // ResidentSimulation.complete's "travel" branch) - with the door locked and student not the
        // one who locked it.
        ResidentSimulation.schedule(w, student, "study", "cafe", null, "去看会儿书", start.plusSeconds(1), 1800);

        assertThat(student.plan).as("a clean failure, not a plan stuck pointed at the cafe").isNull();
        assertThat(ResidentSimulation.actor(w, "student").place()).isNotEqualTo("cafe");
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "cafe-door".equals(m.topicId())))
            .as("the one fact")
            .anySatisfy(m -> assertThat(m.text()).isEqualTo("咖啡馆的门锁着，我进不去。"));
        // Never an instruction - docs/06-society.md's "后果只给感知，不给指令" - and never who did it either.
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "cafe-door".equals(m.topicId())))
            .noneMatch(m -> m.text().contains("敲门") || m.text().contains("别的地方") || m.text().contains("应该") || m.text().contains("阿禾"));
    }

    /** End-to-end through the real travel machinery: a resident who chose to go to the cafe while it
     * is locked ends up turned away, then genuinely free to be asked what they want next - not looping
     * on the same walk. */
    @Test void endToEndAResidentTurnedAwayAtALockedDoorIsFreeToDecideAgainNotStuckRetrying() {
        CompanionWorld w = world("door-blocked-e2e", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(6000)); owner.plan = null;
        assertThat(DoorService.lock(w, owner, start)).isTrue();
        // The owner then steps away; the lock is a fact about the door, not about who is inside now.
        ResidentSimulation.replaceActor(w, "owner", "garden", "observe", "去花园看看", start.plusSeconds(6000));
        TownPlaces.release(w, "owner"); TownPlaces.claim(w, "owner", "garden", null, start);

        ResidentState student = ResidentSimulation.state(w, "student");
        String home = TownPlaces.homeOf("student");
        ResidentSimulation.replaceActor(w, "student", home, "idle", "在家里", start.plusSeconds(6000)); student.plan = null;
        TownPlaces.release(w, "student"); TownPlaces.claim(w, "student", home, null, start);

        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "cafe", "study", null, "去咖啡馆看会儿书", null, List.of(), start)).isTrue();
        assertThat(student.plan.action()).isEqualTo("travel");

        Instant at = start;
        for (int i = 0; i < 150 && student.plan != null && "travel".equals(student.plan.action()); i++) {
            at = at.plusSeconds(6); CompanionRules.advance(w, at);
        }

        assertThat(ResidentSimulation.actor(w, "student").place()).as("never actually got inside").isNotEqualTo("cafe");
        assertThat(student.plan).as("cleanly free, not parked mid-plan on the cafe").isNull();

        // Genuinely a fresh decision, not an automatic re-attempt of the same walk: nothing in the
        // rules chose "cafe" again on the student's behalf, and a real different decision applies
        // cleanly right away.
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "garden", "observe", null, "那就去花园看看好了", null, List.of(), at.plusSeconds(1))).isTrue();
        assertThat(student.plan.place()).isEqualTo("garden");
    }

    // ---- the asymmetry: only whoever was actually there learns who ------------------------------

    @Test void lockingIsWitnessedOnlyByWhoIsActuallyThereAndNamesNobodyToAnyoneElse() {
        CompanionWorld w = world("door-witness", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentState artist = ResidentSimulation.state(w, "artist");   // present - a real witness
        ResidentState gardener = ResidentSimulation.state(w, "gardener"); // elsewhere - not a witness
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(6000)); owner.plan = null;
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "在看墙上的画", start.plusSeconds(6000)); artist.plan = null;
        ResidentSimulation.replaceActor(w, "gardener", "garden", "observe", "在花园里", start.plusSeconds(6000)); gardener.plan = null;

        assertThat(DoorService.lock(w, owner, start)).isTrue();

        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("artist") && "cafe-door".equals(m.topicId())))
            .as("present witness sees who")
            .anySatisfy(m -> assertThat(m.text()).contains("阿禾").contains("锁"));
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("gardener")))
            .as("absent resident gets nothing about it at all - not even that it happened")
            .noneMatch(m -> "cafe-door".equals(m.topicId()));
    }

    // ---- self-bypass: the locker is never shut out by their own lock ----------------------------

    @Test void theResidentWhoLockedTheDoorCanStillWalkBackInThemselves() {
        CompanionWorld w = world("door-self-open", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(6000)); owner.plan = null;
        assertThat(DoorService.lock(w, owner, start)).isTrue();

        ResidentSimulation.replaceActor(w, "owner", "street", "walk", "出去转一圈", start.plusSeconds(60));
        TownPlaces.release(w, "owner"); owner.plan = null;

        ResidentSimulation.schedule(w, owner, "observe", "cafe", null, "回来看看", start.plusSeconds(70), 300);

        assertThat(owner.plan).as("their own lock never turns them away").isNotNull();
        assertThat(ResidentSimulation.actor(w, "owner").place()).isEqualTo("cafe");
    }
}
