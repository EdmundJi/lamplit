package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** Outcome checks across a sustained interval when no resident model is configured. */
class CompanionEmergenceTest {
    @Test void aRuleOnlyAfternoonFinishesExistingEventsWithoutInventingNewIntentions() {
        Instant start = Instant.parse("2026-09-08T06:00:00Z");
        CompanionWorld world = CompanionRules.join("afternoon-alice", "住客", "Asia/Shanghai", start);
        Set<String> eventKinds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        for (int second = 6; second <= 1800; second += 6) {
            CompanionRules.advance(world, start.plusSeconds(second));
            world.events.forEach(event -> { eventKinds.add(event.type()); eventIds.add(event.id()); });
            assertThat(world.residents).hasSize(6);
            assertThat(world.residentStates).allSatisfy(resident -> {
                assertThat(resident.energy).isBetween(0.0, 100.0);
                assertThat(resident.social).isBetween(0.0, 100.0);
            });
            assertThat(world.conversations.stream().filter(c -> c.status.equals("active"))).allSatisfy(conversation -> {
                assertThat(world.residents.stream().filter(actor -> conversation.participantIds.contains(actor.id())))
                    .allSatisfy(actor -> assertThat(actor.place()).isEqualTo(conversation.place));
            });
        }
        assertThat(eventKinds).contains("conversation", "agreement", "arrival");
        assertThat(eventKinds).doesNotContain("contribution", "ready", "new_wish");
        assertThat(world.residentStates).filteredOn(resident->!"self".equals(resident.id)).allMatch(resident->resident.plan==null);
        System.out.println("Companion afternoon: " + eventIds.size() + " events, types=" + eventKinds);
    }

    @Test void aNoMindFallbackIsReplayableAndDoesNotUseWorldIdAsAHiddenDesire() {
        assertThat(trace("same-world")).isEqualTo(trace("same-world"));
        assertThat(new HashSet<>(java.util.List.of(trace("a-world"), trace("b-world"), trace("c-world")))).hasSize(1);
    }

    @Test void returningTheNextDayContinuesHistoryWithoutManufacturingANewPublicActivity() {
        Instant start = Instant.parse("2026-09-08T06:00:00Z");
        CompanionWorld world = CompanionRules.join("next-day", "住客", "Asia/Shanghai", start);
        Set<String> originalProjects = new HashSet<>();
        world.projects.forEach(project -> originalProjects.add(project.id));
        for (int second = 6; second <= 3600; second += 6) CompanionRules.advance(world, start.plusSeconds(second));
        String identity = world.id;
        Instant joined = world.joinedAt;
        CompanionRules.advance(world, start.plusSeconds(86400));
        for (int second = 6; second <= 300; second += 6) CompanionRules.advance(world, start.plusSeconds(86400 + second));
        assertThat(world.id).isEqualTo(identity);
        assertThat(world.joinedAt).isEqualTo(joined);
        assertThat(world.projects).extracting(project -> project.id).containsExactlyInAnyOrderElementsOf(originalProjects);
    }

    private String trace(String seed) {
        Instant start = Instant.parse("2026-09-08T06:00:00Z");
        CompanionWorld world = CompanionRules.join(seed, "住客", "Asia/Shanghai", start);
        Set<String> trace = new LinkedHashSet<>();
        for (int second = 6; second <= 600; second += 6) {
            CompanionRules.advance(world, start.plusSeconds(second));
            world.events.forEach(event -> trace.add(event.at() + " " + event.type() + " " + event.actorIds() + " " + event.projectId()));
        }
        return String.join("\n", trace);
    }
}
