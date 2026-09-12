package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CompanionRecallTest {
    private final Instant now = Instant.parse("2026-09-08T10:00:00Z");

    @Test void aResidentCannotRetrieveAnotherResidentsKnowledgeOrFutureEvents() {
        var own = memory("own", "student", now.minusSeconds(60), "我还没听说晚会", "daily");
        var secret = memory("private", "owner", now.minusSeconds(30), "读书晚会准备好了", "reading");
        var future = memory("future", "student", now.plusSeconds(60), "已经参加读书晚会", "reading");
        assertThat(CompanionRecall.retrieve(List.of(own, secret, future), "student", "读书晚会", now, 10))
            .containsExactly(own);
    }

    @Test void aRelevantOlderPromiseOutranksRecentUnrelatedRoutine() {
        var promise = memory("promise", "artist", now.minusSeconds(86400), "我答应为读书晚会画海报", "reading");
        var routine = memory("routine", "artist", now.minusSeconds(5), "喝了一杯温水", "");
        var input = List.of(routine, promise);
        assertThat(CompanionRecall.retrieve(input, "artist", "读书晚会海报", now, 1)).containsExactly(promise);
        assertThat(input).containsExactly(routine, promise);
    }

    // Scoring is now relevance-as-multiplier over a recency/importance/layer base (see
    // CompanionRecall's own doc comment), not the old relevance*3 + recency + importance sum. Under
    // that old sum, a memory with zero relevance could still win on sheer freshness and importance
    // alone: recency and importance are each capped at 1.0, so a perfectly fresh, maximally important
    // but totally irrelevant memory scored up to 2.0 even with relevance*3=0, while a real but
    // partial match on an older, less important memory could easily fall short of that. This test
    // fixes numbers where that is exactly what used to happen (verified by hand against the old
    // formula) and asserts the new ranking gets it right instead.
    @Test void aFreshButIrrelevantMemoryNeverOutranksAnOlderDirectlyRelevantOne() {
        var fresh = new Memory("fresh", "artist", "self", "observed", now, "sunny weather feels great today", "", List.of(), 10);
        var older = new Memory("older", "artist", "self", "observed", now.minusSeconds(3 * 3600), "I mentioned the reading circle idea", "", List.of(), 3);
        assertThat(CompanionRecall.retrieve(List.of(fresh, older), "artist", "reading night poster plan", now, 2))
            .containsExactly(older, fresh);
    }

    // Layering (see CompanionWorld.Memory's own doc comment): a belief is the most durable, most
    // preferred-on-recall layer, a reflection sits in the middle, and a raw observation is the most
    // disposable. With everything else held equal (same text, same age, same importance, no query to
    // otherwise distinguish them), only the layer should decide the order.
    @Test void higherLayersOutrankLowerOnesUnderOtherwiseIdenticalConditions() {
        Instant at = now.minusSeconds(60);
        var observed = new Memory("m-observed", "artist", "artist", "observed", at, "阿禾总是坐在窗边", "seat", List.of(), 6);
        var reflection = new Memory("m-reflection", "artist", "artist", "reflection", at, "阿禾总是坐在窗边", "seat", List.of(), 6);
        var belief = new Memory("m-belief", "artist", "artist", "belief", at, "阿禾总是坐在窗边", "seat", List.of(), 6);
        assertThat(CompanionRecall.retrieve(List.of(observed, reflection, belief), "artist", "", now, 3))
            .containsExactly(belief, reflection, observed);
    }

    // Supersession (see CompanionWorld.Memory and ResidentSimulation.applyReflection): a superseded
    // memory is never deleted - it must still be sitting right there in the list handed to
    // retrieve() - but it must never come back out of retrieve() once flagged.
    @Test void aSupersededMemoryIsExcludedFromRetrievalButRemainsInTheSourceList() {
        var old = new Memory("old-belief", "artist", "artist", "belief", now.minusSeconds(600),
            "以前觉得阿禾喜欢靠门的桌子", "seat", List.of(), 8, "artist:seat-preference:阿禾", true);
        var current = new Memory("new-belief", "artist", "artist", "belief", now.minusSeconds(10),
            "现在觉得阿禾喜欢窗边的桌子", "seat", List.of(), 8, "artist:seat-preference:阿禾", false);
        var input = List.of(old, current);
        assertThat(CompanionRecall.retrieve(input, "artist", "", now, 5)).containsExactly(current);
        assertThat(input).contains(old); // still on record - the evidence chain is never deleted
    }

    @Test void aResidentsOwnAccountOfThemselvesCannotFillTheWholeWindow() {
        // A decision files the reason the resident gave for it as a reflection, one per decision - so
        // it is both the most numerous thing in anybody's memory and always the freshest, and it sits
        // a layer above raw memory. Recency and layer agree with each other every time, and the window
        // filled up with the resident talking to themselves: 51% of everything retrieved across 243
        // real decisions, 66% in a later run, with 44%-58% of decisions receiving a byte-identical
        // memory set to that resident's previous one.
        var input = new java.util.ArrayList<Memory>();
        for (int i = 0; i < 12; i++) // twelve of his own accounts, all fresher than anything else
            input.add(new Memory("own-" + i, "student", "student", "reflection", now.minusSeconds(60L * (i + 1)), "先看完这页再说", null, List.of(), 7));
        for (int i = 0; i < 6; i++) // and six things that actually happened, older
            input.add(new Memory("seen-" + i, "student", "owner", "observed", now.minusSeconds(3600L * (i + 1)), "我看见阿禾在吧台后面忙", null, List.of(), 5));

        var recalled = CompanionRecall.retrieve(input, "student", "", now, 10);

        assertThat(recalled).hasSize(10);
        assertThat(recalled.stream().filter(m -> "reflection".equals(m.sourceType())).count())
            .as("自己给自己的说法不能占满整个记忆窗口").isLessThanOrEqualTo(5);
        assertThat(recalled.stream().filter(m -> "observed".equals(m.sourceType())).count())
            .as("真正发生过的事必须有位置").isGreaterThanOrEqualTo(5);
    }

    @Test void reservingRoomNeverReturnsLessThanItUsedTo() {
        // A resident whose whole recorded life is their own voice still gets their whole recorded
        // life: the reservation holds a seat open, it never leaves one empty.
        var input = new java.util.ArrayList<Memory>();
        for (int i = 0; i < 8; i++)
            input.add(new Memory("own-" + i, "student", "student", "reflection", now.minusSeconds(60L * (i + 1)), "先看完这页再说", null, List.of(), 7));

        assertThat(CompanionRecall.retrieve(input, "student", "", now, 10))
            .as("没有别的可说时，就把他自己的话还给他，而不是还一个更短的列表").hasSize(8);
    }

    @Test void aStandingBeliefIsNeverTreatedAsJustAnotherThingTheySaid() {
        // Beliefs are the rare, hard-won layer this whole memory model exists for - they are a
        // resident's own conclusion about themselves, and capping them alongside passing
        // rationalisations would quietly bury the one thing the town is being measured for.
        var belief = new Memory("belief-1", "student", "student", "belief", now.minusSeconds(86400),
            "每次光线正好，我就自动往窗边坐", null, List.of(), 8, "habit:student:study_cafe", false);
        var input = new java.util.ArrayList<Memory>();
        input.add(belief);
        for (int i = 0; i < 12; i++)
            input.add(new Memory("own-" + i, "student", "student", "reflection", now.minusSeconds(60L * (i + 1)), "先看完这页再说", null, List.of(), 7));

        assertThat(CompanionRecall.retrieve(input, "student", "", now, 6))
            .as("一条站得住的信念，不该和随口的说法一起被挤掉").contains(belief);
    }

    private Memory memory(String id, String owner, Instant at, String text, String topic) {
        return new Memory(id, owner, "self", "observed", at, text, topic);
    }
}
