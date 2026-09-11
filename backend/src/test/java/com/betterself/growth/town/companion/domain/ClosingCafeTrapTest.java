package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A measured day where the town lost its cafe at half past eight in the morning and never got it
 * back. The chain, in order, because every link is its own bug:
 *
 * <p>1. The operator opened the shop half an hour early. 2. scheduleCue told him, on the very next
 * tick, that it was past the usual closing time - because "outside the usual hours" was one
 * condition rather than two, and before-opening got the after-closing sentence. 3. He closed it,
 * fifty seconds after opening it, for a perfectly sound reason given what he was told. 4. Every
 * habit in town that goes to the cafe is gated on it being open, so five of six residents stayed
 * home for the rest of the day. 5. He himself, standing inside his own emptying shop, chose to sit
 * down 476 times and was refused 408 of them, because "rest, here" named the cafe as its place. 6.
 * A refused decision leaves a resident with nothing decided, which is the very condition for asking
 * again - so he took 67% of the whole town's thinking, in a loop, for a simulated day.
 */
class ClosingCafeTrapTest {
    private static final Instant BEFORE_OPENING = Instant.parse("2026-01-01T00:31:00Z"); // 08:31 Shanghai
    private static final Instant DURING_HOURS = Instant.parse("2026-01-01T04:00:00Z");   // 12:00 Shanghai

    private CompanionWorld world(Instant at) {
        CompanionWorld w = CompanionRules.join("closing-trap", "住客", "Asia/Shanghai", at);
        w.conversations.forEach(c -> c.status = "ended");
        return w;
    }

    @Test void openingEarlyIsNeverReportedAsBeingPastClosingTime() {
        CompanionWorld w = world(BEFORE_OPENING);
        w.cafeStatus = "open"; w.cafeOperating = true;
        String cue = ResidentSimulation.cafeScheduleCue(w, "owner", BEFORE_OPENING);
        assertThat(cue).as("08:31, with the shop opening at 09:00").isNotNull().doesNotContain("打烊");
        // The genuine after-hours case still reads the way it always did.
        Instant afterClosing = Instant.parse("2026-01-01T14:00:00Z"); // 22:00 Shanghai
        assertThat(ResidentSimulation.cafeScheduleCue(w, "owner", afterClosing)).contains("打烊");
    }

    @Test void somebodyStandingInAClosingCafeCanStillSitDownThere() {
        CompanionWorld w = world(DURING_HOURS);
        w.cafeStatus = "closing"; w.cafeOperating = true;
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = null; owner.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在店里", DURING_HOURS);

        assertThat(ResidentSimulation.applyDecision(w, "owner", owner.revision, w.intentRevision,
            "cafe", "rest", null, "店里正在打烊，先坐一会儿等人走", null, List.of(), DURING_HOURS))
            .as("waiting for the room to empty is the most ordinary thing there is").isTrue();
        assertThat(owner.plan).isNotNull();
        assertThat(owner.plan.action()).isEqualTo("rest");
    }

