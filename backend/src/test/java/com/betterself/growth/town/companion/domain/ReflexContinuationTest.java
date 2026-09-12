package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bottom of the three layers a resident is answered from - reflex, then occasion, then the model
 * - and the one that had to be built after a six-hour run measured <b>78 of its 243 decisions
 * (32.1%) as byte-identical repeats of that same resident's previous decision</b>: the same action
 * with the same sentence, in the same unchanged room, ten minutes later.
 *
 * <p>What is pinned here is both halves of the rule. It carries on a decision the resident made
 * themselves, into a room that has not changed - and it refuses to in every case where carrying on
 * would mean the rules deciding something instead of repeating something.
 */
class ReflexContinuationTest {
    private final Instant start = Instant.parse("2026-09-08T03:00:00Z"); // 11:00 Asia/Shanghai

    private CompanionWorld world(String id) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", start, true);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, start, "测试准备"));
        for (ResidentState r : w.residentStates) { r.plan = null; r.suspendedAction = null; }
        return w;
    }
    /** Everybody else asleep at home, so "who else is here" cannot move under the test. */
    private static void parkEveryoneExcept(CompanionWorld w, String who, Instant at) {
        for (ResidentState r : w.residentStates) {
            if (r.id.equals(who)) continue;
            ResidentSimulation.replaceActor(w, r.id, TownPlaces.homeOf(r.id), "sleep", "睡着", at.plusSeconds(36000));
            r.plan = new CompanionWorld.Plan("park-" + r.id, "sleep", TownPlaces.homeOf(r.id), null, "睡着", at, at.plusSeconds(36000));
        }
    }
    /** One resident's own decision to sit in their own home and read, which is the shape the 78 had. */
    private static boolean decideToRead(CompanionWorld w, String who, Instant at) {
        ResidentState r = ResidentSimulation.state(w, who);
        return ResidentSimulation.applyDecision(w, who, r.revision, w.intentRevision,
            "home", "read", null, "刚搬来，先在家里歇会儿", null, List.of(), at);
    }
    private static long reflexTriggers(CompanionWorld w) {
        return w.decisionTriggers.stream().filter(t -> t.trigger().startsWith("reflex:")).count();
    }

    @Test
    @DisplayName("情形一点没变，本人自己选的事就接着做——不必再问一遍")
    void anUnchangedRoomCarriesTheResidentsOwnDecisionOn() {
        CompanionWorld w = world("reflex-carry");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        assertThat(decideToRead(w, "fixer", start)).isTrue();
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        String plan = fixer.plan.id();

        // Walk just past the end of that one plan (read runs 30 minutes) with nothing else in town
        // changing. Deliberately not further: being carried on is bounded, and the bound is what
        // beingCarriedOnIsBounded below is for.
        Instant at = start;
        for (int tick = 0; tick < 32; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }

        assertThat(fixer.plan).as("没被丢在原地等模型").isNotNull();
        assertThat(fixer.plan.action()).as("接着做的是他自己选的那件事").isEqualTo("read");
        assertThat(fixer.plan.id()).as("而且是新的一段，不是把旧计划的时钟往后挪").isNotEqualTo(plan);
        assertThat(reflexTriggers(w)).as("而且这件事记在账上，随时查得到").isPositive();
    }

    @Test
    @DisplayName("接下去的次数有上限——不能让一个人一直被顺下去，从此没人问过他")
    void beingCarriedOnIsBounded() {
        CompanionWorld w = world("reflex-bounded");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        decideToRead(w, "fixer", start);
        ResidentState fixer = ResidentSimulation.state(w, "fixer");

        Instant at = start;
        for (int tick = 0; tick < 600; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }

        assertThat(reflexTriggers(w)).as("顺下去是有上限的，过了就必须重新问人")
            .isLessThanOrEqualTo(3);
        assertThat(fixer.reflexExtensions).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("屋里多了个人，就是变了——得重新问")
    void companyChangingCountsAsTheSituationChanging() {
        CompanionWorld w = world("reflex-company");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", "cafe", "idle", "在店里", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "cafe", "read", null, "在店里看会儿书", null, List.of(), start)).isTrue();
        String before = ResidentSimulation.situationFingerprint(w, "fixer", start);

        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "走进来了", start.plusSeconds(3600));

        assertThat(ResidentSimulation.situationFingerprint(w, "fixer", start))
            .as("有人走进来，这就不是同一个情形了").isNotEqualTo(before);
    }

    @Test
    @DisplayName("规则自己安排的事不许被顺下去——那是规则在过日子")
    void aPlanTheRulesArrangedIsNeverCarriedOn() {
        CompanionWorld w = world("reflex-not-ours");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        // Exactly what a rules-arranged plan looks like: scheduled directly, never chosen by anybody.
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "read", "看书", start.plusSeconds(120));
        fixer.plan = new CompanionWorld.Plan("rule-plan", "read", TownPlaces.homeOf("fixer"), null, "规则安排的",
            start, start.plusSeconds(120));
        fixer.planFromDecision = false;

        Instant at = start;
        for (int tick = 0; tick < 10; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }

        assertThat(w.decisionTriggers.stream().filter(t -> t.trigger().startsWith("reflex:")
            && t.residentId().equals("fixer"))).as("没人选过它，就不该被接着做下去").isEmpty();
    }

    @Test
    @DisplayName("说了「没什么特别想做的」之后，不会被顺出一段休息来")
    void doingNothingIsNeverTurnedIntoSomething() {
        CompanionWorld w = world("reflex-none");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "home", "none", null, "没什么特别想做的", null, List.of(), start)).isTrue();

        Instant at = start;
        for (int tick = 0; tick < 60; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }

        assertThat(fixer.planFromDecision).isFalse();
        assertThat(w.decisionTriggers.stream().filter(t -> t.trigger().startsWith("reflex:")
            && t.residentId().equals("fixer"))).isEmpty();
    }
}
