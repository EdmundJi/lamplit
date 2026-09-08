package com.betterself.growth.town.companion.application;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.town.companion.adapters.QwenResidentMind;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.tools.InMemoryModelUsage;
import com.betterself.growth.town.companion.tools.InMemoryWorldStore;
import com.betterself.growth.town.companion.tools.MetricsExporter;
import com.betterself.growth.town.companion.tools.MutableClock;
import com.betterself.growth.town.companion.tools.TimelineCollector;
import com.betterself.growth.town.companion.tools.TimelineExporter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A headless runner that drives one {@link CompanionWorld} through many simulated days, entirely
 * through the same public entry points the app itself uses every time it polls:
 * {@link CompanionRules#join} once to seed a world with a caller-chosen, fixed id (so a run is
 * reproducible), then {@link CompanionService#advance} and {@link CompanionService#submit} every
 * tick - never anything from {@code domain/**} beyond those public static/instance methods, and
 * never {@code JdbcWorldStore}. See {@link InMemoryWorldStore} for the isolation this rests on,
 * and {@link AcceleratedTownRunnerIT} for how to invoke this from the command line.
 *
 * This class lives in the {@code application} test package (not a new {@code tools} package) for
 * one reason only: it needs to call {@link ResidentDirector#close()}, which is package-private.
 *
 * <h2>Why decisions are single-flight per world, and why this runner does not fight that</h2>
 * {@link ResidentDirector#consider} guards every dispatch with an {@code inFlight.add(userId)}
 * check: for one world (one userId, exactly what an accelerated run always is), at most one model
 * round trip is ever outstanding at a time, for the whole duration of that call including its
 * network I/O. That is production correctness, not an oversight - it is what keeps a shared,
 * mutable {@link CompanionWorld} from being written by two concurrent decisions at once, and this
 * package's boundary (see the task this class was built under) forbids touching
 * {@code application}/{@code domain} production code to work around it. So this runner does not
 * attempt to have several resident decisions in flight for the same world at once - that would
 * require either duplicating {@code ResidentDirector}'s own reservation logic here (which the
 * class-level javadoc above already rules out: only {@code CompanionService.advance}/{@code submit})
 * or weakening the single-flight guarantee, neither of which is safe.
 *
 * <p>What IS safe, and what {@link #run} actually does: {@link ResidentDirector} already dispatches
 * the network call itself on its own background thread, independent of the tick loop below - the
 * loop's {@code service.advance()} call returns immediately whether or not a model call was just
 * queued. The old version of this loop then unconditionally slept {@code realPaceMillisPerTick}
 * after every single tick, regardless of whether a call was actually outstanding - re-serializing
 * the loop with a conservative worst case on every tick instead of only when a call might actually
 * need the wall-clock room. The loop below still sleeps at the exact same pace, for the exact same
 * reason (see {@link RunConfig#withModel}: the simulated-seconds-per-real-second ratio must stay
 * under {@code ConversationLifecycle.OPERATION_TIMEOUT_SECONDS}, 45 simulated seconds, so a real
 * network call has a real chance to land before its reservation goes stale) - but only for a bounded
 * window after a dispatch is actually observed (via {@link CompanionWorld#modelSequence} ticking
 * up), not for every tick unconditionally. Ticks where nothing was just dispatched - which is most
 * of them once a decision throttle or a quiet stretch of the day is in effect - advance at full
 * speed. This changes nothing about correctness or the staleness safety margin for any individual
 * call (a call that gets dispatched still receives the exact same protected real-time budget it
 * always did); it only stops paying that budget when there is nothing to protect.
 */
public final class AcceleratedTownRunner {
    private AcceleratedTownRunner() {}

    /** The tighter of ConversationLifecycle.OPERATION_TIMEOUT_SECONDS (45s, dialogue turns) and
     * ResidentDirector's own 90s decision-staleness check - protecting for the tighter one is safe
     * for both. Not imported directly: this test-only runner does not depend on domain internals
     * beyond the public API, per this class's own javadoc, so the constant is duplicated here with
     * a pointer back to its source of truth. */
    private static final long STALENESS_BUDGET_SECONDS = 45;

    public record RunConfig(
        String worldId, String avatarName, String timezone, Instant start,
        double days, int tickSeconds, boolean modelEnabled, int dailyModelBudget,
        long userId, Path outDir, long realPaceMillisPerTick, int drainTicks,
        long blindTestSeed, int blindTestSize, boolean scriptedAvatarIntents,
        Path resumeFrom
    ) {
        /** Pure-rule defaults: no model calls, no staleness constraint, so ticks can be coarse and
         * the whole run is deterministic and fast - safe for a smoke test that must not spend tokens. */
        public static RunConfig ruleOnly(Path outDir, double days) {
            return new RunConfig("accelerated-rule-run", "我", "Asia/Shanghai",
                Instant.parse("2026-01-01T00:00:00Z"), days, 60, false, 0,
                1, outDir, 0, 0, 42, 20, true, null);
        }

        /** Model-on defaults: fine-grained ticks with a short real sleep between them. Both
         * ConversationLifecycle.OPERATION_TIMEOUT_SECONDS (45 simulated seconds) and
         * ResidentDirector's own 90-second decision-staleness check discard a model reply that
         * arrives after the simulated clock has moved on too far while the real network call was
         * in flight - so the simulated-seconds-per-real-second ratio must stay well under that
         * budget. docs/05-notes.md measures DeepSeek round trips at 1.7-2.5s; 8s/tick with a 600ms
         * sleep is a 13.3:1 ratio, which still leaves a 2.5s call ~12s of margin (32%) before the
         * tighter 45s dialogue-turn window - down from the original 10:1 (800ms sleep, ~18s margin
         * at 2.5s) because measuring this batch's actual DeepSeek latency (see the item-1 speed
         * comparison this config was tuned against) showed real headroom the original, more
         * conservative ratio was not using. See the class javadoc for why this sleep is now applied
         * adaptively (only while a call is plausibly outstanding) rather than every tick regardless. */
        public static RunConfig withModel(Path outDir, double days) {
            return new RunConfig("accelerated-model-run", "我", "Asia/Shanghai",
                Instant.parse("2026-01-01T00:00:00Z"), days, 8, true, 100000,
                1, outDir, 600, 25, 42, 20, true, null);
        }

        /** Same run, continuing from a previously exported {@code world-snapshot.json} instead of a
         * fresh {@link CompanionRules#join}. {@code days} is how many MORE simulated days to run from
         * wherever the snapshot left off - the snapshot's own {@code updatedAt} becomes this run's
         * effective start, not {@code start}, so what {@code start} is set to no longer matters. */
        public RunConfig withResumeFrom(Path snapshot) {
            return new RunConfig(worldId, avatarName, timezone, start, days, tickSeconds, modelEnabled,
                dailyModelBudget, userId, outDir, realPaceMillisPerTick, drainTicks, blindTestSeed,
                blindTestSize, scriptedAvatarIntents, snapshot);
        }
    }

    public record RunResult(
        Path outDir, Instant simulatedFrom, Instant simulatedTo, long ticks,
        int diaryCount, int eventCount, int memoryCount, int dialogueTurnCount, int relationshipChangeCount,
        long totalModelCalls, long totalInputTokens, long totalOutputTokens, Duration wallTime,
        long pacedTicks, long fastTicks
    ) {}

    private static final class ScriptedIntent {
        final long afterSeconds;
        final CompanionService.Command command;
        boolean fired;
        ScriptedIntent(long afterSeconds, CompanionService.Command command) {
            this.afterSeconds = afterSeconds;
            this.command = command;
        }
    }

    public static RunResult run(RunConfig cfg) throws IOException {
        long startNanos = System.nanoTime();
        var store = new InMemoryWorldStore();
        var usage = new InMemoryModelUsage();

        boolean resuming = cfg.resumeFrom() != null;
        CompanionWorld seed = resuming
            ? loadSnapshot(cfg.resumeFrom())
            : CompanionRules.join(cfg.worldId(), cfg.avatarName(), cfg.timezone(), cfg.start(), cfg.modelEnabled());
        Instant runStart = resuming ? seed.updatedAt : cfg.start();

        var clock = new MutableClock(runStart);
        ResidentMind mind = cfg.modelEnabled() ? liveMind() : disabledMind();
        var director = new ResidentDirector(store, mind, clock, Math.max(1, cfg.dailyModelBudget()), usage);
        var service = new CompanionService(store, clock, director, usage);

        store.seed(cfg.userId(), seed);

        var collector = new TimelineCollector();
        collector.capture(seed);
        collector.capturePersonalitySnapshot(seed); // a true t=0 baseline for THIS run, resumed or fresh

        List<ScriptedIntent> scripted = cfg.scriptedAvatarIntents() ? scriptedIntents() : List.of();

        long totalSeconds = Math.round(cfg.days() * 86400);
        int tickSeconds = Math.max(1, cfg.tickSeconds());
        long ticksInSpan = Math.max(1, totalSeconds / tickSeconds);
        long ticks = ticksInSpan + Math.max(0, cfg.drainTicks());
        long ticksPerDay = Math.max(1, 86400 / tickSeconds);
        long protectedTicksWindow = (long) Math.ceil((double) STALENESS_BUDGET_SECONDS / tickSeconds) + 1;

        Instant t = runStart;
        long lastKnownModelSequence = seed.modelSequence; // baseline BEFORE the loop, so a dispatch on tick 1 is still detected
        long protectedTicksRemaining = 0;
        long pacedTicks = 0, fastTicks = 0;
        try {
            for (long i = 1; i <= ticks; i++) {
                t = t.plusSeconds(tickSeconds);
                clock.advanceTo(t);
                long elapsedSeconds = Duration.between(runStart, t).getSeconds();
                fireDueScripted(service, cfg.userId(), scripted, elapsedSeconds);
                CompanionService.View view = service.advance(cfg.userId());
                collector.capture(view.world());
                if (i % ticksPerDay == 0) collector.capturePersonalitySnapshot(view.world());

                if (cfg.modelEnabled() && cfg.realPaceMillisPerTick() > 0) {
                    long seq = view.world() == null ? lastKnownModelSequence : view.world().modelSequence;
                    if (seq != lastKnownModelSequence) {
                        // A dispatch (or a dispatch's resolution freeing the way for a new one) just
                        // happened - re-arm the protective window for exactly the budget a call is
                        // allowed before ConversationLifecycle/ResidentDirector treat it as stale.
                        lastKnownModelSequence = seq;
                        protectedTicksRemaining = protectedTicksWindow;
                    }
                    if (protectedTicksRemaining > 0) {
                        sleepQuietly(cfg.realPaceMillisPerTick());
                        protectedTicksRemaining--;
                        pacedTicks++;
                    } else {
                        fastTicks++;
                    }
                }
            }
        } finally {
            if (cfg.modelEnabled()) director.close(); // shuts down the daemon worker pool; harmless if one last call is mid-flight
        }

        var sorted = TimelineExporter.sortedByTime(collector.entries());
        CompanionWorld finalWorld = store.read(cfg.userId());
        export(cfg, sorted, usage, finalWorld, runStart, t, collector);

        Duration wall = Duration.ofNanos(System.nanoTime() - startNanos);
        return new RunResult(cfg.outDir(), runStart, t, ticks,
            (int) sorted.stream().filter(e -> "diary".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "event".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "memory".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "dialogue".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "relationship".equals(e.get("kind"))).count(),
            usage.totalCalls(), usage.totalInputTokens(), usage.totalOutputTokens(), wall,
            pacedTicks, fastTicks);
    }

    /** Two deterministic user "thoughts" fired at fixed simulated offsets from the run's start, so
     * CompanionService.submit() (both an explicit and a passing/thought priority, the latter using
     * the exact "想看看花" example from docs/01-requirements.md) is exercised the same way a real
     * user's click would exercise it - not just the residents' own autonomous life. */
    private static List<ScriptedIntent> scriptedIntents() {
        return List.of(
            new ScriptedIntent(600, new CompanionService.Command("run-walk-1", "walk", "explicit", null, 25, null)),
            new ScriptedIntent(21600, new CompanionService.Command("run-thought-1", "thought", "passing", null, 25, "想看看花"))
        );
    }

    private static void fireDueScripted(CompanionService service, long userId, List<ScriptedIntent> scripted, long elapsedSeconds) {
        for (ScriptedIntent si : scripted) {
            if (!si.fired && elapsedSeconds >= si.afterSeconds) {
                service.submit(userId, si.command);
                si.fired = true;
            }
        }
    }

    private static ResidentMind disabledMind() {
        return new ResidentMind() {
            @Override public boolean enabled() { return false; }
            @Override public Decision decide(Context context) {
                throw new UnsupportedOperationException("rule-only mode never calls the model");
            }
        };
    }

    /** Builds a real DeepSeek-backed mind directly, the same way ResidentMindLiveEmojiIT does:
     * QwenHttpProvider's non-Spring constructor, credentials read straight from the environment
     * (see .env.local's QWEN_* names) and never logged or written anywhere. No Spring context is
     * started, so this never risks wiring in the real DataSource/JdbcWorldStore by accident. */
    private static ResidentMind liveMind() {
        String baseUrl = requireEnv("QWEN_BASE_URL");
        String apiKey = requireEnv("QWEN_API_KEY");
        String model = requireEnv("QWEN_MODEL");
        Duration timeout = envDuration("QWEN_TIMEOUT", Duration.ofSeconds(35));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var provider = new QwenHttpProvider(json, baseUrl, apiKey, model, timeout);
        return new QwenResidentMind(provider, json, "qwen", true);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Model mode requires " + name + " in the environment (see .env.local) - refusing to guess a default.");
        return value;
    }

    private static Duration envDuration(String name, Duration fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Duration.parse(value);
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** The same Jackson configuration JdbcWorldStore uses in production to round-trip a
     * {@link CompanionWorld} through {@code state_json} - reused here so a snapshot this runner
     * writes decodes exactly the way the real save/load path would, proving nothing but the public
     * shape of the world is involved. */
    private static final ObjectMapper WORLD_JSON = new ObjectMapper().findAndRegisterModules();

    private static CompanionWorld loadSnapshot(Path file) throws IOException {
        return WORLD_JSON.readValue(file.toFile(), CompanionWorld.class);
    }

    private static void export(RunConfig cfg, List<Map<String, Object>> sorted, InMemoryModelUsage usage,
                                CompanionWorld finalWorld, Instant runStart, Instant finalInstant, TimelineCollector collector) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("worldId", finalWorld == null ? cfg.worldId() : finalWorld.id);
        manifest.put("avatarName", finalWorld == null ? cfg.avatarName() : finalWorld.name);
        manifest.put("timezone", finalWorld == null ? cfg.timezone() : finalWorld.timezone);
        manifest.put("resumedFrom", cfg.resumeFrom() == null ? null : cfg.resumeFrom().toAbsolutePath().toString());
        manifest.put("simulatedStart", runStart.toString());
        manifest.put("simulatedEnd", finalInstant.toString());
        manifest.put("requestedDays", cfg.days());
        manifest.put("tickSeconds", cfg.tickSeconds());
        manifest.put("modelEnabled", cfg.modelEnabled());
        manifest.put("dailyModelBudget", cfg.dailyModelBudget());
        manifest.put("userId", cfg.userId());
        manifest.put("scriptedAvatarIntents", cfg.scriptedAvatarIntents());
        manifest.put("blindTestSeed", cfg.blindTestSeed());
        manifest.put("blindTestSize", cfg.blindTestSize());
        manifest.put("finalWorldRevision", finalWorld == null ? null : finalWorld.revision);
        manifest.put("finalModelStatus", finalWorld == null ? null : finalWorld.modelStatus);
        manifest.put("entryCount", sorted.size());
        TimelineExporter.writeJson(cfg.outDir().resolve("manifest.json"), manifest);

        TimelineExporter.writeJson(cfg.outDir().resolve("timeline.json"), sorted);
        TimelineExporter.writeMarkdown(cfg.outDir().resolve("timeline.md"), sorted, cfg.timezone());
        TimelineExporter.writeHighlightsMarkdown(cfg.outDir().resolve("highlights.md"), sorted, cfg.timezone());

        Map<String, Object> usageReport = new LinkedHashMap<>();
        usageReport.put("rows", usage.allRows());
        usageReport.put("totalCalls", usage.totalCalls());
        usageReport.put("totalInputTokens", usage.totalInputTokens());
        usageReport.put("totalOutputTokens", usage.totalOutputTokens());
        TimelineExporter.writeJson(cfg.outDir().resolve("usage.json"), usageReport);

        // Blind test: questions and answers live in two separate directories on purpose (see
        // docs/05-notes.md "手抄是个静默失败点" / docs/04-decisions.md "验收") - whoever actually
        // takes the test is handed only blind-test/quiz/, never blind-test/key/.
        var blind = TimelineExporter.buildBlindTest(sorted, cfg.blindTestSize(), cfg.blindTestSeed());
        TimelineExporter.writeJson(cfg.outDir().resolve("blind-test/quiz/quiz.json"), blind.quiz());
        TimelineExporter.writeBlindTestQuizText(cfg.outDir().resolve("blind-test/quiz/quiz.txt"), blind.quiz());
        TimelineExporter.writeJson(cfg.outDir().resolve("blind-test/key/answer-key.json"), blind.answerKey());
        TimelineExporter.writeJson(cfg.outDir().resolve("blind-test/key/pool-stats.json"), blind.poolStats());

        // Emergence metrics: the run's actual "did it work" evidence, not an impression from reading
        // timeline.md - see docs/01-requirements.md "让他们自己产生秩序" and MetricsExporter's javadoc.
        Map<String, Object> metrics = MetricsExporter.compute(sorted, collector, finalWorld);
        MetricsExporter.writeJson(cfg.outDir().resolve("metrics.json"), metrics);
        MetricsExporter.writeMarkdown(cfg.outDir().resolve("metrics.md"), metrics);

        // World snapshot: lets a later run resume exactly where this one left off (RunConfig.withResumeFrom).
        if (finalWorld != null) TimelineExporter.writeJson(cfg.outDir().resolve("world-snapshot.json"), finalWorld);
    }
}
