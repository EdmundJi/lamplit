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

    @Test void oneOrTwoRefusalsAreJustACollisionAndCostNothing() {
        CompanionWorld w = world(DURING_HOURS);
        ResidentState r = ResidentSimulation.state(w, "owner");
        ResidentSimulation.recordDecisionOutcome(r, false, DURING_HOURS);
        ResidentSimulation.recordDecisionOutcome(r, false, DURING_HOURS);
        assertThat(r.decisionRetryAfter).as("a stale decision now and then is normal").isNull();
    }
}
