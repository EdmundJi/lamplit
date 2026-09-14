package com.betterself.growth.town.companion.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command-line entry point for the accelerated headless town runner.
 *
 * Usage (from backend/):
 * <pre>
 *   # Rule-only smoke run, 3 simulated days, no model calls, no tokens spent:
 *   COMPANION_RUN=true COMPANION_RUN_DAYS=3 \
 *     ./mvnw test -Dtest=AcceleratedTownRunnerIT
 *
 *   # With the Qwen3.8-Flash primary (QWEN_* env vars in .env.local, no provider fallback):
 *   set -a; source ../.env.local; set +a
 *   COMPANION_RUN=true COMPANION_RUN_MODEL=true COMPANION_RUN_MODEL_PROVIDER=qwen COMPANION_RUN_DAYS=0.02 \
 *     ./mvnw test -Dtest=AcceleratedTownRunnerIT
 *
 *   # Custom output directory (default: backend/target/accelerated-run):
 *   COMPANION_RUN=true COMPANION_RUN_OUT=/tmp/my-run COMPANION_RUN_DAYS=1 \
 *     ./mvnw test -Dtest=AcceleratedTownRunnerIT
 *
 *   # Resume from a previous run's world-snapshot.json and simulate 2 more days:
 *   COMPANION_RUN=true COMPANION_RUN_MODEL=true COMPANION_RUN_DAYS=2 \
 *     COMPANION_RUN_RESUME_FROM=/tmp/my-run/world-snapshot.json COMPANION_RUN_OUT=/tmp/my-run-continued \
 *     ./mvnw test -Dtest=AcceleratedTownRunnerIT
 * </pre>
 *
 * Every knob is read from the environment, never a JVM system property, so credentials never
 * appear on a command line or in a surefire report. Disabled by default (like every other *IT in
 * this package) so a plain {@code ./mvnw clean test} never runs it and never spends a token.
 */
