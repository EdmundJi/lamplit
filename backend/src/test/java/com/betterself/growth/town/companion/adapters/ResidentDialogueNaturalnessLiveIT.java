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

/** Small opt-in Qwen probe for human-scale dialogue. It uses synthetic town state and never logs credentials. */
@EnabledIfEnvironmentVariable(named = "COMPANION_LIVE_MODEL_TEST", matches = "true")
class ResidentDialogueNaturalnessLiveIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-09-09T07:00:00Z");

    @Test void answersConcreteTalkAndDistinguishesLimitedHelpFromTakingOver() throws Exception {
        var provider = new QwenHttpProvider(JSON, System.getenv("QWEN_BASE_URL"), System.getenv("QWEN_API_KEY"),
            System.getenv("QWEN_MODEL"), Duration.ofSeconds(35), Duration.ofSeconds(35), true, "qwen");
        var mind = new QwenResidentMind(provider, JSON, "qwen", true, "qwen", false, false, false);
        var world = CompanionRules.join("natural-dialogue-probe", "测试住客", "Asia/Shanghai", NOW, true);

        var ordinary = ask(mind, world, "student", "owner", "阿禾", "窗边那桌有人了。吧台边还空着。", "随口聊聊", List.of());
        var limitedHelp = ask(mind, world, "owner", "gardener", "青叔", "下午忙起来的话，我替你看十分钟吧？", "眼前的生活和工作",
            List.of(new ResidentMind.WorkArrangementView("work-limited", "assist", "cafe", "gardener", "gardener", "proposed", "替十分钟吧台", NOW.toString(), null, null)));
        var takeover = ask(mind, world, "artist", "owner", "阿禾", "我不想再开这家店了。你愿意正式接手吗？", "眼前的生活和工作",
            List.of(new ResidentMind.WorkArrangementView("work-takeover", "takeover", "cafe", "owner", "artist", "proposed", "正式接手咖啡馆", NOW.toString(), null, null)));
        var heard = new CompanionWorld.Memory("memory-ordinary", "student", "owner", "heard", NOW,
            "阿禾当面说：“窗边那桌有人了。吧台边还空着。”", "life", List.of(), 6);
        var summary = mind.summarizeConversationMetered(new ResidentMind.SummaryRequest(
            context(world, "student", "owner", List.of(heard), List.of(new CompanionWorld.Turn("owner", "窗边那桌有人了。吧台边还空着。", NOW, "model")), List.of()),
            "summary-probe", "阿禾", List.of(new CompanionWorld.Turn("owner", "窗边那桌有人了。吧台边还空着。", NOW, "model")), List.of(ResidentMind.memoryView(heard))));

        assertThat(ordinary.value().text()).isNotBlank();
        assertThat(limitedHelp.value().workAction()).isIn("accept_work", "reject_work");
        assertThat(limitedHelp.value().workTarget()).isEqualTo("work-limited");
        assertThat(takeover.value().workAction()).isIn("none", "accept_work", "reject_work");
        assertThat(summary.value().text()).isNotBlank().hasSizeLessThanOrEqualTo(80);
        var usages=List.of(ordinary.usage(),limitedHelp.usage(),takeover.usage(),summary.usage());
        assertThat(usages).allSatisfy(usage->{
            assertThat(usage.provider()).isEqualTo("qwen");
            // Whatever QWEN_MODEL actually points at - this probe is for comparing models, so pinning
            // one by name turns "I switched to a cheaper model" into a test failure instead of a
            // measurement.
            assertThat(usage.model()).isEqualTo(System.getenv("QWEN_MODEL"));
            assertThat(usage.reasoningContentPresent()).isFalse();
            assertThat(usage.reasoningTokens()).isZero();
        });
        System.out.println("Qwen natural dialogue probe: " + JSON.writeValueAsString(Map.of(
            "actualModel",ordinary.usage().model(),
            "reasoningTokens",usages.stream().mapToInt(ResidentMind.Usage::reasoningTokens).sum(),
            "ordinary", ordinary.value().text(),
            "limitedHelp", limitedHelp.value().text(),
            "limitedHelpAction", limitedHelp.value().workAction(),
            "takeover", takeover.value().text(),
            "takeoverAction", takeover.value().workAction(),
            "ordinarySummary", summary.value().text()
        )));
    }

    private static ResidentMind.Result<ConversationLifecycle.Utterance> ask(QwenResidentMind mind, CompanionWorld world, String selfId,
                                                        String partnerId, String partnerName, String line,
                                                        String topic, List<ResidentMind.WorkArrangementView> work) {
        var transcript = List.of(new CompanionWorld.Turn(partnerId, line, NOW, "model"));
        var context = context(world, selfId, partnerId, world.memories.stream().filter(memory -> memory.ownerId().equals(selfId)).toList(), transcript, work);
        return mind.generateTurnMetered(new ResidentMind.DialogueRequest(context, "probe-" + selfId, 1, "operation-" + selfId, partnerName, topic));
    }

    private static ResidentMind.Context context(CompanionWorld world, String selfId, String partnerId,
                                                 List<CompanionWorld.Memory> memories, List<CompanionWorld.Turn> transcript,
                                                 List<ResidentMind.WorkArrangementView> work) {
        var state = ResidentSimulation.state(world, selfId);
        var context = new ResidentMind.Context(selfId,"15:00","clear",ResidentMind.actorView(ResidentSimulation.actor(world,selfId)),
            state.goal,List.of(),List.of(),ResidentMind.memoryViews(memories),List.of(),ResidentMind.actorViews(List.of(ResidentSimulation.actor(world,partnerId))),
            List.of(),List.of(),List.of(),List.of(),ResidentMind.turnViews(transcript),null,null,ResidentMind.planView(state.plan,NOW),work,state.occupation,com.betterself.growth.town.companion.application.ResidentDirector.personaView(selfId),List.of("observe","rest","work"),"owner",List.of(),"owner".equals(selfId),List.of(),"open",null,null,null,null);
        return context;
    }
}
