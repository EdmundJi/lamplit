package com.betterself.growth.town.companion.application;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.adapters.QwenResidentMind;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lending exists in the rules ({@code Lending}, {@code CompanionWorld.Loan}, and the {@code
 * lend}/{@code gift}/{@code return_loan} entries in {@code DECISION_ACTIONS}) - this checks the half
 * that decides whether any of it ever happens: that a resident who could lend is actually told what
 * the verb does.
 *
 * <p>It is written because this town has already paid for the alternative. {@code celebrate} was
 * offered 1658 times and chosen 0; {@code create} 342 times, chosen 0; {@code invite} 285 times,
 * chosen 0 (docs/01-requirements.md 「一条用钱换来的警告」). A verb that appears in the schema enum with
 * no explanation anywhere in the instruction is not a capability, it is a line item in a report.
 *
 * <p>The second test is the more important one, and it guards a measurement rather than a feature.
 * docs/01 第二版 puts the whole point of ownership like this: rules record only what a bystander could
 * see - who lent what to whom, when, whether it came back - and 「我欠他一次」 may appear <b>only</b> in
 * a resident's own words. So the measurement is 「有没有人写下了一笔规则从没告诉过他的债」. The instant
 * this prompt explains that borrowing creates an obligation, every resident who later writes about
 * owing is repeating us, and the finding is worthless before the first run. Keeping obligation
 * language out is therefore not tidiness; it is the experiment.
 */
class LendingReachesTheModelTest {
    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two residents standing in the shop, one of whom owns something lendable there. Uses the real
     * seeded world and the real {@code availableActions}, so this fails if lending ever stops being
     * reachable at all - not only if the wording changes. */
    private static String promptInTheShop() {
        CompanionWorld w = CompanionRules.join("lending-prompt", "我", "Asia/Shanghai", NOW, true);
        move(w, "fixer", "看看手上的家伙事");
        move(w, "weaver", "在旁边翻线盒");

        assertThat(ResidentSimulation.availableActions(w, "fixer", NOW))
            .as("the rules must actually offer lending here, or this test is checking nothing")
            .contains("lend", "gift");

        AtomicReference<String> captured = new AtomicReference<>();
        QwenProvider provider = new QwenProvider() {
            public StructuredResult generateStructured(StructuredPrompt prompt) {
                captured.set(prompt.instruction());
                return new StructuredResult("{\"action\":\"none\",\"place\":\"shop\",\"targetId\":null,"
                    + "\"reason\":\"先看看\",\"speech\":\"\",\"evidenceIds\":[]}", "fake", "request", 1, 1, 0);
            }
            public StreamMetadata stream(ChatPrompt p, Consumer<String> c) { throw new UnsupportedOperationException(); }
            public Classification classify(ClassificationPrompt p) { throw new UnsupportedOperationException(); }
        };
        var director = new ResidentDirector(new SnapshotStore(w), new DisabledMind(), Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            new QwenResidentMind(provider, JSON, "qwen", true)
                .decideMetered(director.perspective(w, "fixer", NOW, List.of()));
        } finally { director.close(); }
        return captured.get();
    }

    @Test
    @DisplayName("能借东西的时候，模型得知道 lend/gift 是什么、targetId 填什么")
    void aResidentWhoCouldLendIsToldWhatTheVerbDoes() {
        String prompt = promptInTheShop();
        assertThat(prompt).as("lend 要被解释").contains("lend是把自己的一件东西先借给");
        assertThat(prompt).as("gift 要被解释，并且和 lend 区分开").contains("gift是直接给他、不再是自己的");
        // The commonest way to waste a call: name a person where the rules expect a thing.
        assertThat(prompt).as("targetId 填东西不填人，必须说清楚").contains("不是人的id");
    }

    @Test
    @DisplayName("借东西这一段绝不能替居民写出「欠」——那正是要量的东西")
    void theLendingPromptNeverTellsAnyoneThatBorrowingCreatesADebt() {
        String prompt = promptInTheShop();
        // Not a style rule. If the prompt supplies the idea of a debt, then a resident writing
        // 「我欠他一次」 is quoting the prompt, and docs/01's 「不可直写」 gate is gone.
        assertThat(prompt).as("不得出现「欠」").doesNotContain("欠");
        assertThat(prompt).as("不得出现「人情」").doesNotContain("人情");
        assertThat(prompt).as("不得把还东西说成应该").doesNotContain("应该还", "记得还", "要还回");
        assertThat(prompt).as("不得暗示会被记住或被回报").doesNotContain("回报", "记得你", "感激");
    }

    /** Same shape ResidentDirectorDialogueTest uses: rewrite the Actor in place, because
     * ResidentSimulation.replaceActor is package-private to domain. */
    private static void move(CompanionWorld w, String id, String label) {
        for (int i = 0; i < w.residents.size(); i++) {
            var a = w.residents.get(i);
            if (a.id().equals(id))
                w.residents.set(i, new CompanionWorld.Actor(a.id(), a.name(), a.role(), "shop", "observe",
                    label, a.x(), a.y(), NOW.plusSeconds(600)));
        }
    }

    private static final class DisabledMind implements ResidentMind {
        public boolean enabled() { return false; }
        public Decision decide(Context context) { throw new UnsupportedOperationException(); }
    }
    private record SnapshotStore(CompanionWorld world) implements WorldStore {
        public CompanionWorld read(long userId) { return world; }
        public CompanionWorld update(long userId, java.util.function.Supplier<CompanionWorld> initial, java.util.function.UnaryOperator<CompanionWorld> change) { return change.apply(world); }
        public boolean ownsTask(long userId, String taskId) { return false; }
        public String timezone(long userId) { return world.timezone; }
    }
}
