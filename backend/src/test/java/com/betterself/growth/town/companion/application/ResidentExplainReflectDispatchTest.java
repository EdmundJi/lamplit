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
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w); // first tick: explain is attempted and fails as unsupported
            await(() -> store.updates.get() >= 2);
            assertThat(decideCalls).as("must not have fallen through to a decision on the very same tick").hasValue(0);
            assertThat(w.modelConsecutiveFailures).as("a missing capability is not a model failure").isZero();
            assertThat(w.modelFailuresToday).isZero();
            assertThat(w.modelRetryAfter).as("must not trigger the shared backoff window either").isNull();

            // A later tick must no longer be starved by the same unsupported explain request winning
            // the same priority race every time - it should now fall through to the ordinary decision.
            // Retried (rather than called once) because consider() is single-flight per user and may
            // still legitimately no-op if the first dispatch's own inFlight bookkeeping has not yet
            // cleared at the exact moment this polls.
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
