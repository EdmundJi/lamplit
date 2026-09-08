package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live contract probe for BOTH companion model providers - deepseek (the existing "QWEN_"-
 * named slot) and qwen3 (the new one). Credentials stay in the environment, never JVM properties,
 * arguments, or logs; only latency, token counts, and the model's own (non-secret) reply text are
 * printed. This is the "did we actually call both providers for real" evidence for docs/05-notes.md -
 * run it locally with COMPANION_LIVE_MODEL_TEST=true and the relevant *_BASE_URL/*_API_KEY/*_MODEL
 * variables exported (see .env.local), never in CI.
 */
@EnabledIfEnvironmentVariable(named = "COMPANION_LIVE_MODEL_TEST", matches = "true")
class CompanionModelLiveIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-09-08T06:00:00Z");

    @Test void deepseekAnswersADecisionCallForReal() throws Exception {
        String baseUrl = firstNonBlank(System.getenv("DEEPSEEK_BASE_URL"), System.getenv("QWEN_BASE_URL"));
        String apiKey = firstNonBlank(System.getenv("DEEPSEEK_API_KEY"), System.getenv("QWEN_API_KEY"));
        String model = firstNonBlank(System.getenv("DEEPSEEK_MODEL"), System.getenv("QWEN_MODEL"));
        assumeConfigured("deepseek", baseUrl, apiKey, model);

        var provider = new QwenHttpProvider(JSON, baseUrl, apiKey, model, Duration.ofSeconds(35), Duration.ofSeconds(35), true, "deepseek");
        // decisionThinking=false: this is the default route config's actual runtime setting for the
        // decision call type, applied uniformly to whichever provider serves it - see CompanionModelConfig.
        var mind = new QwenResidentMind(provider, JSON, "qwen", true, "deepseek", false, null, null);

        report("deepseek", model, timedDecision(mind));
    }

    @Test void qwen3AnswersADecisionCallForReal() throws Exception {
        String baseUrl = System.getenv("QWEN3_BASE_URL");
        String apiKey = System.getenv("QWEN3_API_KEY");
        String model = System.getenv("QWEN3_MODEL");
        assumeConfigured("qwen3", baseUrl, apiKey, model);

        var provider = new QwenHttpProvider(JSON, baseUrl, apiKey, model, Duration.ofSeconds(35), Duration.ofSeconds(35), true, "qwen");
        var mind = new QwenResidentMind(provider, JSON, "qwen", true, "qwen3", false, null, null);

        report("qwen3", model, timedDecision(mind));
    }

    private record Timed(ResidentMind.Result<ResidentMind.Decision> result, long millis) {}

    private Timed timedDecision(QwenResidentMind mind) {
        long started = System.nanoTime();
        var result = mind.decideMetered(context());
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.value().action()).isNotBlank();
        return new Timed(result, millis);
    }

    private void report(String provider, String model, Timed timed) throws Exception {
        System.out.println("Live companion model contract: " + JSON.writeValueAsString(Map.of(
            "provider", provider,
            "model", model,
            "latencyMs", timed.millis(),
            "inputTokens", timed.result().usage() == null ? -1 : timed.result().usage().inputTokens(),
            "outputTokens", timed.result().usage() == null ? -1 : timed.result().usage().outputTokens(),
            "action", timed.result().value().action(),
            "reason", timed.result().value().reason()
        )));
    }

    private static void assumeConfigured(String name, String baseUrl, String apiKey, String model) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank(),
            name + " credentials not present in the environment - skipping this half of the live probe"
        );
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static ResidentMind.Context context() {
        var world = CompanionRules.join("live-model-contract", "测试住客", "Asia/Shanghai", NOW, true);
        var self = ResidentSimulation.actor(world, "artist");
        var state = ResidentSimulation.state(world, "artist");
        return new ResidentMind.Context(world.id, "artist", state.revision, 0, NOW, "14:00", "sunny", self,
            state.goal, state.mood, state.thought, state.energy, state.social, state.relationships,
            world.memories.stream().filter(m -> m.ownerId().equals("artist")).toList(), List.of(), List.of(), List.of(), List.of());
    }
}
