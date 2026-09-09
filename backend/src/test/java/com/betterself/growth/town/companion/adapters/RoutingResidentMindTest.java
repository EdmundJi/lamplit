package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/**
 * RoutingResidentMind owns two things: per-call-type provider order (routing) and "the next entry in
 * that same order is the failover path" (degrade). Both are exercised here without any network call -
 * each provider is a fake that either succeeds or always throws, standing in for "configured and
 * healthy" vs "down or not configured" (UnavailableModelProvider throws the same way).
 */
class RoutingResidentMindTest {
    private static final Instant NOW = Instant.parse("2026-09-08T06:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test void decisionRoutesToQwenFirstByDefault() {
        var qwen = residentMind("qwen", decisionJson(), 10, 5);
        var deepseek = residentMind("deepseek", decisionJson(), 999, 999);
        var routing = routing(deepseek, qwen, "qwen,deepseek", "qwen,deepseek", "qwen,deepseek");

        var result = routing.decideMetered(context());

        assertThat(result.usage().provider()).isEqualTo("qwen");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
    }

    @Test void turnAndSummaryRouteToQwenFirstByDefault() throws Exception {
        var utteranceJson = JSON.writeValueAsString(
            new ConversationLifecycle.Utterance("路过看看。", false, "平静", "none", null, List.of(), "🙂"));
        var deepseek = residentMind("deepseek", utteranceJson, 40, 20);
        var qwen = residentMind("qwen", utteranceJson, 12, 6);
        var routing = routing(deepseek, qwen, "qwen,deepseek", "qwen,deepseek", "qwen,deepseek");

        var result = routing.generateTurnMetered(new ResidentMind.DialogueRequest(context(), "c1", 0, "op1", "阿禾", "随口聊聊"));

        assertThat(result.usage().provider()).isEqualTo("qwen");
        assertThat(result.value().text()).isEqualTo("路过看看。");

        String summaryJson=JSON.writeValueAsString(new ConversationLifecycle.Recollection("记得路过时说了一句。","平静",List.of()));
        var summaryRouting=routing(residentMind("deepseek",summaryJson,40,20),residentMind("qwen",summaryJson,13,7),"qwen,deepseek","qwen,deepseek","qwen,deepseek");
        var summary=summaryRouting.summarizeConversationMetered(new ResidentMind.SummaryRequest(context(),"c1","阿禾",List.of(),List.of()));
        assertThat(summary.usage().provider()).isEqualTo("qwen");
        assertThat(summary.value().text()).contains("路过");
    }

    @Test void fallsOverToTheNextProviderWhenTheFirstOneFails() {
        var failing = failingMind();
        var healthy = residentMind("deepseek", decisionJson(), 30, 15);
        var routing = routing(healthy, failing, "qwen,deepseek", "qwen,deepseek", "qwen,deepseek");

        var result = routing.decideMetered(context());

        assertThat(result.usage().provider()).isEqualTo("deepseek");
        assertThat(result.value().action()).isEqualTo("observe");
    }

    @Test void propagatesTheFailureWhenEveryProviderInTheRouteFails() {
        var routing = routing(failingMind(), failingMind(), "qwen,deepseek", "qwen,deepseek", "qwen,deepseek");

        assertThatThrownBy(() -> routing.decideMetered(context()))
            .isInstanceOf(RuntimeException.class);
    }

    @Test void qwenOnlyEvaluationNeverSilentlyUsesAHealthyDeepseekFallback() {
        var routing=routing(residentMind("deepseek",decisionJson(),20,10),failingMind(),"qwen","qwen","qwen");

        assertThatThrownBy(()->routing.decideMetered(context())).isInstanceOf(RuntimeException.class);
    }

    @Test void historicalQwen3RouteNameStillSelectsTheQwenMindButReportsCanonicalSupplier() {
        var routing=routing(failingMind(),residentMind("qwen",decisionJson(),10,5),"qwen3","qwen3","qwen3");
        assertThat(routing.decideMetered(context()).usage().provider()).isEqualTo("qwen");
    }

