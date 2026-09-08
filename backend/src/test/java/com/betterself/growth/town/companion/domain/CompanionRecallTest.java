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

    private Memory memory(String id, String owner, Instant at, String text, String topic) {
        return new Memory(id, owner, "self", "observed", at, text, topic);
    }
}
