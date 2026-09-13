package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;

/**
 * Wires the "explain" and "reflect" capabilities all the way through {@link ResidentDirector}'s
 * dispatch (not just ResidentMind's own default methods): both must actually be reserved and sent to
 * a mind, both must land through the same identity-checked apply path every other capability uses, and
 * a mind that does not implement one of them must never be treated as a real failure - see
 * ResidentDirector.explainUnavailable's own doc comment for why that matters.
 */
class ResidentExplainReflectDispatchTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z"); // 14:00 in Asia/Shanghai - outside the morning day-plan window

    private CompanionWorld world() {
        CompanionWorld w = CompanionRules.join("explain-reflect", "住客", "Asia/Shanghai", now, true);
        w.conversations.forEach(c -> c.status = "ended"); // no active/summarizable conversation to outrank explain/reflect
        w.pendingEncounters.clear();
        w.serviceRequests.clear();
        return w;
    }

    /** Everyone except the given ids is parked on an ordinary "sleep" plan, which
     * ResidentSimulation.needsDecision always excludes - so nobody but the named residents can ever
     * become an ordinary-decision candidate. */
    private static void parkEveryoneElseAsleep(CompanionWorld w, Instant now, String... awake) {
        Set<String> keep = Set.of(awake);
        for (ResidentState r : w.residentStates)
            if (!keep.contains(r.id) && !r.id.equals("self"))
                r.plan = new Plan("park-" + r.id, "sleep", "home-" + r.id, null, "睡着", now, now.plusSeconds(600));
    }

    @Test void explainIsDispatchedAheadOfAnOrdinaryDecisionCarryingOughtSelfAndBystanderOnlyDeeds() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "owner");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = null; // also eligible for an ordinary decision - proves explain wins the race, not just that it works alone
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "又擦了一遍已经擦过的那张桌子。", now);
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "把杯子按高矮重新排了一次。", now.plusSeconds(60));
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "擦了柜台。", now.plusSeconds(120));
        assertThat(ResidentSimulation.needsExplanation(w, "owner", now)).isTrue();

        var store = new CountingStore(w);
        var captured = new ResidentMind.ExplainRequest[1];
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { throw new AssertionError("explain must be dispatched before an ordinary decision when both are pending"); }
            public ExplainDraft explain(ExplainRequest request) {
                captured[0] = request;
                return new ExplainDraft(request.deeds().stream().map(DeedView::id).toList(), "桌子确实脏了，顺手都擦了。", List.of());
            }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> store.updates.get() >= 2);
        } finally { director.close(); }

        assertThat(captured[0]).as("explain must actually have been invoked").isNotNull();
        assertThat(captured[0].perspective().residentId()).isEqualTo("owner");
        // Superego reaches the account: 阿禾's oughtSelf is what should shape (and often distort) his
        // own explanation of himself - the model cannot lean on it if it never arrives.
        assertThat(captured[0].perspective().persona()).isNotNull();
        assertThat(captured[0].perspective().persona().oughtSelf()).isEqualTo(ResidentSeed.narrative("owner").oughtSelf());
        assertThat(captured[0].deeds()).hasSize(3);
        assertThat(captured[0].deeds()).extracting(ResidentMind.DeedView::note)
            .containsExactlyInAnyOrder("又擦了一遍已经擦过的那张桌子。", "把杯子按高矮重新排了一次。", "擦了柜台。");
        // Bystander-only facts: the rules never write a motive into a deed (CompanionWorld.Deed's own
        // doc comment), so none of these notes should carry one either.
        assertThat(captured[0].deeds()).extracting(ResidentMind.DeedView::note)
            .noneMatch(note -> note.contains("心里") || note.contains("不舒服") || note.contains("因为") || note.contains("想"));

        // The account is not just requested - it actually lands, through the same identity-checked
        // apply path every other model output goes through.
        assertThat(owner.thought).isEqualTo("桌子确实脏了，顺手都擦了。");
        assertThat(ResidentSimulation.unexplainedDeeds(w, "owner")).isEmpty();
    }

    /** A new call kind is exactly where this project keeps producing "compiles clean, feature is
     * dead": six separate capabilities in this codebase had a completion path, a test, and no route
     * from the dispatcher to them, and none was noticed until a full run was measured. So venture
     * gets the same treatment as the rest - dispatched for real, applied through the real propose
     * path, and visible to everybody afterwards. */
    @Test void ventureIsDispatchedWhenTheTownHasNothingLeftToDoTogetherAndTheWishActuallyLands() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "weaver");
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        weaver.plan = new Plan("weaver-sleep", "sleep", "home-weaver", null, "睡着", now, now.plusSeconds(600));
        for (CompanionWorld.Project p : w.projects) { p.status = "ready"; p.progress = 100; }
        // Everyone else was asked recently, so the one who has waited longest is 阿满 - which is also
        // what stops one resident being asked what they want every single time.
        for (ResidentState other : w.residentStates)
            if (!other.id.equals("weaver")) other.lastVentureAt = now.minusSeconds(60);
        assertThat(ResidentSimulation.needsVenture(w, "weaver", now)).isTrue();
        addRawMemory(w, "weaver", now.minusSeconds(600), 7);
        String evidenceId = w.memories.stream().filter(m -> m.ownerId().equals("weaver"))
            .reduce((a, b) -> b).orElseThrow().id();

        var store = new CountingStore(w);
        var captured = new ResidentMind.VentureRequest[1];
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { throw new AssertionError("no ordinary decision should be needed here"); }
            public VentureDraft venture(VentureRequest request) {
                captured[0] = request;
                return new VentureDraft("把街口那盏灯修好", "street", "poster", "总有人晚上看不清路", List.of(evidenceId));
            }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> store.updates.get() >= 2);
        } finally { director.close(); }

        assertThat(captured[0]).as("venture must actually have been invoked").isNotNull();
        assertThat(captured[0].perspective().residentId()).isEqualTo("weaver");
        assertThat(captured[0].sharedThingsLeft()).as("the emptiness is shown, not asserted at them").isEmpty();

        var wish = w.projects.stream().filter(p -> "weaver".equals(p.ownerId)).reduce((a, b) -> b).orElseThrow();
        assertThat(wish.title).isEqualTo("把街口那盏灯修好");
        assertThat(wish.place).isEqualTo("street");
        assertThat(wish.needed).as("a wish is a thing that needs somebody else").isGreaterThan(1);
        // And it is on the board, or nobody could ever join it.
        for (String id : List.of("owner", "student", "artist", "gardener", "fixer"))
            assertThat(ResidentSimulation.knows(w, id, wish.id)).as(id).isTrue();
        assertThat(ResidentSimulation.needsVenture(w, "weaver", now))
            .as("asked is asked, whatever came of it").isFalse();
    }

    /** A mind with no venture of its own must degrade silently, exactly like explain and reflect -
     * never counted as a real model failure, never able to starve anything else. */
    @Test void aMindThatCannotVentureIsNotTreatedAsAFailure() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "weaver");
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        weaver.plan = new Plan("weaver-sleep", "sleep", "home-weaver", null, "睡着", now, now.plusSeconds(600));
        for (CompanionWorld.Project p : w.projects) { p.status = "ready"; p.progress = 100; }

        var store = new CountingStore(w);
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { throw new AssertionError("no ordinary decision should be needed here"); }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> store.updates.get() >= 1);
        } finally { director.close(); }
        assertThat(w.modelConsecutiveFailures).as("an unimplemented capability is not an outage").isZero();
    }

    @Test void reflectFiresOnlyWhenNothingElseNeedsTheModelAndItsConclusionActuallyLands() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "artist");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = new Plan("artist-sleep", "sleep", "home-artist", null, "睡着", now, now.plusSeconds(600)); // excluded from ordinary decision too
        artist.lastReflectionAt = now.minusSeconds(4 * 3600);
        addRawMemory(w, "artist", now.minusSeconds(3600), 9);
        addRawMemory(w, "artist", now.minusSeconds(1800), 9);
        addRawMemory(w, "artist", now.minusSeconds(600), 9); // sum 27 >= the importance threshold
        assertThat(ResidentSimulation.needsReflection(w, "artist", now)).isTrue();
        List<Memory> source = ResidentSimulation.reflectionSource(w, "artist", now);
        String evidenceId = source.getFirst().id();

        var store = new CountingStore(w);
        var captured = new ResidentMind.ReflectRequest[1];
        String key = "artist-测试信念";
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { throw new AssertionError("no ordinary decision should be needed here"); }
            public ReflectDraft reflect(ReflectRequest request) {
                captured[0] = request;
                return new ReflectDraft("反复想过之后，觉得确实是这样。", List.of(evidenceId), key);
            }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> store.updates.get() >= 2);
        } finally { director.close(); }

        assertThat(captured[0]).as("reflect must actually have been invoked").isNotNull();
        assertThat(captured[0].perspective().residentId()).isEqualTo("artist");
        // Evidence is checked against reflectionSource(...), the open browse actually offered - not
        // against the narrower, query-shaped context().memories() an ordinary decision would see.
        assertThat(captured[0].source()).extracting(ResidentMind.MemoryView::id).contains(evidenceId);

        Memory belief = w.memories.stream().filter(m -> m.ownerId().equals("artist") && key.equals(m.supersedesKey())).findFirst().orElseThrow();
        assertThat(belief.sourceType()).isEqualTo("belief");
        assertThat(belief.text()).isEqualTo("反复想过之后，觉得确实是这样。");
        assertThat(artist.lastReflectionAt).isEqualTo(now);
    }

    /**
     * A resident who already holds a standing view gets it handed back, key and all, when they next
     * reflect.
     *
     * <p>Why it matters: {@code supersedesKey} supersedes on an exact string match only (see {@code
     * ResidentSimulation.supersedePrevious}). A resident asked to invent a short label from nothing
     * every time will invent a different one every time - measured, twice, in the same run: 阿满 wrote
     * {@code weaver配合者} and {@code weaver-配合者} for one idea, so neither ever replaced the other and
     * the town read as though she had never changed her mind. Offering the existing keys back is what
     * turns "invent an identifier" into "pick the one this is about", which is what every surveyed
     * system that actually revises beliefs does (Graphiti hands integer indices, Mem0 masks its ids
     * and forbids new ones, Affordable Generative Agents prints the current value and asks whether to
     * update it).
     *
     * <p>What is NOT asserted here, deliberately: which way the view should move. The rules hand back
     * the resident's own earlier words and nothing else - confirming it and abandoning it are the same
     * shape from here (docs/04 「允许往坏了长」).
     */
    @Test void aResidentsOwnStandingViewsAreHandedBackSoTheKeyCanBeReusedRatherThanReinvented() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "artist");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = new Plan("artist-sleep", "sleep", "home-artist", null, "睡着", now, now.plusSeconds(600));
        artist.lastReflectionAt = now.minusSeconds(4 * 3600);
        addRawMemory(w, "artist", now.minusSeconds(3600), 9);
        addRawMemory(w, "artist", now.minusSeconds(1800), 9);
        addRawMemory(w, "artist", now.minusSeconds(600), 9);

        // One view she already holds, and one she has since moved on from. Only the live one comes back.
        w.memories.add(new Memory("m-belief-live", "artist", "artist", "belief", now.minusSeconds(7200),
            "小川总是坐窗边那个位子。", "seat", List.of(), 9, "artist:小川-座位", false));
        w.memories.add(new Memory("m-belief-old", "artist", "artist", "belief", now.minusSeconds(90000),
            "以前觉得他只是随便挑的。", "seat", List.of(), 9, "artist:小川-座位", true));
        // Somebody else's belief must never leak into her prompt.
        w.memories.add(new Memory("m-belief-other", "owner", "owner", "belief", now.minusSeconds(7200),
            "阿满做事最稳。", "work", List.of(), 9, "owner:阿满-做事", false));

        var store = new CountingStore(w);
        var captured = new ResidentMind.ReflectRequest[1];
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { throw new AssertionError("no ordinary decision should be needed here"); }
            public ReflectDraft reflect(ReflectRequest request) { captured[0] = request; return null; }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> captured[0] != null);
        } finally { director.close(); }

        assertThat(captured[0]).as("reflect must actually have been invoked").isNotNull();
        assertThat(captured[0].standingBeliefs())
            .extracting(ResidentMind.StandingBeliefView::key, ResidentMind.StandingBeliefView::text)
            .containsExactly(tuple("artist:小川-座位", "小川总是坐窗边那个位子。"));
    }

    @Test void reflectNeverPreemptsAnOrdinaryDecisionEvenWhenBothAreDue() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "artist");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null; // makes an ordinary decision due too - reflect is the LOWEST priority
        artist.lastReflectionAt = now.minusSeconds(4 * 3600);
        addRawMemory(w, "artist", now.minusSeconds(3600), 9);
        addRawMemory(w, "artist", now.minusSeconds(1800), 9);
        addRawMemory(w, "artist", now.minusSeconds(600), 9);
        assertThat(ResidentSimulation.needsReflection(w, "artist", now)).isTrue();

        var store = new CountingStore(w);
        var decideCalls = new AtomicInteger();
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { decideCalls.incrementAndGet(); return new Decision("observe", c.self().place(), null, "先看看四周", "", List.of(), null, null); }
            public ReflectDraft reflect(ReflectRequest request) { throw new AssertionError("reflect must never preempt a due ordinary decision"); }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> store.updates.get() >= 2);
        } finally { director.close(); }

        assertThat(decideCalls).hasValue(1);
    }

    @Test void aMindThatDoesNotImplementExplainNeverBurnsTheFailureBudgetAndStopsBlockingLaterDecisions() throws Exception {
        CompanionWorld w = world();
        parkEveryoneElseAsleep(w, now, "owner");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = null; // also due for an ordinary decision, which explain's priority would otherwise keep blocking forever
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "擦了柜台。", now);
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "擦了桌子。", now.plusSeconds(60));
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "理了杯子。", now.plusSeconds(120));

        var store = new CountingStore(w);
        var decideCalls = new AtomicInteger();
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) { decideCalls.incrementAndGet(); return new Decision("observe", c.self().place(), null, "先看看四周", "", List.of(), null, null); }
            // explain deliberately not overridden: ResidentMind's own default throws UnsupportedOperationException.
        };
        // Pinned to one call in flight. "Not on the very same tick" below is a statement about
        // sequencing, and sequencing within one consider() is exactly what docs/04-decisions.md
        // 「只并行"想"，写世界仍串行」 changed: unpinned, this resident's explain fails as unsupported and
        // releases them again fast enough (there is no network here) that the next worker in the chain
        // legitimately picks up their ordinary decision inside the same await window. Nothing this test
        // is actually about changes - a missing capability still never burns the failure budget, and
        // the later tick must still fall through rather than be starved; both are asserted below.
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC), 100000,
            (userId, day, callType, inputTokens, outputTokens) -> {}, 8, 64, 12, 1);
        try {
            director.consider(1, w); // first tick: explain is attempted and fails as unsupported
            await(() -> store.updates.get() >= 2);
            assertThat(decideCalls).as("must not have fallen through to a decision on the very same tick").hasValue(0);
            assertThat(w.modelConsecutiveFailures).as("a missing capability is not a model failure").isZero();
            assertThat(w.modelFailuresToday).isZero();
            assertThat(w.modelRetryAfter).as("must not trigger the shared backoff window either").isNull();

            // A later tick must no longer be starved by the same unsupported explain request winning
            // the same priority race every time - it should now fall through to the ordinary decision.
            // Retried (rather than called once) because this director is pinned to one call at a time
            // and may still legitimately no-op if the first dispatch's own worker bookkeeping has not
            // yet cleared at the exact moment this polls.
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (decideCalls.get() < 1 && System.nanoTime() < deadline) {
                director.consider(1, w);
                Thread.sleep(5);
            }
        } finally { director.close(); }

        assertThat(decideCalls).hasValue(1);
        assertThat(w.modelConsecutiveFailures).isZero();
        assertThat(owner.plan).as("the decision that finally got through must have actually applied").isNotNull();
    }

    private static void addRawMemory(CompanionWorld w, String owner, Instant at, int importance) {
        List<Memory> memories = new ArrayList<>(w.memories);
        // topicId is deliberately non-null here (unlike a plain everyday observation, which may well
        // have none): ResidentSimulation.applyReflection's own topic lookup
        // (w.memories.stream().filter(...).map(Memory::topicId).findFirst()) throws a NullPointerException
        // via Stream's internal Optional.of(null) whenever evidenceIds.get(0) resolves to a memory whose
        // topicId is null - a real, pre-existing bug in ResidentSimulation.java (out of bounds for this
        // change; see the report) that a topic-less test memory would otherwise trip on every run.
        memories.add(new Memory("test-" + memories.size() + "-" + at.toEpochMilli(), owner, owner, "observed", at, "测试用记忆", "test-topic", List.of(), importance));
        w.memories = memories;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** Counts every transaction ResidentDirector runs against the world (reserve, then apply/catch) so
     * tests can wait for a dispatch to fully settle without depending on call-type-specific status text. */
    static class CountingStore implements WorldStore {
        CompanionWorld world;
        final AtomicInteger updates = new AtomicInteger();
        CountingStore(CompanionWorld world) { this.world = world; }
        public CompanionWorld read(long id) { return world; }
        public synchronized CompanionWorld update(long id, Supplier<CompanionWorld> initial, UnaryOperator<CompanionWorld> change) {
            try { return world = change.apply(world); } finally { updates.incrementAndGet(); }
        }
        public boolean ownsTask(long id, String task) { return false; }
        public String timezone(long id) { return "Asia/Shanghai"; }
    }
}