    @Test void aClosingCafeIsStillNotAPlaceToStartHalfAnHoursWork() {
        CompanionWorld w = world(DURING_HOURS);
        w.cafeStatus = "closing"; w.cafeOperating = true;
        ResidentState student = ResidentSimulation.state(w, "student");
        student.plan = null; student.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "student", "cafe", "idle", "在店里", DURING_HOURS);
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "cafe", "study", null, "接着看书", null, List.of(), DURING_HOURS)).isFalse();
    }

    @Test void aDecisionThatCanNeverApplyStopsBeingAskedInsteadOfLoopingForever() {
        CompanionWorld w = world(DURING_HOURS);
        ResidentState r = ResidentSimulation.state(w, "owner");
        Instant t = DURING_HOURS;
        for (int i = 1; i <= 3; i++) ResidentSimulation.recordDecisionOutcome(r, false, t);
        assertThat(r.decisionRetryAfter).as("three refusals in a row is enough to stop asking").isNotNull();
        assertThat(r.decisionRetryAfter).isAfter(t);
        // It grows, and it is bounded.
        for (int i = 0; i < 40; i++) ResidentSimulation.recordDecisionOutcome(r, false, t);
        assertThat(r.decisionRetryAfter).isBeforeOrEqualTo(t.plusSeconds(15 * 60));
        // Anything that lands clears it completely.
        ResidentSimulation.recordDecisionOutcome(r, true, t);
        assertThat(r.decisionRetryAfter).isNull();
        assertThat(r.consecutiveDecisionRejections).isZero();
    }

    @Test void aShopWithPostedHoursOpensAtItsOwnOpeningTimeWithoutAnybodyDecidingTo() {
        // The whole town's social life used to hang on one successful model call a day: opening was a
        // model decision and nothing else, so a day without a model had five of six residents at home
        // from beginning to end, and one wrong call cost the same.
        CompanionWorld w = world(BEFORE_OPENING);
        w.cafeStatus = "closed"; w.cafeOperating = true; w.cafeStatusChangedAt = BEFORE_OPENING.minusSeconds(86_400);
        // At his own counter, which is the part this test originally left out: it asserted that a shop
        // opens itself with nobody in it, and that is exactly the bug that let the door unlock while
        // its owner was asleep at home. What is removed by scheduled opening is the busywork of
        // re-deciding to open every morning - not the need for the person with the key to be there.
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "在店里", DURING_HOURS.plusSeconds(3_600));
        CompanionRules.advance(w, BEFORE_OPENING.plusSeconds(60));
        assertThat(w.cafeStatus).as("08:32, still before the usual 09:00").isEqualTo("closed");
        CompanionRules.advance(w, DURING_HOURS);
        assertThat(w.cafeStatus).as("noon, he is at the counter, and he still runs the place").isEqualTo("open");
    }

    /** The door opens when the person with the key reaches it. The first version of scheduled
     * opening checked the clock and the standing commitment and nothing else, so the shop unlocked
     * itself at nine while its owner was asleep in bed at home - and wrote a world event saying he
     * had opened it, which was simply untrue. */
    @Test void aShopDoesNotUnlockItselfWhileItsOwnerIsAsleepInBed() {
        CompanionWorld w = world(BEFORE_OPENING);
        w.cafeStatus = "closed"; w.cafeOperating = true; w.cafeStatusChangedAt = BEFORE_OPENING.minusSeconds(86_400);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = new CompanionWorld.Plan("sleeping", "sleep", TownPlaces.homeOf("owner"), null, "还在睡", BEFORE_OPENING, BEFORE_OPENING.plusSeconds(7_200));
        ResidentSimulation.replaceActor(w, "owner", TownPlaces.homeOf("owner"), "sleep", "睡着了", BEFORE_OPENING.plusSeconds(7_200));

        CompanionRules.advance(w, Instant.parse("2026-01-01T01:05:00Z")); // 09:05, past opening
        assertThat(w.cafeStatus).as("nobody is there to unlock it").isEqualTo("closed");
        assertThat(w.events).as("and nothing claims he did").noneMatch(e -> "cafe_opened".equals(e.type()));
    }

    @Test void theOwnerStandingInHisOwnShopAtOpeningTimeOpensIt() {
        CompanionWorld w = world(BEFORE_OPENING);
        w.cafeStatus = "closed"; w.cafeOperating = true; w.cafeStatusChangedAt = BEFORE_OPENING.minusSeconds(86_400);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "在店里", BEFORE_OPENING.plusSeconds(7_200));
        CompanionRules.advance(w, Instant.parse("2026-01-01T01:05:00Z"));
        assertThat(w.cafeStatus).isEqualTo("open");
    }

    /** Opening your own shop is not a habit you may or may not feel like today - it is what you
     * agreed to when you took the place on. Left in the habit layer it inherited a five-hour cooldown
     * and a one-in-five roll per minute, and it showed: measured, the operator woke at seven, sat at
     * home with nothing decided from 08:29, and did not set off until 10:04 - an hour past the time
     * his own door was meant to be unlocked. */
    @Test void theOperatorSetsOffInTimeToUnlockHisOwnDoorAtOpeningTime() {
        CompanionWorld w = world(BEFORE_OPENING);
        w.cafeStatus = "closed"; w.cafeOperating = true; w.cafeStatusChangedAt = BEFORE_OPENING.minusSeconds(86_400);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = null; owner.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "owner", TownPlaces.homeOf("owner"), "idle", "在家里", BEFORE_OPENING);

        Instant t = BEFORE_OPENING; // 08:31, half an hour before opening
        Instant openedAt = null;
        for (int minute = 0; minute < 90 && openedAt == null; minute++) {
            t = t.plusSeconds(60);
            CompanionRules.advance(w, t);
            if ("open".equals(w.cafeStatus)) openedAt = t;
        }
        assertThat(openedAt).as("the door gets unlocked at all").isNotNull();
        // Within a couple of minutes of nine, not an hour and a half after it.
        assertThat(openedAt).isBefore(Instant.parse("2026-01-01T01:05:00Z"));
        assertThat(ResidentSimulation.actor(w, "owner").place()).isEqualTo("cafe");
    }

    @Test void nobodyElseIsSentToOpenAShopTheyDoNotRun() {
        CompanionWorld w = world(BEFORE_OPENING);
        w.cafeStatus = "closed"; w.cafeOperating = true; w.cafeStatusChangedAt = BEFORE_OPENING.minusSeconds(86_400);
        for (ResidentState r : w.residentStates) { r.plan = null; r.suspendedAction = null; }
        ResidentSimulation.replaceActor(w, "student", TownPlaces.homeOf("student"), "idle", "在家里", BEFORE_OPENING);
        Instant t = BEFORE_OPENING;
        for (int minute = 0; minute < 40; minute++) { t = t.plusSeconds(60); CompanionRules.advance(w, t); }
        assertThat(ResidentSimulation.state(w, "student").lastHabitAt)
            .as("it is the operator's own obligation, not everybody's").doesNotContainKey("open_up");
    }

    @Test void aShopClosedForTheDayStaysClosedForTheDay() {
        CompanionWorld w = world(DURING_HOURS);
        w.cafeOperating = true;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "在店里", DURING_HOURS);
        w.cafeStatus = "open";
        assertThat(CafeService.closeForDay(w, "owner", "今天先到这儿吧", DURING_HOURS)).isTrue();
        w.cafeStatus = "closed"; w.cafeStatusChangedAt = DURING_HOURS; // the room has emptied
        CompanionRules.advance(w, DURING_HOURS.plusSeconds(3_600));
        assertThat(w.cafeStatus).as("closing early is a decision about today, and it stands").isEqualTo("closed");
    }

    @Test void apausedShopIsNeverReopenedBySchedule() {
        CompanionWorld w = world(DURING_HOURS);
        w.cafeStatus = "closed"; w.cafeOperating = false; w.cafeStatusChangedAt = DURING_HOURS.minusSeconds(86_400);
        CompanionRules.advance(w, DURING_HOURS.plusSeconds(60));
        assertThat(w.cafeStatus).as("not running a shop is a standing choice, not a daily one").isEqualTo("closed");
    }

    @Test void oneOrTwoRefusalsAreJustACollisionAndCostNothing() {
        CompanionWorld w = world(DURING_HOURS);
        ResidentState r = ResidentSimulation.state(w, "owner");
        ResidentSimulation.recordDecisionOutcome(r, false, DURING_HOURS);
        ResidentSimulation.recordDecisionOutcome(r, false, DURING_HOURS);
        assertThat(r.decisionRetryAfter).as("a stale decision now and then is normal").isNull();
    }
}
