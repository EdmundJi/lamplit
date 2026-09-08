package com.betterself.growth.town.companion.application;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.town.companion.adapters.QwenResidentMind;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.tools.InMemoryModelUsage;
import com.betterself.growth.town.companion.tools.InMemoryWorldStore;
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
 */
public final class AcceleratedTownRunner {
    private AcceleratedTownRunner() {}

    public record RunConfig(
        String worldId, String avatarName, String timezone, Instant start,
        double days, int tickSeconds, boolean modelEnabled, int dailyModelBudget,
        long userId, Path outDir, long realPaceMillisPerTick, int drainTicks,
        long blindTestSeed, int blindTestSize, boolean scriptedAvatarIntents
    ) {
        /** Pure-rule defaults: no model calls, no staleness constraint, so ticks can be coarse and
         * the whole run is deterministic and fast - safe for a smoke test that must not spend tokens. */
        public static RunConfig ruleOnly(Path outDir, double days) {
            return new RunConfig("accelerated-rule-run", "我", "Asia/Shanghai",
                Instant.parse("2026-01-01T00:00:00Z"), days, 60, false, 0,
                1, outDir, 0, 0, 42, 20, true);
        }

        /** Model-on defaults: fine-grained ticks with a short real sleep between them. Both
         * ConversationLifecycle.OPERATION_TIMEOUT_SECONDS (45 simulated seconds) and
         * ResidentDirector's own 90-second decision-staleness check discard a model reply that
         * arrives after the simulated clock has moved on too far while the real network call was
         * in flight - so the simulated-seconds-per-real-second ratio must stay well under that
         * budget for a ~2-3s DeepSeek round trip. 8s/tick with an 800ms sleep is a 10:1 ratio. */
        public static RunConfig withModel(Path outDir, double days) {
            return new RunConfig("accelerated-model-run", "我", "Asia/Shanghai",
                Instant.parse("2026-01-01T00:00:00Z"), days, 8, true, 100000,
                1, outDir, 800, 25, 42, 20, true);
        }
    }

    public record RunResult(
        Path outDir, Instant simulatedFrom, Instant simulatedTo, long ticks,
        int diaryCount, int eventCount, int memoryCount, int dialogueTurnCount, int relationshipChangeCount,
        long totalModelCalls, long totalInputTokens, long totalOutputTokens, Duration wallTime
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
        var clock = new MutableClock(cfg.start());
        ResidentMind mind = cfg.modelEnabled() ? liveMind() : disabledMind();
        var director = new ResidentDirector(store, mind, clock, Math.max(1, cfg.dailyModelBudget()), usage);
        var service = new CompanionService(store, clock, director, usage);

        CompanionWorld seed = CompanionRules.join(cfg.worldId(), cfg.avatarName(), cfg.timezone(), cfg.start(), cfg.modelEnabled());
        store.seed(cfg.userId(), seed);

        var collector = new TimelineCollector();
        collector.capture(seed);

        List<ScriptedIntent> scripted = cfg.scriptedAvatarIntents() ? scriptedIntents() : List.of();

        long totalSeconds = Math.round(cfg.days() * 86400);
        int tickSeconds = Math.max(1, cfg.tickSeconds());
        long ticksInSpan = Math.max(1, totalSeconds / tickSeconds);
        long ticks = ticksInSpan + Math.max(0, cfg.drainTicks());
        long ticksPerDay = Math.max(1, 86400 / tickSeconds);

        Instant t = cfg.start();
        try {
            for (long i = 1; i <= ticks; i++) {
                t = t.plusSeconds(tickSeconds);
                clock.advanceTo(t);
                long elapsedSeconds = Duration.between(cfg.start(), t).getSeconds();
                fireDueScripted(service, cfg.userId(), scripted, elapsedSeconds);
                CompanionService.View view = service.advance(cfg.userId());
                collector.capture(view.world());
                if (i % ticksPerDay == 0) collector.capturePersonalitySnapshot(view.world());
                if (cfg.modelEnabled() && cfg.realPaceMillisPerTick() > 0) sleepQuietly(cfg.realPaceMillisPerTick());
            }
        } finally {
            if (cfg.modelEnabled()) director.close(); // shuts down the daemon worker pool; harmless if one last call is mid-flight
        }

        var sorted = TimelineExporter.sortedByTime(collector.entries());
        CompanionWorld finalWorld = store.read(cfg.userId());
        export(cfg, sorted, usage, finalWorld, t);

        Duration wall = Duration.ofNanos(System.nanoTime() - startNanos);
        return new RunResult(cfg.outDir(), cfg.start(), t, ticks,
            (int) sorted.stream().filter(e -> "diary".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "event".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "memory".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "dialogue".equals(e.get("kind"))).count(),
            (int) sorted.stream().filter(e -> "relationship".equals(e.get("kind"))).count(),
            usage.totalCalls(), usage.totalInputTokens(), usage.totalOutputTokens(), wall);
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

    private static void export(RunConfig cfg, List<Map<String, Object>> sorted, InMemoryModelUsage usage, CompanionWorld finalWorld, Instant finalInstant) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("worldId", cfg.worldId());
        manifest.put("avatarName", cfg.avatarName());
        manifest.put("timezone", cfg.timezone());
        manifest.put("simulatedStart", cfg.start().toString());
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

        var blind = TimelineExporter.buildBlindTest(sorted, cfg.blindTestSize(), cfg.blindTestSeed());
        TimelineExporter.writeJson(cfg.outDir().resolve("blind-test/quiz.json"), blind.quiz());
        TimelineExporter.writeBlindTestQuizText(cfg.outDir().resolve("blind-test/quiz.txt"), blind.quiz());
        TimelineExporter.writeJson(cfg.outDir().resolve("blind-test/answer-key.json"), blind.answerKey());
    }
}
