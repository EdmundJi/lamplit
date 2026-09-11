package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second half of the rule {@link Occasions} documents: <b>每一问都必须有一个免费的"不做"</b>, and
 * the ordinary decision is a question too.
 *
 * <p>It had 26 verbs and no way to say "nothing in particular", so that answer had to come back
 * wearing something else: a measured two-day run put 18% of every decision in town on {@code rest},
 * 18% on {@code continue} and 14% on {@code make}. Each of those is a real action the rules then went
 * and carried out, which means the town was busier than anybody in it had actually decided to be, and
 * the three numbers we would have read those percentages off meant nothing.
 */
class NothingInParticularTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld world(String id) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", now, true);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, now, "测试准备"));
        return w;
    }

    @Test
    @DisplayName("说「没什么特别想做的」，就真的什么都不做——不会被翻译成休息")
    void doingNothingSchedulesNothing() {
        CompanionWorld w = world("none-plain");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null; artist.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "artist", "cafe", "idle", "等下一步", now.plusSeconds(60));

        assertThat(ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "cafe", "none", null, "就这么待会儿", null, List.of(), now)).isTrue();

        assertThat(artist.plan).as("没有计划，因为她没打算做什么").isNull();
        assertThat(ResidentSimulation.actor(w, "artist").activity())
            .as("也没被记成 rest——那正是这一项要拆开的东西").isEqualTo("idle");
        assertThat(artist.thought).isEqualTo("就这么待会儿");
    }

    @Test
    @DisplayName("不做不是拒答：不能被当成失败记进那条退避")
    void doingNothingIsNotCountedAsARefusal() {
        // recordDecisionOutcome's backoff exists to stop a resident being asked over and over while
        // the model keeps returning something the rules cannot apply. An honest "nothing in
        // particular" is the opposite of that, and punishing it would teach the model that this
        // answer costs something - which is how an answer stops being free.
        CompanionWorld w = world("none-not-refusal");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null;
        ResidentSimulation.replaceActor(w, "artist", "cafe", "idle", "等下一步", now.plusSeconds(60));

        boolean applied = ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "cafe", "none", null, "没什么特别想做的", null, List.of(), now);
        ResidentSimulation.recordDecisionOutcome(artist, applied, now);

        assertThat(applied).isTrue();
        assertThat(artist.consecutiveDecisionRejections).isZero();
    }

    @Test
    @DisplayName("安静一阵子之后还会被问，不会就此消失")
    void theQuietStretchIsBoundedAndTheyAreAskedAgainAfterIt() {
        CompanionWorld w = world("none-bounded");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null;
        ResidentSimulation.replaceActor(w, "artist", "cafe", "idle", "等下一步", now.plusSeconds(60));
        ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "cafe", "none", null, "没什么特别想做的", null, List.of(), now);

        assertThat(artist.decisionRetryAfter).as("确实安静了一阵").isAfter(now);
        assertThat(artist.decisionRetryAfter).as("但这一阵有头——半小时以内还会再被问")
            .isBeforeOrEqualTo(now.plusSeconds(1800));
    }

    @Test
    @DisplayName("正在做的事不会被「不做」打断")
    void doingNothingDoesNotTearUpSomethingAlreadyUnderway() {
        CompanionWorld w = world("none-keeps-plan");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = new CompanionWorld.Plan("p-sketch", "make", "cafe", null, "把小稿收尾", now, now.plusSeconds(1800));
        ResidentSimulation.replaceActor(w, "artist", "cafe", "make", "还在画小稿", now.plusSeconds(1800));

        assertThat(ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "cafe", "none", null, "手上这张画完再说", null, List.of(), now)).isTrue();

        assertThat(artist.plan).as("手上的事还在").isNotNull();
        assertThat(artist.plan.id()).isEqualTo("p-sketch");
        assertThat(ResidentSimulation.actor(w, "artist").activity()).isEqualTo("make");
    }
}