@EnabledIfEnvironmentVariable(named = "COMPANION_RUN", matches = "true")
class AcceleratedTownRunnerIT {
    @Test void acceleratedRunProducesAReadableTimeline() throws Exception {
        boolean modelEnabled = Boolean.parseBoolean(System.getenv().getOrDefault("COMPANION_RUN_MODEL", "false"));
        double days = Double.parseDouble(System.getenv().getOrDefault("COMPANION_RUN_DAYS", modelEnabled ? "0.02" : "3"));
        Path outDir = Path.of(System.getenv().getOrDefault("COMPANION_RUN_OUT", "target/accelerated-run"));
        String worldId = System.getenv().getOrDefault("COMPANION_RUN_WORLD_ID", modelEnabled ? "accelerated-model-run" : "accelerated-rule-run");
        String resumeFromEnv = System.getenv("COMPANION_RUN_RESUME_FROM");
        // Which hour the run starts at, so a short slice can be aimed at the part of the day the
        // question is about. The occasions this town now asks separately (closing up, locking the
        // door on the way out) only come round in the evening, and the default 08:00 start with a
        // quarter-day budget would simply never reach them - which would read as "nobody wanted to"
        // for exactly the reason the whole mechanism exists to stop.
        String startEnv = System.getenv("COMPANION_RUN_START");

        var base = modelEnabled ? AcceleratedTownRunner.RunConfig.withModel(outDir, days) : AcceleratedTownRunner.RunConfig.ruleOnly(outDir, days);
        var cfg = new AcceleratedTownRunner.RunConfig(
            worldId, base.avatarName(), base.timezone(),
            startEnv == null || startEnv.isBlank() ? base.start() : java.time.Instant.parse(startEnv), days, base.tickSeconds(),
            modelEnabled, Integer.parseInt(System.getenv().getOrDefault("COMPANION_RUN_MODEL_BUDGET", String.valueOf(base.dailyModelBudget()))),
            Integer.parseInt(System.getenv().getOrDefault("COMPANION_RUN_TOTAL_MODEL_BUDGET",String.valueOf(base.totalModelBudget()))),
            Long.parseLong(System.getenv().getOrDefault("COMPANION_RUN_INPUT_TOKEN_STOP",String.valueOf(base.inputTokenStop()))),base.userId(), outDir, base.realPaceMillisPerTick(),
            Integer.parseInt(System.getenv().getOrDefault("COMPANION_RUN_DRAIN_TICKS",String.valueOf(base.drainTicks()))), base.blindTestSeed(), base.blindTestSize(), base.scriptedAvatarIntents(),
            resumeFromEnv == null || resumeFromEnv.isBlank() ? null : Path.of(resumeFromEnv));

        String modelProvider=modelEnabled?AcceleratedTownRunner.modelProvider(System.getenv()):"none";
        String modelName=modelEnabled?AcceleratedTownRunner.modelName(System.getenv()):"none";
        System.out.println("[accelerated-run] starting: days=" + days + " modelEnabled=" + modelEnabled
            + " modelProvider="+modelProvider+" model="+modelName
            + " dailyBudget="+cfg.dailyModelBudget()+" totalBudget="+cfg.totalModelBudget()
            + " inputTokenStop="+cfg.inputTokenStop()
            + " outDir=" + outDir.toAbsolutePath() + " resumeFrom=" + cfg.resumeFrom());
        var result = AcceleratedTownRunner.run(cfg);
        System.out.println("[accelerated-run] done: " + result);

        assertThat(result.ticks()).isGreaterThan(0);
        assertThat(result.simulatedTo()).isAfter(result.simulatedFrom());
        assertThat(Files.exists(outDir.resolve("timeline.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("timeline.md"))).isTrue();
        assertThat(Files.exists(outDir.resolve("highlights.md"))).isTrue();
        assertThat(Files.exists(outDir.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("usage.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("model-calls.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("model-wire-requests.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("model-application-outcomes.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("world-snapshot.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("metrics.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("metrics.md"))).isTrue();
        // Quiz and answers live in separate directories - whoever takes the blind test is handed
        // only blind-test/quiz/, never blind-test/key/ (see docs/05-notes.md "手抄是个静默失败点").
        assertThat(Files.exists(outDir.resolve("blind-test/quiz/quiz.txt"))).isTrue();
        assertThat(Files.exists(outDir.resolve("blind-test/quiz/quiz.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("blind-test/key/answer-key.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("blind-test/key/pool-stats.json"))).isTrue();
        // Even a very short run should produce SOME readable content - a run that only ever
        // captures diary noise (no events, no memories, no dialogue) would be a sign the world
        // never actually lived, which is exactly what this tool exists to catch.
        assertThat(result.diaryCount() + result.eventCount() + result.memoryCount() + result.dialogueTurnCount()).isGreaterThan(0);
        if (modelEnabled) {
            // The allowance is per local calendar day, not per invocation. A run that starts at
            // 08:00 local and lasts exactly 24 hours touches the next date on its final tick, so it
            // may legitimately spend from two daily allowances (this happened in the first 25-person
            // probe: 60 + 60 calls). Count the inclusive dates the simulation actually covered.
            var zone=java.time.ZoneId.of(cfg.timezone());
            long coveredLocalDays=java.time.temporal.ChronoUnit.DAYS.between(
                result.simulatedFrom().atZone(zone).toLocalDate(),
                result.simulatedTo().atZone(zone).toLocalDate())+1;
            assertThat(result.totalModelCalls()).isBetween(1L,coveredLocalDays*cfg.dailyModelBudget());
            assertThat(result.logicalModelCallsStarted()).isGreaterThanOrEqualTo(result.totalModelCalls());
            assertThat(result.wireModelRequestsStarted()).isBetween(1L,(long)cfg.totalModelBudget());
            System.out.println("[accelerated-run] model usage: logicalCalls=" + result.totalModelCalls()
                + " logicalCallsStarted=" + result.logicalModelCallsStarted()
                + " wireRequestsStarted=" + result.wireModelRequestsStarted()
                + " inputTokens=" + result.totalInputTokens() + " outputTokens=" + result.totalOutputTokens()
                + " pacedTicks=" + result.pacedTicks() + " fastTicks=" + result.fastTicks());
        } else if(days>=2){
            // Structural acceptance for the second-version town. This is deliberately attached to
            // the opt-in runner rather than every unit-test build: it advances all 25 residents for
            // two whole days and then checks the exported, reloadable artifact rather than a seed.
            var snapshot=new ObjectMapper().findAndRegisterModules().readTree(outDir.resolve("world-snapshot.json").toFile());
            assertThat(snapshot.path("residents").size()).isEqualTo(25);
            assertThat(snapshot.path("residentStates").size()).isEqualTo(26); // 25 residents + autonomous-capable avatar state
            assertThat(snapshot.path("locations").size()).isGreaterThanOrEqualTo(23);
            assertThat(snapshot.path("rooms").size()).isGreaterThanOrEqualTo(66);
            assertThat(snapshot.path("positions").size()).isGreaterThanOrEqualTo(108);
            var roomIds=new java.util.HashSet<String>();snapshot.path("rooms").forEach(room->roomIds.add(room.path("id").asText()));
            snapshot.path("residentStates").forEach(state->assertThat(state.path("roomId").asText(null)).as(state.path("id").asText()).isIn(roomIds));
            snapshot.path("positions").forEach(position->assertThat(position.path("roomId").asText(null)).as(position.path("id").asText()).isIn(roomIds));
            snapshot.path("objects").forEach(object->assertThat(object.path("roomId").asText(null)).as(object.path("id").asText()).isIn(roomIds));
        }
    }
}
