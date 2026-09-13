package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;

/**
 * Exercises the parallel-dispatch rewrite of {@link ResidentDirector} (docs/04-decisions.md 「只并行
 * "想"，写世界仍串行」: a network round trip is ~4s, a world write is microseconds, so 25 residents over
 * 2 simulated days - 7500 calls x 4s, 8+ hours if fully serial - is bottlenecked entirely on the model
 * call). The concurrency unit is one resident: several residents of ONE world may now have a model
 * call outstanding at once, up to {@code parallelism}, but no single resident is ever asked twice
 * concurrently. Every test here would fail against the old single-flight {@code inFlight.add(userId)}
 * gate this replaces - that gate allowed at most one dispatched worker per world, ever, so a second
 * concurrent call for the same world (whichever resident it was for) simply never happened.
 */
class ResidentDirectorParallelTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z"); // 14:00 Asia/Shanghai - outside the morning day-plan window

    /** Six ordinary-decision candidates (owner/student/artist/gardener plus the two hand-authored
     * newcomers, fixer/weaver - see ResidentSeed.initialize) and nothing else competing for reserve()
     * - conversations, pending encounters/occasions and service requests are all cleared, and every
     * NPC's plan is blanked so {@code needsDecision} is unconditionally true for each of them ({@code
     * r.plan==null} short-circuits it to true - see ResidentSimulation). "self" never joins this pool
     * (avatarAutonomyEnabled defaults off), so exactly six residents are ever actually reservable
     * here, however many workers get dispatched. */
    private CompanionWorld sixReadyResidents() {
        CompanionWorld w = CompanionRules.join("parallel-test", "住客", "Asia/Shanghai", now);
        w.conversations.clear();
        w.pendingEncounters.clear();
        w.pendingOccasions.clear();
        w.serviceRequests.clear();
        for (ResidentState r : w.residentStates) if (!r.id.equals("self")) r.plan = null;
        return w;
    }

    @Test void severalResidentsOfOneWorldCanThinkAtOnce() throws Exception {
        CompanionWorld w = sixReadyResidents();
        var store = new Store(w);
        AtomicInteger concurrent = new AtomicInteger();
        CountDownLatch reachedTwo = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) {
                if (concurrent.incrementAndGet() >= 2) reachedTwo.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                concurrent.decrementAndGet();
                return new Decision("observe", c.self().place(), null, "先看看四周", "", List.of(), null, null);
            }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            // Would time out under the old single-flight gate: consider() dispatched exactly one
            // worker per world, so a second concurrent call for a different resident could never exist.
            assertThat(reachedTwo.await(3, TimeUnit.SECONDS))
                .as("two residents of the same world must be able to think at once").isTrue();
        } finally { release.countDown(); director.close(); }
    }

    @Test void noResidentIsEverAskedTwiceAtOnce() throws Exception {
        CompanionWorld w = sixReadyResidents();
        var store = new Store(w);
        Set<String> outstanding = ConcurrentHashMap.newKeySet();
        AtomicBoolean duplicateSeen = new AtomicBoolean(false);
        CountDownLatch release = new CountDownLatch(1);
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) {
                if (!outstanding.add(c.residentId())) duplicateSeen.set(true);
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                outstanding.remove(c.residentId());
                return new Decision("observe", c.self().place(), null, "先看看四周", "", List.of(), null, null);
            }
        };
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC));
        try {
            director.consider(1, w);
            await(() -> outstanding.size() == 6);
            // Re-entrant ticks while all six calls are still blocked mid-flight - exactly the
            // scenario that would double-dispatch a resident if any branch of reserve() ever forgot
            // to check #thinking (item 4: every branch must skip a resident who is already thinking).
            for (int i = 0; i < 10; i++) { director.consider(1, w); Thread.sleep(5); }
            assertThat(duplicateSeen).as("no resident should ever be asked a second time while the first call is still in flight").isFalse();
            assertThat(outstanding).as("still exactly the original six, nobody dropped or duplicated").hasSize(6);
        } finally { release.countDown(); director.close(); }
    }

    @Test void parallelismCapBoundsHowManyResidentsCanThinkAtOnce() throws Exception {
        CompanionWorld w = sixReadyResidents();
        var store = new Store(w);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        int cap = 2;
        CountDownLatch reachedCap = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResidentMind mind = new ResidentMind() {
            public boolean enabled() { return true; }
            public Decision decide(Context c) {
                int n = concurrent.incrementAndGet();
                maxConcurrent.updateAndGet(m -> Math.max(m, n));
                if (n >= cap) reachedCap.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                concurrent.decrementAndGet();
                return new Decision("observe", c.self().place(), null, "先看看四周", "", List.of(), null, null);
            }
        };
        // Explicit constructor (parallelism threaded through the same way poolSize/queueSize/
        // decisionThrottleSeconds already were) so the cap can be set well below the six residents
        // this world has ready. The pool itself is left generous (8) so it is never the actual limit here.
        ModelUsageRecorder noRecorder = (userId, day, callType, inputTokens, outputTokens) -> {};
        var director = new ResidentDirector(store, mind, Clock.fixed(now, ZoneOffset.UTC), 100000, noRecorder, 8, 64, 12, cap);
        try {
            director.consider(1, w);
            assertThat(reachedCap.await(3, TimeUnit.SECONDS)).as("at least the capped number must actually run concurrently").isTrue();
            // A short, bounded grace window for a third worker to (wrongly) start before release is
            // signalled - the cap must hold given time, not just at the instant it was first reached.
            // maxConcurrent only ever grows (Math::max), so a late violation would still be caught here.
            Thread.sleep(200);
            assertThat(maxConcurrent.get()).as("never more than parallelism residents thinking at once").isLessThanOrEqualTo(cap);
            assertThat(concurrent.get()).isLessThanOrEqualTo(cap);
        } finally { release.countDown(); director.close(); }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** Hand-rolled {@link WorldStore}, {@code synchronized} exactly like {@code InMemoryWorldStore}
     * and {@code JdbcWorldStore}'s own row lock - reservations for one world must be serialised for
     * {@link ResidentDirector#thinking}'s claim to be race-free (see that field's own doc comment). */
    static class Store implements WorldStore {
        CompanionWorld world;
        Store(CompanionWorld world) { this.world = world; }
        public CompanionWorld read(long id) { return world; }
        public synchronized CompanionWorld update(long id, Supplier<CompanionWorld> initial, UnaryOperator<CompanionWorld> change) { return world = change.apply(world); }
        public boolean ownsTask(long id, String task) { return false; }
        public String timezone(long id) { return "Asia/Shanghai"; }
    }
}
