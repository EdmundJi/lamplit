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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
         * budget. Measured model round trips are a few seconds; 8s/tick with a 600ms
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
        LiveMind live = cfg.modelEnabled() ? liveMind(System.getenv()) : null;
        ResidentMind mind = live == null ? disabledMind() : live.mind();
        var director = new ResidentDirector(store, mind, clock, Math.max(1, cfg.dailyModelBudget()), usage);
        var service = new CompanionService(store, clock, director, usage);

        store.seed(cfg.userId(), seed);

        var collector = new TimelineCollector();
        collector.capture(seed);
        collector.capturePersonalitySnapshot(seed); // a true t=0 baseline for THIS run, resumed or fresh
        List<Map<String,Object>> applicationOutcomes=new ArrayList<>();String lastModelStatus=seed.modelStatus;

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
                if(view.world()!=null&&!java.util.Objects.equals(lastModelStatus,view.world().modelStatus)){
                    lastModelStatus=view.world().modelStatus;
                    if(lastModelStatus!=null&&!lastModelStatus.contains("正在想")){
                        Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("at",t.toString());outcome.put("status",lastModelStatus);
                        outcome.put("outcome",lastModelStatus.contains("过时")?"rejected":lastModelStatus.contains("暂时")?"failed":"applied");applicationOutcomes.add(outcome);
                    }
                }
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
                        // consider() dispatches on its own executor. Give that worker a small bounded
                        // wall-clock turn before advancing simulated time again; otherwise a very
                        // short run can finish all ticks and close the director before the first
                        // reservation ever starts, yielding a misleading "model-on, zero calls" run.
                        sleepQuietly(Math.min(25,Math.max(10,cfg.realPaceMillisPerTick()/12)));
                        fastTicks++;
                    }
                }
            }
        } finally {
            if (cfg.modelEnabled()) director.close(); // shuts down the daemon worker pool; harmless if one last call is mid-flight
        }

        var sorted = TimelineExporter.sortedByTime(collector.entries());
        CompanionWorld finalWorld = store.read(cfg.userId());
        export(cfg, sorted, usage, finalWorld, runStart, t, collector, live, applicationOutcomes);

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

    /** One explicitly selected provider per accelerated run. There is intentionally no fallback in
     * this harness: a Qwen evaluation must fail visibly if Qwen fails, rather than producing a
     * plausible-looking report whose dialogue was silently generated by DeepSeek. */
    private record LiveMind(ResidentMind mind,String provider,String model,List<Map<String,Object>> calls) {}

    private static LiveMind liveMind(Map<String,String> env) {
        String selected=modelProvider(env);
        String baseUrl=requireProviderEnv(env,selected,"BASE_URL");
        String apiKey=requireProviderEnv(env,selected,"API_KEY");
        String model=requireProviderEnv(env,selected,"MODEL");
        Duration timeout=providerDuration(env,selected,"TIMEOUT","qwen".equals(selected)?Duration.ofSeconds(60):Duration.ofSeconds(120));
        Duration streamTimeout=providerDuration(env,selected,"STREAM_TIMEOUT","qwen".equals(selected)?Duration.ofSeconds(70):Duration.ofSeconds(130));
        String jsonModeValue=providerEnv(env,selected,"JSON_MODE");
        boolean jsonMode=jsonModeValue==null||jsonModeValue.isBlank()||Boolean.parseBoolean(jsonModeValue);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var provider = new QwenHttpProvider(json,baseUrl,apiKey,model,timeout,streamTimeout,jsonMode,selected);
        var delegate = new QwenResidentMind(provider,json,"qwen",true,selected,false,false,false);
        List<Map<String,Object>> calls=Collections.synchronizedList(new ArrayList<>());
        return new LiveMind(new RecordingMind(delegate,calls),selected,model,calls);
    }

    /** Captures the canonical input and structured result only. Credentials and HTTP headers stay
     * inside QwenHttpProvider and cannot enter the exported audit file. */
    private static final class RecordingMind implements ResidentMind {
        private final ResidentMind delegate;private final List<Map<String,Object>> calls;
        RecordingMind(ResidentMind delegate,List<Map<String,Object>> calls){this.delegate=delegate;this.calls=calls;}
        public boolean enabled(){return delegate.enabled();}
        public Decision decide(Context context){return decideMetered(context).value();}
        public Result<Decision> decideMetered(Context context){return capture("decision",context,()->delegate.decideMetered(context));}
        public com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance generateTurn(DialogueRequest request){return generateTurnMetered(request).value();}
        public Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance> generateTurnMetered(DialogueRequest request){
            Map<String,Object> input=new LinkedHashMap<>();input.put("perspective",request.perspective());input.put("partnerName",request.partnerName());input.put("topicTitle",request.topicTitle());
            return capture("turn",input,()->delegate.generateTurnMetered(request));
        }
        public ReactDraft react(ReactRequest request){return reactMetered(request).value();}
        public Result<ReactDraft> reactMetered(ReactRequest request){
            Map<String,Object> input=new LinkedHashMap<>();input.put("residentId",request.perspective().residentId());
            input.put("otherName",request.otherName());input.put("otherActivity",request.otherActivity());input.put("place",request.place());
            return capture("react",input,()->delegate.reactMetered(request));
        }
        public DayPlanDraft planDay(DayPlanRequest request){return planDayMetered(request).value();}
        public Result<DayPlanDraft> planDayMetered(DayPlanRequest request){
            // Forwarded like every other call, and for a specific reason: ResidentMind.planDay has a
            // default implementation that throws, so a decorator that simply forgets a method does not
            // fail to compile - it silently disables the capability for everything behind it. This
            // decorator forgot, and a whole measured day ran with every resident's day plan recorded
            // as "unavailable". Not one line of it looked wrong.
            Map<String,Object> input=new LinkedHashMap<>();input.put("residentId",request.perspective().residentId());
            return capture("dayplan",input,()->delegate.planDayMetered(request));
        }
        public com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){return summarizeConversationMetered(request).value();}
        public Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection> summarizeConversationMetered(SummaryRequest request){
            Map<String,Object> input=new LinkedHashMap<>();input.put("perspective",request.perspective());input.put("partnerName",request.partnerName());input.put("transcript",ResidentMind.turnViews(request.transcript()));input.put("conversationMemories",request.conversationMemories());
            return capture("summary",input,()->delegate.summarizeConversationMetered(request));
        }
        private <T>Result<T> capture(String type,Object input,java.util.function.Supplier<Result<T>> call){
            Map<String,Object> row=new LinkedHashMap<>();row.put("callType",type);row.put("input",input);
            try{Result<T> result=call.get();row.put("status","generated");row.put("output",result.value());row.put("usage",result.usage());calls.add(row);return result;}
            catch(RuntimeException failure){row.put("status","failed");row.put("error",failure.getClass().getSimpleName());calls.add(row);throw failure;}
        }
    }

    static String modelProvider(Map<String,String> env) {
        String selected=env.getOrDefault("COMPANION_RUN_MODEL_PROVIDER","qwen").trim().toLowerCase(java.util.Locale.ROOT);
        if("qwen3".equals(selected))selected="qwen"; // old runner value, same Qwen supplier
        if(!Set.of("qwen","deepseek").contains(selected))throw new IllegalStateException("COMPANION_RUN_MODEL_PROVIDER must be qwen or deepseek");
        return selected;
    }

    static String modelName(Map<String,String> env) {
        return requireProviderEnv(env,modelProvider(env),"MODEL");
    }

    private static String providerEnv(Map<String,String> env,String provider,String suffix) {
        String value=env.get(("qwen".equals(provider)?"QWEN":"DEEPSEEK")+"_"+suffix);
        if((value==null||value.isBlank())&&"qwen".equals(provider))value=env.get("QWEN3_"+suffix);
        return value;
    }

    private static String requireProviderEnv(Map<String,String> env,String provider,String suffix) {
        String value=providerEnv(env,provider,suffix);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Model mode requires the selected "+provider+" "+suffix+" in the environment (see .env.local) - refusing to guess a credential.");
        return value;
    }

    private static Duration providerDuration(Map<String,String> env,String provider,String suffix, Duration fallback) {
        String value=providerEnv(env,provider,suffix);
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
                                CompanionWorld finalWorld, Instant runStart, Instant finalInstant, TimelineCollector collector,
                                LiveMind live,List<Map<String,Object>> applicationOutcomes) throws IOException {
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
        manifest.put("modelProvider", live == null ? null : live.provider());
        manifest.put("model", live == null ? null : live.model());
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
        TimelineExporter.writeJson(cfg.outDir().resolve("model-calls.json"),live==null?List.of():List.copyOf(live.calls()));
        TimelineExporter.writeJson(cfg.outDir().resolve("model-application-outcomes.json"),applicationOutcomes);

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
