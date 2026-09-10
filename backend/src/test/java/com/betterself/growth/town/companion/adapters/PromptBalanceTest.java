package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single most repeated defect in this project: writing a guardrail that leans one way and killing
 * the whole path with it. Four times in one round - the react prompt, {@code continue}, ten lines
 * across four prompts that all pushed away from collaboration and none toward it, and the venture
 * prompt, which put 「大多数时候答案是没有」 directly after telling the resident there was nothing left
 * to do together. Every one of them compiled, every one of them read as prudence, and every one of
 * them produced a number that was zero.
 *
 * <p>So: for every question that offers a resident a genuine choice, both directions have to stay in
 * the prompt. These assertions are deliberately literal - a future edit that quietly removes one side
 * fails here rather than three days and one paid run later.
 */
class PromptBalanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T06:00:00Z");

    /** Runs one call against a provider that answers nothing and simply keeps the prompt it was given. */
    private static String promptFor(Consumer<QwenResidentMind> call, String resultJson) {
        AtomicReference<String> captured = new AtomicReference<>();
        QwenProvider provider = new QwenProvider() {
            @Override public StructuredResult generateStructured(StructuredPrompt prompt) {
                captured.set(prompt.instruction());
                return new StructuredResult(resultJson, "fake-model", "fake-request", 1, 1, 0);
            }
            @Override public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) { throw new UnsupportedOperationException(); }
            @Override public Classification classify(ClassificationPrompt prompt) { throw new UnsupportedOperationException(); }
        };
        call.accept(new QwenResidentMind(provider, JSON, "qwen", true, "qwen"));
        return captured.get();
    }

    private static ResidentMind.Context context() {
        var world = CompanionRules.join("prompt-balance", "我", "Asia/Shanghai", NOW, true);
        var self = ResidentSimulation.actor(world, "artist");
        var state = ResidentSimulation.state(world, "artist");
        return new ResidentMind.Context(world.id, "artist", state.revision, 0, NOW, "14:00", "sunny", self,
            state.goal, state.mood, state.thought, state.energy, state.social, state.relationships,
            world.memories.stream().filter(m -> m.ownerId().equals("artist")).toList(),
            List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("承诺到点之后那一问：两个方向都在，而且「没什么想法」和「说出来」都容易给")
    void theQuestionAfterAPromiseComesDueLeansNeitherWay() {
        String prompt = promptFor(mind -> mind.promiseSettled(new ResidentMind.PromiseSettledRequest(
                context(),
                new ResidentMind.PromiseView("pr-1", "fixer", "周野", "把长桌腿修一下", "cafe",
                        "2026-01-01T10:00:00Z", "did_not_come", "promised_to"),
                List.of())),
            "{\"text\":\"\",\"supersedesKey\":null,\"evidenceIds\":[]}");

        assertThat(prompt).as("给了不介意这一侧").contains("可以觉得没什么");
        assertThat(prompt).as("也给了介意这一侧").contains("可以不痛快");
        assertThat(prompt).as("替对方想理由这一侧").contains("可以替他想到一个理由");
        assertThat(prompt).as("「什么都没想」必须是好给的答案").contains("什么想法都没有");
        assertThat(prompt).as("但不能反过来把沉默说成正确答案").contains("不要因为被问到了就凑一句出来");
        assertThat(prompt).as("也不能让「该大度」把话压回去").contains("咽回去");
        assertThat(prompt).as("规则不替他判断这算什么").contains("没有人替你判断这算什么");
    }

    @Test
    @DisplayName("他来了也一样问——只在没来的时候问，等于我们把这个镇子调成专出怨气")
    void theSameQuestionIsAskedWhenTheyDidTurnUp() {
        String prompt = promptFor(mind -> mind.promiseSettled(new ResidentMind.PromiseSettledRequest(
                context(),
                new ResidentMind.PromiseView("pr-2", "fixer", "周野", "把长桌腿修一下", "cafe",
                        "2026-01-01T10:00:00Z", "came", "promised_to"),
                List.of())),
            "{\"text\":\"\",\"supersedesKey\":null,\"evidenceIds\":[]}");

        assertThat(prompt).as("兑现了这一侧也有话可说").contains("记住他真的来了");
        assertThat(prompt).contains("可以觉得他做到了是理所当然的");
    }

    @Test
    @DisplayName("venture 那一问：留空和写出来都不许被说成正确答案")
    void theVentureQuestionStillCarriesBothHalves() {
        String prompt = promptFor(mind -> mind.venture(new ResidentMind.VentureRequest(context(), List.of())),
            "{\"title\":null,\"place\":null,\"objectKind\":null,\"reason\":null,\"evidenceIds\":[]}");

        assertThat(prompt).as("想不出来是真话").contains("想不出来也没关系");
        assertThat(prompt).as("但谦虚不是理由——这半句是修回来的那次加的").contains("也别因为觉得");
    }
}
