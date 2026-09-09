package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.Project;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "There is nothing left in this town that you and anybody else could be doing together. Is there
 * something you want that you could not do on your own?"
 *
 * <p>Why this is asked separately rather than left as one more item on the decision menu: across a
 * two-day measured run, `propose` was offered 472 times and chosen 0, `invite` 285/0, `join` 285/0,
 * `help` 173/0 and `celebrate` 261/0 - about 1800 chances and two takers - after ten separate lines
 * of prompt had been rebalanced to stop pushing residents away from collaborating. Over the same two
 * days the same model, asked on its own whether to say something to a person standing in front of
 * it, said yes 67 times out of 127. The wording was never the problem; a discretionary social action
 * sitting twentieth on a flat menu loses every comparison it is in.
 *
 * <p>These tests pin only the rules' half: WHEN the question is worth putting. What anybody wants is
 * never decided here.
 */
class VentureTest {
    private static final Instant DAY = Instant.parse("2026-09-09T04:00:00Z"); // noon Shanghai

    private CompanionWorld world() {
        CompanionWorld w = CompanionRules.join("venture", "住客", "Asia/Shanghai", DAY);
        w.conversations.forEach(c -> c.status = "ended");
        for (ResidentState r : w.residentStates) r.plan = null;
        return w;
    }

    /** Everything communal finished: this is the exact state that produced four consecutive measured
     * days of zero joint action. */
    private void emptyTheTown(CompanionWorld w) {
        for (Project p : w.projects) { p.status = "ready"; p.progress = 100; }
    }

    @Test void nobodyIsAskedWhileThereIsStillSomethingInTownToJoin() {
        CompanionWorld w = world();
        for (String id : List.of("owner", "student", "artist", "gardener", "fixer", "weaver"))
            assertThat(ResidentSimulation.needsVenture(w, id, DAY))
                .as(id + " - four unfinished shared things are sitting right there").isFalse();
    }

    @Test void theQuestionIsPutOnceTheTownHasRunOutOfThingsToDoTogether() {
        CompanionWorld w = world();
        emptyTheTown(w);
        assertThat(ResidentSimulation.needsVenture(w, "weaver", DAY)).isTrue();
        assertThat(ResidentSimulation.sharedThingsLeft(w, "weaver")).isEmpty();
    }

    @Test void aThingStalledForWantOfHandsYouCouldBeStillCountsAsSomethingToJoin() {
        CompanionWorld w = world();
        emptyTheTown(w);
        Project stalled = w.projects.getFirst();
        stalled.status = "active"; stalled.progress = ResidentSimulation.SOLO_PROGRESS_CAP;
        stalled.contributors.clear(); stalled.contributors.add("owner"); stalled.needed = 3;
        assertThat(ResidentSimulation.needsVenture(w, "weaver", DAY))
            .as("it has stopped precisely because it needs somebody like her").isFalse();
    }

    @Test void theAvatarIsNeverAskedWhatItWantsBecauseThatIsTheUsersToSay() {
        CompanionWorld w = world();
        emptyTheTown(w);
        assertThat(ResidentSimulation.needsVenture(w, "self", DAY)).isFalse();
    }

    @Test void somebodyAlreadyCarryingTwoUnfinishedIdeasOfTheirOwnIsNotAskedForAThird() {
        CompanionWorld w = world();
        emptyTheTown(w);
        for (int i = 0; i < 2; i++) {
            Project mine = new Project();
            mine.id = "wish-" + i; mine.title = "想做的事" + i; mine.kind = "shared"; mine.place = "cafe";
            mine.ownerId = "weaver"; mine.status = "idea"; mine.needed = 2; mine.objectKind = "tea";
            mine.members.add("weaver");
            w.projects.add(mine);
        }
        assertThat(ResidentSimulation.needsVenture(w, "weaver", DAY)).isFalse();
    }

    @Test void askingCountsWhateverTheAnswerWasSoNobodyIsPesteredHourly() {
        CompanionWorld w = world();
        emptyTheTown(w);
        assertThat(ResidentSimulation.needsVenture(w, "weaver", DAY)).isTrue();
        ResidentSimulation.markVentureAsked(w, "weaver", DAY);
        assertThat(ResidentSimulation.needsVenture(w, "weaver", DAY.plusSeconds(3600)))
            .as("wanting nothing an hour ago is an answer, not a gap to fill").isFalse();
        assertThat(ResidentSimulation.needsVenture(w, "weaver", DAY.plusSeconds(9 * 3600))).isTrue();
    }

    @Test void aNewWishGoesOnTheBoardSoSomebodyElseCanActuallyJoinIt() {
        // A wish only its owner knows about is exactly as unjoinable as the seeded projects were
        // before the noticeboard existed - a private to-do item that happens to say it needs two.
        CompanionWorld w = world();
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        String evidence = w.memories.stream().filter(m -> m.ownerId().equals("weaver")).findFirst().orElseThrow().id();
        assertThat(ResidentSimulation.proposeDecision(w, "weaver", weaver.revision, w.intentRevision,
            "cafe", "把街口那盏灯修好", "poster", "总有人晚上看不清路", List.of(evidence), DAY)).isTrue();
        Project wish = w.projects.getLast();
        for (String id : List.of("owner", "student", "artist", "gardener", "fixer"))
            assertThat(ResidentSimulation.knows(w, id, wish.id)).as(id).isTrue();
        assertThat(ResidentSimulation.knows(w, "self", wish.id)).as("never the avatar's business").isFalse();
    }
}
