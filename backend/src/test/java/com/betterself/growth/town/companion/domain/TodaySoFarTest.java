package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "今天到现在，我做了什么" - the half of the 32.1% that was never a gating problem.
 *
 * <p>A six-hour run took 243 decisions and 78 of them (32.1%) repeated that resident's own previous
 * decision word for word. The reflex layer stops us <i>asking</i> into an unchanged room; this is
 * the other half: when a resident IS asked, they could not see their own afternoon.
 * 「刚搬来，先在家里歇会儿，整理一下心情和住处。」 came back verbatim dozens of times, and a person who
 * had said that three times running would know they had.
 *
 * <p>Bystander's view only - what, where, how long. Never a motive: why they did it is theirs to say.
 */
class TodaySoFarTest {
    private final Instant start = Instant.parse("2026-09-08T03:00:00Z"); // 11:00 Asia/Shanghai

    private CompanionWorld world(String id) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", start, true);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, start, "测试准备"));
        for (ResidentState r : w.residentStates) { r.plan = null; r.suspendedAction = null; }
        return w;
    }
    private static void parkEveryoneExcept(CompanionWorld w, String who, Instant at) {
        for (ResidentState r : w.residentStates) {
            if (r.id.equals(who)) continue;
            ResidentSimulation.replaceActor(w, r.id, TownPlaces.homeOf(r.id), "sleep", "睡着", at.plusSeconds(36000));
            r.plan = new CompanionWorld.Plan("park-" + r.id, "sleep", TownPlaces.homeOf(r.id), null, "睡着", at, at.plusSeconds(36000));
        }
    }

    @Test
    @DisplayName("做过的每一段都记下来，同样的事合成一行带次数和分钟")
    void repeatedStretchesCollapseIntoOneLineWithACount() {
        CompanionWorld w = world("today-count");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");

        Instant at = start;
        for (int round = 0; round < 3; round++) {
            fixer.plan = null;
            assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
                "home", "read", null, "刚搬来，先在家里歇会儿", null, List.of(), at)).isTrue();
            for (int tick = 0; tick < 32; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }
        }

        var today = ResidentSimulation.todaySoFar(w, "fixer", at);
        var reading = today.stream().filter(d -> d.action().equals("read")).findFirst().orElseThrow();
        assertThat(reading.times()).as("他今天已经读过好几段了，而且他看得见这件事").isGreaterThanOrEqualTo(3);
        assertThat(reading.minutes()).as("而且看得见一共花了多久").isGreaterThanOrEqualTo(30);
        assertThat(reading.place()).as("家是「home」，不写成谁的家——地点是给他自己看的").isEqualTo("home");
    }

    @Test
    @DisplayName("走路和睡觉不算「今天做了什么」")
    void gettingThereAndBeingAsleepAreNotStretchesOfSomebodysDay() {
        CompanionWorld w = world("today-ignored");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        // Somewhere else entirely, so the trip itself is a real travel plan first.
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "cafe", "read", null, "去店里看会儿书", null, List.of(), start)).isTrue();
        assertThat(fixer.plan.action()).isEqualTo("travel");

        Instant at = start;
        for (int tick = 0; tick < 60; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }

        assertThat(ResidentSimulation.todaySoFar(w, "fixer", at))
            .as("走过去这件事不是他的下午").noneMatch(d -> d.action().equals("travel"))
            .as("睡着也不是").noneMatch(d -> d.action().equals("sleep"));
    }

    @Test
    @DisplayName("到了第二天，昨天的就不在了")
    void yesterdayIsNotPartOfToday() {
        CompanionWorld w = world("today-rollover");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "home", "read", null, "看会儿书", null, List.of(), start);
        Instant at = start;
        for (int tick = 0; tick < 32; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }
        assertThat(ResidentSimulation.todaySoFar(w, "fixer", at)).isNotEmpty();

        assertThat(ResidentSimulation.todaySoFar(w, "fixer", at.plus(java.time.Duration.ofDays(1))))
            .as("这一问是「今天到现在」，不是一本流水账").isEmpty();
    }

    @Test
    @DisplayName("记的是旁观者看得见的：做了什么、在哪、多久——没有为什么")
    void itRecordsWhatAnOnlookerWouldSeeAndNothingElse() {
        CompanionWorld w = world("today-no-motive");
        parkEveryoneExcept(w, "fixer", start);
        ResidentSimulation.replaceActor(w, "fixer", TownPlaces.homeOf("fixer"), "idle", "在家", start.plusSeconds(60));
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        String privateReason = "其实是不想碰那件没做完的活";
        ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "home", "read", null, privateReason, null, List.of(), start);
        Instant at = start;
        for (int tick = 0; tick < 32; tick++) { at = at.plusSeconds(60); ResidentSimulation.advance(w, at); }

        // The record carries the action, the place and the length - and the resident's own account of
        // why stays where it belongs, in their memories, in their words. Same red line as Deed.
        assertThat(ResidentSimulation.todaySoFar(w, "fixer", at).toString())
            .as("动机是他自己的事，不该被规则替他写进流水账").doesNotContain(privateReason);
    }
}
