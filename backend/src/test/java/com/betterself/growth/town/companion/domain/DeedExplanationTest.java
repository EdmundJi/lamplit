package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules are the reflex; the model is the explanation. People mostly act first and account for it
 * afterwards, and the account is often not the real cause - Gazzaniga's interpreter, Libet's
 * readiness potential, Nisbett &amp; Wilson's "Telling More Than We Can Know". These tests pin the
 * two halves of that split that must not drift: the rules never write a motive, and the account the
 * resident ends up with is the thing that survives.
 */
class DeedExplanationTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld world() {
        CompanionWorld w = CompanionRules.join("deeds", "住客", "Asia/Shanghai", now, true);
        w.conversations.forEach(c -> c.status = "ended");
        return w;
    }

    @Test void deedsPileUpUntilThereIsEnoughToBeWorthAccountingFor() {
        CompanionWorld w = world();
        assertThat(ResidentSimulation.needsExplanation(w, "owner", now)).isFalse();
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "又擦了一遍已经擦过的那张桌子。", now);
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "把杯子按高矮重新排了一次。", now.plusSeconds(60));
        assertThat(ResidentSimulation.needsExplanation(w, "owner", now)).as("two is not yet a stretch of behaviour").isFalse();
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "擦了柜台。", now.plusSeconds(120));
        assertThat(ResidentSimulation.needsExplanation(w, "owner", now)).isTrue();
    }

    @Test void theRulesNeverWriteAMotiveIntoADeed() {
        CompanionWorld w = world();
        ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "又擦了一遍已经擦过的那张桌子。", now);
        var deed = ResidentSimulation.unexplainedDeeds(w, "owner").getFirst();
        // Only what an observer would have seen. Why he did it is his to say, later, and he may be
        // wrong about it - that is the point.
        assertThat(deed.note).isEqualTo("又擦了一遍已经擦过的那张桌子。");
        assertThat(deed.action).isEqualTo("tidy");
        assertThat(w.memories).as("a deed is not yet a memory").noneMatch(m -> m.text().contains("擦了一遍"));
    }

    @Test void theAccountBecomesWhatTheResidentKnowsAboutThemselves() {
        CompanionWorld w = world();
        var owner = ResidentSimulation.state(w, "owner");
        for (int i = 0; i < 3; i++) ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "又擦了一遍桌子。", now.plusSeconds(i * 60L));
        var ids = ResidentSimulation.unexplainedDeeds(w, "owner").stream().map(d -> d.id).toList();
        // 阿禾's superego is "我不该让人看出我在意" - so his own account of tidying is about the table,
        // not about himself. He will read this back later and believe it.
        assertThat(ResidentSimulation.applyExplanation(w, "owner", owner.revision, ids, "桌子确实脏了，顺手都擦了。", List.of(), now.plusSeconds(200))).isTrue();
        assertThat(ResidentSimulation.unexplainedDeeds(w, "owner")).isEmpty();
        var written = w.memories.stream().filter(m -> m.ownerId().equals("owner") && m.text().contains("桌子确实脏了")).findFirst().orElseThrow();
        // A construction after the fact, not something observed - the memory layer already carries
        // that distinction, and the model is told reflection can be wrong.
        assertThat(written.sourceType()).isEqualTo("reflection");
        assertThat(owner.thought).isEqualTo("桌子确实脏了，顺手都擦了。");
    }

    @Test void deedsNobodyAccountedForAreSimplyGone() {
        CompanionWorld w = world();
        for (int i = 0; i < 12; i++) ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "第" + i + "次擦桌子。", now.plusSeconds(i * 60L));
        var kept = ResidentSimulation.unexplainedDeeds(w, "owner");
        assertThat(kept).hasSize(8);
        assertThat(kept.getFirst().note).as("the oldest unaccounted-for deeds fall off first").isEqualTo("第4次擦桌子。");
    }

    @Test void anAccountCitingSomeoneElsesMemoryIsRefusedOutright() {
        CompanionWorld w = world();
        var owner = ResidentSimulation.state(w, "owner");
        for (int i = 0; i < 3; i++) ResidentSimulation.recordDeed(w, "owner", "tidy", "cafe", "擦桌子。", now.plusSeconds(i * 60L));
        var ids = ResidentSimulation.unexplainedDeeds(w, "owner").stream().map(d -> d.id).toList();
        String someoneElses = w.memories.stream().filter(m -> !m.ownerId().equals("owner")).findFirst().orElseThrow().id();
        int before = w.memories.size();
        assertThat(ResidentSimulation.applyExplanation(w, "owner", owner.revision, ids, "想起了一件事", List.of(someoneElses), now.plusSeconds(200))).isFalse();
        assertThat(w.memories).hasSize(before);
        assertThat(ResidentSimulation.unexplainedDeeds(w, "owner")).as("a refused account clears nothing").hasSize(3);
    }
}
