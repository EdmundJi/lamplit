package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the same rule {@code Occasions} states for the action menu, applied one layer up to the
 * decide() instruction text itself: a sentence that explains one specific action belongs in the
 * prompt only while {@code context.availableActions()} actually offers that action (or, for the
 * cafe-operator cluster, only while this resident's situation makes it relevant) - never every time,
 * regardless of what this call could possibly do. A real decision call measured 4832 characters of
 * instruction against 3517 of perception, and {@code celebrate} explained an action that was never
 * once offered in 243 real calls. These assertions are deliberately literal, the same way
 * {@link PromptBalanceTest} pins wording: a future edit that quietly puts a conditional sentence back
 * on the always-on path (or drops it from the menu it is supposed to ride) fails here.
 */
class DecisionPromptRelevanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Runs one decide() call against a provider that answers with a fixed decision and simply keeps
     * the instruction it was given - same pattern as {@link PromptBalanceTest#promptFor}. */
    private static String promptFor(ResidentMind.Context context) {
        AtomicReference<String> captured = new AtomicReference<>();
        QwenProvider provider = new QwenProvider() {
            @Override public StructuredResult generateStructured(StructuredPrompt prompt) {
                captured.set(prompt.instruction());
                return new StructuredResult(
                    "{\"action\":\"none\",\"place\":\"street\",\"targetId\":null,\"reason\":\"没什么特别的\",\"speech\":\"\",\"evidenceIds\":[],\"projectTitle\":null,\"objectKind\":null}",
                    "fake-model", "fake-request", 1, 1, 0);
            }
            @Override public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) { throw new UnsupportedOperationException(); }
            @Override public Classification classify(ClassificationPrompt prompt) { throw new UnsupportedOperationException(); }
        };
        new QwenResidentMind(provider, JSON, "qwen", true, "qwen").decide(context);
        return captured.get();
    }

    /** A minimal but complete Context. {@code availableActions} is this call's own menu;
     * {@code place}/{@code cafeOperatorId}/{@code cafeRoleFacts}/{@code canTend}/{@code cafeStatus}
     * are exactly the facts {@code QwenResidentMind.cafeRoleRelevant} reads. */
    private static ResidentMind.Context context(List<String> availableActions, String place,
            String cafeOperatorId, List<String> cafeRoleFacts, boolean canTend, String cafeStatus) {
        return new ResidentMind.Context("artist", "14:00", "sunny",
            new ResidentMind.ActorView("artist", "知夏", "artist", place, "observe", null),
            "过好今天", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, List.of(),
            "画点东西", null, availableActions, cafeOperatorId, cafeRoleFacts, canTend, List.of(),
            cafeStatus, null, null, null, null);
    }

    /** A resident with nothing to do with the cafe at all: not the operator, no retained role facts,
     * no tending rights, standing on the street, and a cafe status the rules never bothered to report. */
    private static ResidentMind.Context ordinaryStreetResident(List<String> availableActions) {
        return context(availableActions, "street", null, List.of(), false, null);
    }

    // ---- celebrate ------------------------------------------------------------------------------

    @Test
    @DisplayName("celebrate 不在 availableActions 里时，它那段用法说明不出现")
    void celebrateExplanationAbsentWhenNotOffered() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "rest")));
        assertThat(prompt).doesNotContain("celebrate只在一件你参与做过的事真的做完");
    }

    @Test
    @DisplayName("celebrate 在 availableActions 里时，它那段用法说明出现")
    void celebrateExplanationPresentWhenOffered() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "celebrate")));
        assertThat(prompt).contains("celebrate只在一件你参与做过的事真的做完");
    }

    // ---- propose --------------------------------------------------------------------------------

    @Test
    @DisplayName("propose 不在 availableActions 里时，它的三条严规不出现")
    void proposeRulesAbsentWhenNotOffered() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "rest")));
        assertThat(prompt).doesNotContain("propose是例外，规则比别的动作严");
        assertThat(prompt).doesNotContain("大胆的创意可以是提案或幻想");
    }

    @Test
    @DisplayName("propose 在 availableActions 里时，它的三条严规出现")
    void proposeRulesPresentWhenOffered() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "propose")));
        assertThat(prompt).contains("propose是例外，规则比别的动作严");
        assertThat(prompt).contains("大胆的创意可以是提案或幻想");
    }

    // ---- join -------------------------------------------------------------------------------------

    @Test
    @DisplayName("join 不在 availableActions 里时（也没有 create/help），它的用法说明不出现")
    void joinExplanationAbsentWhenNotOffered() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "rest")));
        assertThat(prompt).doesNotContain("join表示走过去挨着某个熟人坐下");
        assertThat(prompt).doesNotContain("create/help 的 targetId 必须是 knownProjects 之一且 place 匹配");
    }

    @Test
    @DisplayName("join 在 availableActions 里时，它的用法说明出现")
    void joinExplanationPresentWhenOffered() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "join")));
        assertThat(prompt).contains("join表示走过去挨着某个熟人坐下");
        assertThat(prompt).contains("create/help 的 targetId 必须是 knownProjects 之一且 place 匹配");
    }

    // ---- cafe-operator cluster --------------------------------------------------------------------

    @Test
    @DisplayName("跟咖啡馆完全无关的居民：经营权/吧台/营业那几段一个都不出现")
    void cafeOperatorClusterAbsentForAnUninvolvedResident() {
        String prompt = promptFor(ordinaryStreetResident(List.of("none", "observe", "rest", "study", "work", "read", "make", "sleep", "away")));
        assertThat(prompt).doesNotContain("cafeRoleFacts只陈述自己真实保留的经营权");
        assertThat(prompt).doesNotContain("前经营者若想回来帮忙");
        assertThat(prompt).doesNotContain("咖啡馆的帮工、委托、接手、拒绝和退出只能在两人当面的结构化对话回合里协商");
        assertThat(prompt).doesNotContain("occupation、cafeOperatorId、canTend");
        assertThat(prompt).doesNotContain("咖啡馆营业时，普通居民也可以把它当作有六个独立窗边座位");
        assertThat(prompt).doesNotContain("cafeStatus、cafeScheduleCue和cafeNotice");
    }

    @Test
    @DisplayName("cafeRoleFacts 非空时，经营权那几段出现")
    void cafeOperatorClusterPresentWhenRoleFactsCarried() {
        String prompt = promptFor(context(List.of("none", "observe"), "street", null, List.of("我曾是这家店的经营者"), false, null));
        assertThat(prompt).contains("cafeRoleFacts只陈述自己真实保留的经营权");
    }

    @Test
    @DisplayName("人在 cafe 里时，经营权/营业那几段出现")
    void cafeOperatorClusterPresentWhenStandingInTheCafe() {
        String prompt = promptFor(context(List.of("none", "observe"), "cafe", null, List.of(), false, null));
        assertThat(prompt).contains("咖啡馆的帮工、委托、接手、拒绝和退出只能在两人当面的结构化对话回合里协商");
    }

    // ---- the permanent section ---------------------------------------------------------------------

    @Test
    @DisplayName("常驻段：action必须严格照抄availableActions那一大段，在任何 context 下都在")
    void theActionMustCopyAvailableActionsParagraphIsAlwaysPresent() {
        String narrow = promptFor(ordinaryStreetResident(List.of("none", "observe", "rest")));
        String broad = promptFor(context(
            List.of("none", "observe", "rest", "celebrate", "propose", "join", "create", "help",
                "continue", "resume", "tend", "request_drink", "open_cafe", "continue_home"),
            "cafe", "artist", List.of("我是当前经营者"), true, "open"));

        String signature = "action必须严格照抄availableActions这次实际给出的字符串之一";
        assertThat(narrow).contains(signature);
        assertThat(broad).contains(signature);
    }
}
