package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a resident is handed to think with has to be about the situation they are in - not about the
 * last thing they said about it.
 *
 * <p>The query that fetched a resident's memories used to be built from {@code r.plan.reason()}: the
 * rationalisation that resident themselves wrote the previous time they were asked. Every such reason
 * is also filed as a memory. So the question was made out of the answer, and it fetched the answer
 * back. Measured across 243 real decisions: <b>51% of everything retrieved was the resident's own
 * previous sentences</b>, 66% was written by them at all, and 44% of decisions received a
 * byte-identical memory set to that resident's previous one. 78 of those decisions then came back
 * word for word - an echo chamber with a retrieval score on it.
 *
 * <p>Generative Agents queries retrieval with the agent's situation. So does this now.
 */
class RetrievalIsAboutTheSituationTest {
    private static final Instant NOW = Instant.parse("2026-09-08T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ECHO = "刚搬来先在家里歇会儿整理心情";

    /** A director with no model wired: only {@link ResidentDirector#perspective} is exercised. */
    private static ResidentDirector director(CompanionWorld w) {
        return new ResidentDirector(new WorldStore() {
            public CompanionWorld read(long userId) { return w; }
            public CompanionWorld update(long userId, java.util.function.Supplier<CompanionWorld> initial, java.util.function.UnaryOperator<CompanionWorld> operation) { return operation.apply(w); }
            public boolean ownsTask(long userId, String taskId) { return false; }
            public String timezone(long userId) { return w.timezone; }
        }, new ResidentMind() {
            public boolean enabled() { return false; }
            public Decision decide(Context context) { throw new UnsupportedOperationException(); }
        }, CLOCK, 100);
    }
    /** Domain helpers for placing people and writing memories are package-private, so this test uses
     * the world's own public state the same way ResidentDirectorDialogueTest's `move` already does. */
    private static void place(CompanionWorld w, String id, String where, String activity, String label, Instant until) {
        for (int i = 0; i < w.residents.size(); i++) {
            var actor = w.residents.get(i);
            if (id.equals(actor.id())) w.residents.set(i, new CompanionWorld.Actor(actor.id(), actor.name(), actor.role(), where, activity, label, actor.x(), actor.y(), until));
        }
    }
    private static String remember(CompanionWorld w, String owner, String source, String type, Instant at, String text, int importance) {
        String id = "m-test-" + (++w.eventSequence);
        w.memories.add(new CompanionWorld.Memory(id, owner, source, type, at, text, null, List.of(), importance, null, false));
        return id;
    }

    @Test
    @DisplayName("自己上一次写的那句话，不该是用来找记忆的问题")
    void aResidentsOwnLastSentenceNoLongerFetchesItselfBack() {
        CompanionWorld w = CompanionRules.join("retrieval-echo", "住客", "Asia/Shanghai", NOW, true);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, NOW, "测试准备"));
        w.memories.clear();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        place(w, "artist", "cafe", "rest", "在店里歇着", NOW.plusSeconds(1800));
        place(w, "owner", "cafe", "observe", "在吧台后面", NOW.plusSeconds(1800));
        // The plan she is in the middle of, carrying the sentence she wrote about it last time.
        artist.plan = new CompanionWorld.Plan("p-echo", "rest", "cafe", null, ECHO, NOW.minusSeconds(600), NOW.plusSeconds(600));

        // Twelve echoes of her own last sentence, and three things that actually happened in the room
        // she is sitting in. Twelve beats the retrieval limit of ten on its own, so if the query is
        // still made of her own words, the three real ones cannot get in at all.
        for (int i = 0; i < 12; i++)
            remember(w, "artist", "artist", "reflection", NOW.minusSeconds(60L * (i + 1)), ECHO + "（第" + i + "次）", 7);
        remember(w, "artist", "owner", "observed", NOW.minusSeconds(900), "我在咖啡馆看见阿禾在吧台后面忙。", 5);
        remember(w, "artist", "owner", "heard", NOW.minusSeconds(1200), "阿禾在咖啡馆说这几天客人少。", 5);
        remember(w, "artist", "artist", "observed", NOW.minusSeconds(1500), "咖啡馆窗边那张桌子被人挪过了。", 5);

        var context = director(w).perspective(w, "artist", NOW, List.of());
        var texts = context.memories().stream().map(ResidentMind.MemoryView::text).toList();

        assertThat(texts).as("她坐在咖啡馆里、阿禾就在眼前——这些才是此刻该想起来的")
            .anyMatch(text -> text.contains("咖啡馆"));
        long echoes = texts.stream().filter(text -> text.startsWith(ECHO)).count();
        assertThat(echoes).as("自己的复读不该占满整个记忆窗口（%s）", texts)
            .isLessThan(context.memories().size());
    }

    @Test
    @DisplayName("一句随口的理由，不该比亲眼看见的事更重要")
    void aPassingReasonIsNotWorthMoreThanSomethingYouWatchedHappen() {
        CompanionWorld w = CompanionRules.join("retrieval-weight", "住客", "Asia/Shanghai", NOW, true);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, NOW, "测试准备"));
        w.memories.clear();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null;
        place(w, "artist", "cafe", "idle", "等下一步", NOW.plusSeconds(60));

        assertThat(ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "cafe", "rest", null, "先坐一会儿", null, List.of(), NOW)).isTrue();
        // Evidence is required before a decision's reason is filed at all; give her one real memory
        // to cite so the reflection actually gets written.
        String seen = remember(w, "artist", "artist", "observed", NOW.minusSeconds(60), "我看见窗边坐满了人。", 5);
        artist.plan = null;
        assertThat(ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "cafe", "rest", null, "还是先坐一会儿", null, List.of(seen), NOW.plusSeconds(60))).isTrue();

        var reason = w.memories.stream().filter(m -> "reflection".equals(m.sourceType()) && "还是先坐一会儿".equals(m.text())).findFirst().orElseThrow();
        var watched = w.memories.stream().filter(m -> m.id().equals(seen)).findFirst().orElseThrow();
        assertThat(reason.importance())
            .as("为什么坐下来这句话，不该和看着一件做了好几天的事完成同等重要")
            .isLessThan(watched.importance());
    }
}
