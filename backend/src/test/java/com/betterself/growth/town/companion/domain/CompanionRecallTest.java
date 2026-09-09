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

    private Memory memory(String id, String owner, Instant at, String text, String topic) {
        return new Memory(id, owner, "self", "observed", at, text, topic);
    }
}
