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

    @Test
    @DisplayName("许诺那一问：两侧都在，而且说清楚了时间只由一个地方定")
    void theQuestionAboutFixingATimeLeansNeitherWayAndKeepsOneClock() {
        String prompt = promptFor(mind -> mind.promiseOffer(new ResidentMind.PromiseOfferRequest(
                context(),
                List.of(new ResidentMind.ActorView("fixer", "周野", "修东西的", "cafe", "make", null)),
                List.of())),
            "{\"toId\":null,\"what\":null,\"place\":null,\"inHours\":null,\"evidenceIds\":[]}");

        assertThat(prompt).as("不说也很正常").contains("不想就不说，两种都很正常");
        assertThat(prompt).as("而且明说了「没有」最常见").contains("最常见的答案");
        assertThat(prompt).as("但「万一做不到」不能变成不说的理由").contains("万一做不到呢");
        assertThat(prompt).as("那张需要人手的单子不是让他从里面挑一件来许诺")
                .contains("不是让你从里面挑一件来许诺");
        // 半天的模型跑里，周野说的是"明早9点"，而 inHours 把到点算成了当天 16:57，人在不在那儿
        // 因此成了一笔糊涂账，三条承诺全部结清成 did_not_come。
        assertThat(prompt).as("时间只能由一个地方定").contains("里面不要出现任何时间");
        assertThat(prompt).as("写做的事，不写说的话").contains("不要写成你对他说的话");
    }

    @Test
    @DisplayName("时机问句：做与不做都摆在台面上，而且「不做」不需要理由")
    void theOccasionQuestionLeansNeitherWay() {
        String prompt = promptFor(mind -> mind.consider(new ResidentMind.ConsiderRequest(
                context(), "po-1", "lock_door", "店里现在只剩你一个，门还开着。", "要不要把门锁上再走？",
                "锁上：这段时间只有你自己进得来，别人会被关在外面",
                "不锁：门开着就走，这同样是正常的，不需要理由", "cafe")),
            "{\"choice\":\"none\",\"reason\":\"没必要\",\"speech\":null,\"evidenceIds\":[]}");

        assertThat(prompt).as("两个答案都摆在台面上").contains("两个都是正常答案");
        assertThat(prompt).as("不做不需要理由").contains("none不需要理由");
        // The exact failure mode this whole class exists for, in its newest disguise: a question asked
        // at the right moment still dies if being asked at all reads as a hint that something is
        // expected. 470 offers and 0 takers was our timing; this line is the other half.
        assertThat(prompt).as("被问到本身不等于应该做点什么").contains("不要因为有人问了你，就觉得应该做点什么");
        assertThat(prompt).as("但也不能把「不做」写成更稳妥的答案").contains("只有当你自己此刻确实想这么做");
    }

    @Test
    @DisplayName("react 的第四个答案 invite：想叫和不想叫都留在提示词里")
    void theReactPromptLeansNeitherWayOnInvite() {
        String prompt = promptFor(mind -> mind.react(new ResidentMind.ReactRequest(
                context(), "pe-1", "fixer", "周野", "make", "cafe",
                List.of("greet", "join", "invite", "none"), "留一盏灯的读书小聚")),
            "{\"reaction\":\"none\",\"reason\":\"\",\"evidenceIds\":[]}");

        assertThat(prompt).as("想叫这一侧").contains("想叫就叫");
        assertThat(prompt).as("不想叫也不需要理由这一侧").contains("不想叫也不需要理由");
    }

    @Test
    @DisplayName("普通决定里的 none：和别的选项完全平等，不是认输")
    void doingNothingInParticularIsOfferedAsAnEqualAnswer() {
        String prompt = promptFor(mind -> mind.decide(context()),
            "{\"action\":\"observe\",\"place\":\"cafe\",\"targetId\":null,\"reason\":\"看看四周\",\"speech\":\"\",\"evidenceIds\":[]}");

        assertThat(prompt).as("「没什么特别想做的」得有自己的词").contains("none表示");
        assertThat(prompt).as("而且是平等的一项，不是退而求其次").contains("它和其他选项完全平等");
        assertThat(prompt).as("有时机的动作不该再出现在这份菜单里").contains("到了那个时刻会单独问你");
    }
}
