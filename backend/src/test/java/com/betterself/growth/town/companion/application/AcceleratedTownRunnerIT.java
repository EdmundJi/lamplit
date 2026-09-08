package com.betterself.growth.town.companion.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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
 *   # With the real model (DeepSeek v4 flash via the QWEN_* env vars already in .env.local):
 *   set -a; source ../.env.local; set +a
 *   COMPANION_RUN=true COMPANION_RUN_MODEL=true COMPANION_RUN_DAYS=0.02 \
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

        var base = modelEnabled ? AcceleratedTownRunner.RunConfig.withModel(outDir, days) : AcceleratedTownRunner.RunConfig.ruleOnly(outDir, days);
        var cfg = new AcceleratedTownRunner.RunConfig(
            worldId, base.avatarName(), base.timezone(), base.start(), days, base.tickSeconds(),
            modelEnabled, base.dailyModelBudget(), base.userId(), outDir, base.realPaceMillisPerTick(),
            base.drainTicks(), base.blindTestSeed(), base.blindTestSize(), base.scriptedAvatarIntents(),
            resumeFromEnv == null || resumeFromEnv.isBlank() ? null : Path.of(resumeFromEnv));

        System.out.println("[accelerated-run] starting: days=" + days + " modelEnabled=" + modelEnabled
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
            System.out.println("[accelerated-run] model usage: calls=" + result.totalModelCalls()
                + " inputTokens=" + result.totalInputTokens() + " outputTokens=" + result.totalOutputTokens()
                + " pacedTicks=" + result.pacedTicks() + " fastTicks=" + result.fastTicks());
        }
    }
}
