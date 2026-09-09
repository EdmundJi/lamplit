package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

/**
 * QwenHttpProvider already reads prompt_tokens/completion_tokens off every response; this class is
 * where that used to get thrown away (only result.json() made it out). These tests pin the fix:
 * the Metered entry points must carry the provider's usage numbers up, on all three call types, while
 * the pre-existing plain entry points keep working exactly as before for anything that still calls them.
 */
class QwenResidentMindUsageTest {
    private static final Instant NOW = Instant.parse("2026-09-08T06:00:00Z");

    @Test void decideMeteredCarriesPromptAndCompletionTokensUpFromTheProvider() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var context = context();
        String decisionJson = "{\"action\":\"observe\",\"place\":\""+context.self().place()+"\",\"targetId\":null,"
            + "\"reason\":\"先看看\",\"speech\":\"\",\"evidenceIds\":[\"mem-1\"],\"projectTitle\":null,\"objectKind\":null}";
        var mind = new QwenResidentMind(fakeProvider(decisionJson, 321, 64), json, "qwen", true);

        var metered = mind.decideMetered(context);

        assertThat(metered.usage()).isNotNull();
        assertThat(metered.usage().inputTokens()).isEqualTo(321);
        assertThat(metered.usage().outputTokens()).isEqualTo(64);
        assertThat(metered.value().action()).isEqualTo("observe");
        // The original, unmetered entry point is untouched behavior-wise.
        assertThat(mind.decide(context).action()).isEqualTo("observe");
    }

    @Test void generateTurnMeteredCarriesTokensAndSummarizeConversationMeteredDoesToo() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var context = context();
        String utteranceJson = json.writeValueAsString(
            new ConversationLifecycle.Utterance("我们去看看花吧。", false, "好奇", "none", null, List.of(), "🌼"));
        var turnMind = new QwenResidentMind(fakeProvider(utteranceJson, 88, 40), json, "qwen", true);
        var turnResult = turnMind.generateTurnMetered(
            new ResidentMind.DialogueRequest(context, "conversation-1", 0, "operation-1", "阿禾", "随口聊聊"));
        assertThat(turnResult.usage().inputTokens()).isEqualTo(88);
        assertThat(turnResult.usage().outputTokens()).isEqualTo(40);
        assertThat(turnResult.usage().model()).isEqualTo("fake-model");
        assertThat(turnResult.value().text()).isEqualTo("我们去看看花吧。");

        String recollectionJson = json.writeValueAsString(
            new ConversationLifecycle.Recollection("聊得挺好。", "踏实", List.of("mem-1")));
        var summaryMind = new QwenResidentMind(fakeProvider(recollectionJson, 50, 30), json, "qwen", true);
        var summaryResult = summaryMind.summarizeConversationMetered(
            new ResidentMind.SummaryRequest(context, "conversation-1", "阿禾", List.of(), List.of()));
        assertThat(summaryResult.usage().inputTokens()).isEqualTo(50);
        assertThat(summaryResult.usage().outputTokens()).isEqualTo(30);
        assertThat(summaryResult.usage().model()).isEqualTo("fake-model");
        assertThat(summaryResult.value().text()).isEqualTo("聊得挺好。");
    }

    private static ResidentMind.Context context() {
        var world = CompanionRules.join("usage-contract", "我", "Asia/Shanghai", NOW, true);
        var self = ResidentSimulation.actor(world, "artist");
        var state = ResidentSimulation.state(world, "artist");
        return new ResidentMind.Context(world.id, "artist", state.revision, 0, NOW, "14:00", "sunny", self,
            state.goal, state.mood, state.thought, state.energy, state.social, state.relationships,
            List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static QwenProvider fakeProvider(String resultJson, int inputTokens, int outputTokens) {
        return new QwenProvider() {
            @Override public StructuredResult generateStructured(StructuredPrompt prompt) {
                return new StructuredResult(resultJson, "fake-model", "fake-request", inputTokens, outputTokens, 5);
            }
            @Override public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) {
                throw new UnsupportedOperationException("not exercised in this test");
            }
            @Override public Classification classify(ClassificationPrompt prompt) {
                throw new UnsupportedOperationException("not exercised in this test");
            }
        };
    }
}
