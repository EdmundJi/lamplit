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
 * The other half of entering somebody's home (docs/01-requirements.md 第二版「世界」「进别人家由所有
 * 权和门决定」): {@code invite_home}/{@code visit_home} exist as real, reachable {@code
 * DECISION_ACTIONS}, but a verb the model was never told the meaning of is not a capability - see
 * {@code LendingReachesTheModelTest}'s own doc comment for the exact, already-paid-for lesson this
 * mirrors ({@code celebrate} 1658/0, {@code create} 342/0, {@code invite} 285/0).
 */
class HomeVisitingReachesTheModelTest {
    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String promptFor(CompanionWorld w, String residentId) {
        AtomicReference<String> captured = new AtomicReference<>();
        QwenProvider provider = new QwenProvider() {
            public StructuredResult generateStructured(StructuredPrompt prompt) {
                captured.set(prompt.instruction());
                return new StructuredResult("{\"action\":\"none\",\"place\":\"street\",\"targetId\":null,"
                    + "\"reason\":\"先看看\",\"speech\":\"\",\"evidenceIds\":[]}", "fake", "request", 1, 1, 0);
            }
            public StreamMetadata stream(ChatPrompt p, Consumer<String> c) { throw new UnsupportedOperationException(); }
            public Classification classify(ClassificationPrompt p) { throw new UnsupportedOperationException(); }
        };
        var director = new ResidentDirector(new SnapshotStore(w), new DisabledMind(), Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            new QwenResidentMind(provider, JSON, "qwen", true)
                .decideMetered(director.perspective(w, residentId, NOW, List.of()));
        } finally { director.close(); }
        return captured.get();
    }

    @Test
    @DisplayName("面对面能请人回家的时候，模型得知道 invite_home 是什么、targetId 填的是人")
    void aResidentWhoCouldInviteIsToldWhatTheVerbDoes() {
        CompanionWorld w = CompanionRules.join("invite-home-prompt", "我", "Asia/Shanghai", NOW, true);
        move(w, "owner", "cafe", "在吧台后面"); move(w, "student", "cafe", "在窗边看书");

        assertThat(ResidentSimulation.availableActions(w, "owner", NOW))
            .as("the rules must actually offer it, or this test is checking nothing")
            .contains("invite_home");
        String prompt = promptFor(w, "owner");
        assertThat(prompt).as("invite_home 要被解释").contains("invite_home是请眼前这个人以后来自己家坐坐");
        assertThat(prompt).as("targetId 是人的id，和 lend/gift 的物件id正好相反").contains("targetId填nearby中那个人的id");
    }

    @Test
    @DisplayName("被请过之后，模型得知道 visit_home 是什么，而且知道不去完全正常")
    void anInvitedResidentIsToldWhatVisitingMeansAndThatDecliningIsFine() {
        CompanionWorld w = CompanionRules.join("visit-home-prompt", "我", "Asia/Shanghai", NOW, true);
        move(w, "owner", "cafe", "在吧台后面"); move(w, "student", "cafe", "在窗边看书");
        var owner = ResidentSimulation.state(w, "owner");
        assertThat(ResidentSimulation.applyDecision(w, "owner", owner.revision, w.intentRevision,
            "cafe", "invite_home", "student", "有空来家里坐坐", null, List.of(), NOW)).isTrue();

        assertThat(ResidentSimulation.availableActions(w, "student", NOW))
            .as("邀请生效了，这次真的可以选")
            .contains("visit_home");
        String prompt = promptFor(w, "student");
        assertThat(prompt).as("visit_home 要被解释").contains("visit_home是去一个曾经请过你的人家里坐坐");
        // The commonest way this class of verb dies: reading as an obligation rather than an offer.
        assertThat(prompt).as("不去必须一样正常，不需要理由").contains("不想去也完全正常，不去不需要理由");
    }

    private static void move(CompanionWorld w, String id, String place, String label) {
        for (int i = 0; i < w.residents.size(); i++) {
            var a = w.residents.get(i);
            if (a.id().equals(id))
                w.residents.set(i, new CompanionWorld.Actor(a.id(), a.name(), a.role(), place, "observe",
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
