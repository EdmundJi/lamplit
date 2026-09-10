package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A half-day model run made three promises and settled all three as did_not_come. Nobody had changed
 * their mind - nothing in the world ever told them their own promise was coming due, so keeping one
 * would have been an accident. Machinery that manufactures unreliability and then hands the town a
 * norm about it is worse than no promise machinery at all: every reader afterwards takes the pattern
 * for a finding.
 */
class PromiseCueTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld twoInTheGarden() {
        CompanionWorld w = CompanionRules.join("promise-cue", "住客", "Asia/Shanghai", now, false);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(7200));
        }
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    @Test
    @DisplayName("自己答应过的事，在还来得及的时候，自己是知道的")
    void aResidentKnowsAboutTheirOwnPromiseWhileThereIsStillTime() {
        CompanionWorld w = twoInTheGarden();
        assertThat(ResidentSimulation.promise(w, "owner", "gardener", "把新苗种到花园去", "garden",
                now.plusSeconds(3600), now)).isTrue();

        assertThat(ResidentSimulation.routineCues(w, "owner", now.plusSeconds(1800)))
                .anySatisfy(cue -> assertThat(cue).contains("青叔").contains("把新苗种到花园去"));
    }

    @Test
    @DisplayName("提示是一句事实，不是一道命令——去、忘了、或者觉得不值当了，都还得留着")
    void theCueStatesTheFactAndNeverTellsThemToGo() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.promise(w, "owner", "gardener", "把新苗种到花园去", "garden",
                now.plusSeconds(3600), now);
        assertThat(ResidentSimulation.routineCues(w, "owner", now.plusSeconds(1800)))
                .allSatisfy(cue -> assertThat(cue).doesNotContain("应该").doesNotContain("必须").doesNotContain("记得去"));
    }

    @Test
    @DisplayName("还早得很的时候不提——那不是提醒，是唠叨")
    void staysQuietWhileItIsStillFarOff() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.promise(w, "owner", "gardener", "把新苗种到花园去", "garden",
                now.plusSeconds(20 * 3600), now);
        assertThat(ResidentSimulation.routineCues(w, "owner", now.plusSeconds(60)))
                .noneSatisfy(cue -> assertThat(cue).contains("把新苗种到花园去"));
    }

    @Test
    @DisplayName("提示只给许诺的那个人——被答应的人没有义务替他记着")
    void onlyThePersonWhoMadeItIsTheOneWhoCarriesIt() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.promise(w, "owner", "gardener", "把新苗种到花园去", "garden",
                now.plusSeconds(3600), now);
        assertThat(ResidentSimulation.routineCues(w, "gardener", now.plusSeconds(1800)))
                .noneSatisfy(cue -> assertThat(cue).contains("把新苗种到花园去"));
    }

    @Test
    @DisplayName("结清之后就不再提了")
    void goesQuietOnceItHasBeenSettled() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.promise(w, "owner", "gardener", "把新苗种到花园去", "garden",
                now.plusSeconds(3600), now);
        CompanionRules.advance(w, now.plusSeconds(3600 + 700));
        assertThat(w.promises).allSatisfy(p -> assertThat(p.settledAt).isNotNull());
        assertThat(ResidentSimulation.routineCues(w, "owner", now.plusSeconds(4400)))
                .noneSatisfy(cue -> assertThat(cue).contains("把新苗种到花园去"));
    }
}