    @Test void unknownProviderNamesInTheRouteAreSkippedNotFatal() {
        var healthy = residentMind("deepseek", decisionJson(), 12, 6);
        var routing = routing(healthy, failingMind(), "not-a-real-provider,deepseek", "deepseek", "deepseek");

        var result = routing.decideMetered(context());

        assertThat(result.usage().provider()).isEqualTo("deepseek");
    }

    @Test void enabledOnlyWhenTheGeneralProviderIsQwenAndTheTownToggleIsOn() {
        var mind = residentMind("deepseek", decisionJson(), 1, 1);
        assertThat(new RoutingResidentMind(mind, mind, "qwen", true, "deepseek", "deepseek", "deepseek", "deepseek", "deepseek", "deepseek", "deepseek").enabled()).isTrue();
        assertThat(new RoutingResidentMind(mind, mind, "mock", true, "deepseek", "deepseek", "deepseek", "deepseek", "deepseek", "deepseek", "deepseek").enabled()).isFalse();
        assertThat(new RoutingResidentMind(mind, mind, "qwen", false, "deepseek", "deepseek", "deepseek", "deepseek", "deepseek", "deepseek", "deepseek").enabled()).isFalse();
    }

    private static RoutingResidentMind routing(QwenResidentMind deepseek, QwenResidentMind qwen, String decisionRoute, String turnRoute, String summaryRoute) {
        return new RoutingResidentMind(deepseek, qwen, "qwen", true, decisionRoute, turnRoute, summaryRoute, decisionRoute, decisionRoute, decisionRoute, decisionRoute);
    }

    private static String decisionJson() {
        var c = context();
        return "{\"action\":\"observe\",\"place\":\"" + c.self().place() + "\",\"targetId\":null,"
            + "\"reason\":\"先看看\",\"speech\":\"\",\"evidenceIds\":[\"mem-1\"],\"projectTitle\":null,\"objectKind\":null}";
    }

    private static QwenResidentMind residentMind(String providerCode, String resultJson, int inputTokens, int outputTokens) {
        return new QwenResidentMind(fakeProvider(resultJson, inputTokens, outputTokens), JSON, "qwen", true, providerCode);
    }

    /** Stands in for a down provider or UnavailableModelProvider: every call throws the same ApiException shape. */
    private static QwenResidentMind failingMind() {
        QwenProvider provider = new QwenProvider() {
            @Override public StructuredResult generateStructured(StructuredPrompt prompt) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE", "down");
            }
            @Override public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) { throw new UnsupportedOperationException(); }
            @Override public Classification classify(ClassificationPrompt prompt) { throw new UnsupportedOperationException(); }
        };
        return new QwenResidentMind(provider, JSON, "qwen", true, "failing");
    }

    private static ResidentMind.Context context() {
        var world = CompanionRules.join("routing-contract", "我", "Asia/Shanghai", NOW, true);
        var self = ResidentSimulation.actor(world, "artist");
        var state = ResidentSimulation.state(world, "artist");
        return new ResidentMind.Context(world.id, "artist", state.revision, 0, NOW, "14:00", "sunny", self,
            state.goal, state.mood, state.thought, state.energy, state.social, state.relationships,
            world.memories.stream().filter(m -> m.ownerId().equals("artist")).toList(), List.of(), List.of(), List.of(), List.of());
    }

    private static QwenProvider fakeProvider(String resultJson, int inputTokens, int outputTokens) {
        AtomicInteger calls = new AtomicInteger();
        return new QwenProvider() {
            @Override public StructuredResult generateStructured(StructuredPrompt prompt) {
                calls.incrementAndGet();
                return new StructuredResult(resultJson, "fake-model", "fake-request", inputTokens, outputTokens, 5);
            }
            @Override public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) { throw new UnsupportedOperationException(); }
            @Override public Classification classify(ClassificationPrompt prompt) { throw new UnsupportedOperationException(); }
        };
    }
}
