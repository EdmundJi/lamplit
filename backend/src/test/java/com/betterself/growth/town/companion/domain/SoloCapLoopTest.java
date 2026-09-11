package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 青叔 put his hands on the same thing 163 times in five simulated hours - once every two minutes,
 * every one of them a paid model call, and every one of them writing a contribution event, a memory,
 * an object update and a witness pass for everyone in the garden. The thing never moved: it needed two
 * people and he was one. {@link ResidentSimulation#SOLO_PROGRESS_CAP} stopped the progress and stopped
 * nothing else.
 *
 * <p>It also quietly ruined the measurement it fed: that one loop pushed a pair's support to 197 in the
 * norm detector, which is a number no honest reading of two simulated days can produce.
 */
class SoloCapLoopTest {
    private final Instant now = Instant.parse("2026-09-08T02:00:00Z");

    private CompanionWorld worldWithACappedProject() {
        CompanionWorld w = CompanionRules.join("solo-cap", "住客", "Asia/Shanghai", now, false);
        w.conversations.forEach(c -> c.status = "ended");
        Project p = w.projects.stream().filter(x -> x.needed > 1).findFirst().orElseThrow();
        p.progress = ResidentSimulation.SOLO_PROGRESS_CAP;
        p.status = "active";
        p.contributors.clear();
        p.contributors.add("gardener");
        ResidentSimulation.replaceActor(w, "gardener", p.place, "make", "接着做", now.plusSeconds(3600));
        return w;
    }

    private Project capped(CompanionWorld w) {
        return w.projects.stream().filter(x -> x.needed > 1 && x.contributors.contains("gardener")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("一个人推不动的事，他再去添一笔不会被记成又添了一笔")
    void aFutileAttemptIsNotRecordedAsAContribution() {
        CompanionWorld w = worldWithACappedProject();
        Project p = capped(w);
        assertThat(ResidentSimulation.canAdvance(p, "gardener")).isFalse();

        int eventsBefore = w.events.size();
        ResidentState r = ResidentSimulation.state(w, "gardener");
        r.plan = new Plan("p-x", "create", p.place, p.id, "接着做", now, now.plusSeconds(1));
        CompanionRules.advance(w, now.plusSeconds(30));

        assertThat(w.events.stream().skip(eventsBefore)
                .filter(e -> "contribution".equals(e.type())).toList())
                .as("没有第二笔").isEmpty();
        assertThat(p.progress).isEqualTo(ResidentSimulation.SOLO_PROGRESS_CAP);
    }

    @Test
    @DisplayName("但他确实去了，这件事要留下来——由他自己事后解释")
    void heDidGoAndThatStaysOnTheRecord() {
        CompanionWorld w = worldWithACappedProject();
        Project p = capped(w);
        ResidentState r = ResidentSimulation.state(w, "gardener");
        r.plan = new Plan("p-x", "create", p.place, p.id, "接着做", now, now.plusSeconds(1));
        CompanionRules.advance(w, now.plusSeconds(30));

        assertThat(ResidentSimulation.unexplainedDeeds(w, "gardener"))
                .anySatisfy(d -> assertThat(d.note).contains("一个人推不动了"));
    }

    @Test
    @DisplayName("停住的事对别人仍然是敞开的——关掉所有人正是拿掉了唯一能解开它的东西")
    void itStaysOpenToWhoeverWouldUnblockIt() {
        CompanionWorld w = worldWithACappedProject();
        Project p = capped(w);
        assertThat(ResidentSimulation.canAdvance(p, "gardener")).as("他自己推不动").isFalse();
        assertThat(ResidentSimulation.canAdvance(p, "fixer")).as("第二双手可以").isTrue();
    }

    @Test
    @DisplayName("推不动的时候，连问都不该问——那次调用是白花的")
    void theQuestionIsNotEvenAskedWhenNothingCanMove() {
        CompanionWorld w = worldWithACappedProject();
        for (Project other : w.projects)
            if (!other.contributors.contains("gardener")) { other.status = "ready"; other.progress = 100; }
        assertThat(ResidentSimulation.availableActions(w, "gardener", now)).doesNotContain("create");
    }

    @Test
    @DisplayName("还没到上限的事，一切照旧")
    void nothingChangesForAThingThatCanStillMove() {
        CompanionWorld w = worldWithACappedProject();
        Project p = capped(w);
        p.progress = 10;
        assertThat(ResidentSimulation.canAdvance(p, "gardener")).isTrue();
        assertThat(ResidentSimulation.availableActions(w, "gardener", now)).contains("create");
    }
}
