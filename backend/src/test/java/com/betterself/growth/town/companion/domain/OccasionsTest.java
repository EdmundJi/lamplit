package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mechanism {@link OccasionMenuExclusionTest} states the rule for: what actually happens when a
 * moment comes round, and - the part that matters more - how often it comes round at all.
 */
class OccasionsTest {
    private final Instant start = Instant.parse("2026-09-08T00:00:00Z");

    private CompanionWorld world(String id) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", start, true);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, start, "测试准备"));
        return w;
    }
    private static void park(CompanionWorld w, String except, Instant at) {
        for (ResidentState r : w.residentStates) {
            if (r.id.equals(except)) continue;
            ResidentSimulation.replaceActor(w, r.id, TownPlaces.homeOf(r.id), "sleep", "睡着", at.plusSeconds(7200));
            r.plan = new CompanionWorld.Plan("park-" + r.id, "sleep", TownPlaces.homeOf(r.id), null, "睡着", at, at.plusSeconds(7200));
        }
    }
    private static List<String> keysOf(CompanionWorld w) {
        return w.pendingOccasions.stream().map(p -> p.key).toList();
    }

    // ---- the moment the door question belongs to -----------------------------------------------

    @Test
    @DisplayName("最后一个人、店里没别人、手上的事要完了——锁门这一问才问得出口")
    void theDoorIsAskedAboutOnlyWhenSomebodyIsTheLastOneLeaving() {
        CompanionWorld w = world("occasion-door");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        owner.plan = null;

        Occasions.scan(w, start);
        assertThat(keysOf(w)).as("这才是那 7 次里的一次").contains("lock_door");
    }

    @Test
    @DisplayName("店里还坐着别人，就不是锁门的时候——这一条就是 470 次里的 463 次")
    void nobodyIsAskedAboutTheDoorWhileSomebodyElseIsStillInTheShop() {
        CompanionWorld w = world("occasion-door-busy");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        ResidentSimulation.state(w, "owner").plan = null;
        // One other person, awake, at a table. That is the whole difference.
        ResidentSimulation.replaceActor(w, "student", "cafe", "study", "在窗边复习", start.plusSeconds(3600));

        Occasions.scan(w, start);
        assertThat(keysOf(w)).as("有人在，门就不是一个问题").doesNotContain("lock_door");
    }

    @Test
    @DisplayName("坐下来还要待两个钟头的人，不是站在门口的人")
    void somebodySettledInForHoursIsNotAskedAboutTheDoor() {
        CompanionWorld w = world("occasion-door-settled");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "work", "在吧台后面做事", start.plusSeconds(7200));
        owner.plan = new CompanionWorld.Plan("p-long", "work", "cafe", null, "还有的忙", start, start.plusSeconds(7200));

        Occasions.scan(w, start);
        assertThat(keysOf(w)).doesNotContain("lock_door");
    }

    // ---- the same moment is put once, and only once ---------------------------------------------

    @Test
    @DisplayName("没人回答的问题也算问过了——否则它每过一个 TTL 就回来一次")
    void anUnansweredOccasionIsNotRaisedAgainForTheSameScene() {
        // The bug this pins is not hypothetical and was not subtle in its effect: recording "already
        // asked" only when the resident refused meant an unanswered question came straight back the
        // moment its TTL expired. A two-day probe measured 407 asks for change_work - an occasion that
        // by construction comes round once a day - which is the menu's own failure rebuilt inside the
        // mechanism that exists to fix it.
        CompanionWorld w = world("occasion-once");
        Map<String, Integer> raised = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Instant at = start;
        for (int tick = 0; tick < 2880; tick++) { // two simulated days, one-minute steps
            at = at.plusSeconds(60);
            ResidentSimulation.advance(w, at);
            for (CompanionWorld.PendingOccasion p : w.pendingOccasions)
                if (seen.add(p.id)) raised.merge(p.key, 1, Integer::sum);
            assertThat(w.pendingOccasions.size()).as("没人回答时也不许堆积").isLessThanOrEqualTo(12);
        }
        int residents = w.residentStates.size();
        for (Occasions.Definition d : Occasions.ALL) {
            if (!"occasion".equals(d.askedBy())) continue;
            // Three calendar days are touched by a 2880-minute run that starts at midnight UTC in a
            // +08:00 town, and each occasion's own fingerprint is scoped to one of them.
            assertThat(raised.getOrDefault(d.key(), 0))
                .as("%s 两天里最多每人每天问一次，实际问了 %s 次", d.key(), raised.getOrDefault(d.key(), 0))
                .isLessThanOrEqualTo(3 * residents);
        }
        assertThat(raised.values().stream().mapToInt(Integer::intValue).sum())
            .as("而且不能一次都不问——那只是把 470:0 换成 0:0").isPositive();
    }

    // ---- answering ------------------------------------------------------------------------------

    @Test
    @DisplayName("说不锁就什么都不发生，而且不需要理由")
    void refusingIsFreeAndChangesNothing() {
        CompanionWorld w = world("occasion-refuse");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        owner.plan = null;
        Occasions.scan(w, start);
        var pending = w.pendingOccasions.stream().filter(p -> p.key.equals("lock_door")).findFirst().orElseThrow();

        assertThat(Occasions.apply(w, pending.id, owner.revision, "none", null, null, List.of(), start))
            .as("空理由也照收：不做不需要解释").isTrue();
        assertThat(DoorService.isLocked(w)).isFalse();
        assertThat(w.pendingOccasions).isEmpty();
    }

    @Test
    @DisplayName("说锁就真锁上了，而且走的是普通决定同一条路")
    void acceptingGoesThroughTheOrdinaryActionPath() {
        CompanionWorld w = world("occasion-accept");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        owner.plan = null;
        Occasions.scan(w, start);
        var pending = w.pendingOccasions.stream().filter(p -> p.key.equals("lock_door")).findFirst().orElseThrow();

        assertThat(Occasions.apply(w, pending.id, owner.revision, "lock_door", "锁上再走", null, List.of(), start)).isTrue();
        assertThat(DoorService.isLocked(w)).isTrue();
        assertThat(DoorService.cafeDoor(w).lockedBy).isEqualTo("owner");
    }

    @Test
    @DisplayName("答案回来时情形已经变了，就当没问过")
    void anAnswerThatArrivesTooLateDoesNothing() {
        CompanionWorld w = world("occasion-stale");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        owner.plan = null;
        Occasions.scan(w, start);
        var pending = w.pendingOccasions.stream().filter(p -> p.key.equals("lock_door")).findFirst().orElseThrow();
        // Somebody walked back in while the question was out.
        ResidentSimulation.replaceActor(w, "student", "cafe", "study", "又回来复习", start.plusSeconds(3600));

        assertThat(Occasions.apply(w, pending.id, owner.revision, "lock_door", "锁上再走", null, List.of(), start.plusSeconds(60)))
            .isFalse();
        assertThat(DoorService.isLocked(w)).isFalse();
    }

    @Test
    @DisplayName("只有两个答案：这件事本身，或者不做")
    void nothingElseCanBeAnswered() {
        CompanionWorld w = world("occasion-enum");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        owner.plan = null;
        Occasions.scan(w, start);
        var pending = w.pendingOccasions.stream().filter(p -> p.key.equals("lock_door")).findFirst().orElseThrow();

        assertThat(Occasions.apply(w, pending.id, owner.revision, "sleep", "困了", null, List.of(), start))
            .as("时机问句不是又一个菜单").isFalse();
        assertThat(w.pendingOccasions).as("而且问题还在，没被这个乱答案吃掉").isNotEmpty();
    }

    // ---- a moment that has passed ----------------------------------------------------------------

    @Test
    @DisplayName("过了时限没人答，这个时刻就过去了，规则不会替他锁门")
    void anExpiredOccasionJustPassesAndNobodyActsOnTheResidentsBehalf() {
        CompanionWorld w = world("occasion-expire");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        park(w, "owner", start);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60));
        owner.plan = null;
        Occasions.scan(w, start);
        assertThat(keysOf(w)).contains("lock_door");

        Occasions.expire(w, start.plusSeconds(3600));
        assertThat(w.pendingOccasions).isEmpty();
        // The deliberate asymmetry with pendingEncounters, which greets on the resident's behalf when
        // no model answers: silence here has to mean "not locked", never "locked by default".
        assertThat(DoorService.isLocked(w)).isFalse();
    }
}
