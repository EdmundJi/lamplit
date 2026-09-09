package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.CompanionWorld.Plan;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The measurement behind the target "每天至少 3 次两个人一起做同一件事". It exists as a test because
 * every other dead feature in this town compiled cleanly and had a test that was never executed - a
 * metric that silently counts nothing would be the same failure one level up, and worse, because it
 * would report success.
 *
 * <p>The three tiers are pinned apart on purpose: a shared project and somebody choosing to go sit
 * with someone are decisions about a person; two people happening to read in the same room is the
 * map, since every resident's place habit points at the cafe.
 */
class JointActionMetricTest {
    private static final Instant T0 = Instant.parse("2026-09-09T04:00:00Z");

    private CompanionWorld world() {
        CompanionWorld w = CompanionRules.join("joint-metric", "住客", "Asia/Shanghai", T0);
        w.conversations.forEach(c -> c.status = "ended");
        return w;
    }

    private void put(CompanionWorld w, String id, String place, String activity, String action, String target) {
        for (int i = 0; i < w.residents.size(); i++) {
            CompanionWorld.Actor a = w.residents.get(i);
            if (!a.id().equals(id)) continue;
            w.residents.set(i, new CompanionWorld.Actor(a.id(), a.name(), a.role(), place, activity, "…", a.x(), a.y(), T0.plusSeconds(3600)));
        }
        ResidentState r = ResidentSimulation.state(w, id);
        r.plan = action == null ? null : new Plan(id + "-p", action, place, target, "…", T0, T0.plusSeconds(3600));
    }

    /** Samples the world tick by tick, the way a real run does (8 simulated seconds a tick with a
     * model on). Sampling only the two endpoints would not measure the same thing: an episode is a
     * continuous stretch, so a gap wider than the collector's own tolerance is two sightings of two
     * different afternoons, not one long one. */
    private Map<String, Object> measure(CompanionWorld w, long minutes) {
        TimelineCollector collector = new TimelineCollector();
        for (long second = 0; second <= minutes * 60; second += 8) {
            w.simulatedAt = T0.plusSeconds(second);
            collector.capture(w);
        }
        return MetricsExporter.compute(List.of(), collector, w);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> joint(CompanionWorld w, long minutesApart) {
        return (Map<String, Object>) measure(w, minutesApart).get("jointAction");
    }

    @SuppressWarnings("unchecked") @Test void twoPeopleWithTheirHandsOnOneProjectIsTheThingBeingCounted() {
        CompanionWorld w = world();
        String projectId = w.projects.stream().filter(p -> "cafe".equals(p.place)).findFirst().orElseThrow().id;
        put(w, "owner", "cafe", "make", "help", projectId);
        put(w, "student", "cafe", "make", "create", projectId);

        Map<String, Object> joint = joint(w, 10);
        assertThat(joint.get("chosenTotal")).isEqualTo(1);
        assertThat((Map<String, Integer>) joint.get("byKind")).containsEntry("sharedProject", 1);
        assertThat((Map<String, Integer>) joint.get("chosenPerDay")).containsEntry("2026-09-09", 1);
    }

    @SuppressWarnings("unchecked") @Test void goingOverToSitWithSomebodyCountsToo() {
        CompanionWorld w = world();
        put(w, "artist", "garden", "observe", null, null);
        put(w, "gardener", "garden", "join", "join", "artist");

        Map<String, Object> joint = joint(w, 10);
        assertThat(joint.get("chosenTotal")).isEqualTo(1);
        assertThat((Map<String, Integer>) joint.get("byKind")).containsEntry("satTogether", 1);
    }

    @SuppressWarnings("unchecked") @Test void twoPeopleReadingInTheSameRoomByCoincidenceIsNotCountedAsChosen() {
        // Every resident's place habit points at the cafe, so this happens constantly without anyone
        // having decided anything about anyone. It is reported, never added to the total.
        CompanionWorld w = world();
        put(w, "owner", "cafe", "read", "read", null);
        put(w, "student", "cafe", "read", "read", null);

        Map<String, Object> joint = joint(w, 10);
        assertThat(joint.get("chosenTotal")).as("coincidence must never inflate the headline").isEqualTo(0);
        assertThat((Map<String, Integer>) joint.get("byKind")).containsEntry("sameActivity", 1);
    }

    @Test void walkingPastSomebodyDoingWhatYouAreDoingIsNotDoingItWithThem() {
        CompanionWorld w = world();
        String projectId = w.projects.stream().filter(p -> "cafe".equals(p.place)).findFirst().orElseThrow().id;
        put(w, "owner", "cafe", "make", "help", projectId);
        put(w, "student", "cafe", "make", "create", projectId);

        Map<String, Object> joint = joint(w, 2); // under the five-minute floor
        assertThat(joint.get("chosenTotal")).isEqualTo(0);
    }

    @Test void oneLongAfternoonIsOneEpisodeAndNotThreeHundred() {
        CompanionWorld w = world();
        String projectId = w.projects.stream().filter(p -> "cafe".equals(p.place)).findFirst().orElseThrow().id;
        put(w, "owner", "cafe", "make", "help", projectId);
        put(w, "student", "cafe", "make", "create", projectId);

        TimelineCollector collector = new TimelineCollector();
        for (int tick = 0; tick <= 200; tick++) { // 200 ticks of 8 simulated seconds
            w.simulatedAt = T0.plusSeconds(tick * 8L);
            collector.capture(w);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> joint = (Map<String, Object>) MetricsExporter.compute(List.of(), collector, w).get("jointAction");
        assertThat(joint.get("chosenTotal")).as("counted once per episode, never once per tick").isEqualTo(1);
        assertThat(((List<?>) joint.get("episodes"))).hasSize(1);
    }

    @Test void theTargetIsCarriedInTheMetricSoARunEitherMeetsItOrVisiblyDoesNot() {
        CompanionWorld w = world();
        Map<String, Object> joint = joint(w, 10);
        assertThat(joint.get("targetPerDay")).isEqualTo(3);
        assertThat(joint.get("daysMeetingTarget")).isEqualTo(0L);
    }
}
