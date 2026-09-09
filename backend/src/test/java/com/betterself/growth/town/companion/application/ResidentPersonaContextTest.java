package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.ResidentSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the three-layer personality text (docs/04-decisions.md's 本我/超我/自我) actually reaches
 * {@link ResidentMind.Context} via {@link ResidentDirector#perspective}, and that wiring it up did not
 * violate any of the hard constraints: text only (never a gate on {@code availableActions}), and never
 * leaking the internal numeric fields ({@code energy}/{@code social}/{@code dutyPressure}/{@code
 * lastSocialAt}) that {@link CompanionWorld.ResidentState} keeps for the rules engine but a model must
 * never see.
 */
class ResidentPersonaContextTest {
    private static final List<String> RESIDENT_IDS = List.of("owner", "student", "artist", "gardener", "fixer", "weaver");

    @Test void sixResidentsCarryDistinctPersonaTextAndTheAvatarCarriesNone() {
        Instant now = Instant.parse("2026-09-09T06:00:00Z");
        var world = CompanionRules.join("persona-context", "体验审阅", "Asia/Shanghai", now, true);
        var director = new ResidentDirector(new SnapshotStore(world), new DisabledMind(), Clock.fixed(now, ZoneOffset.UTC));
        try {
            Set<String> distinctPersonas = new HashSet<>();
            for (String id : RESIDENT_IDS) {
                var persona = director.perspective(world, id, now, List.of()).persona();
                assertThat(persona).as("persona for %s", id).isNotNull();
                // Round-trips ResidentSeed's own authored text verbatim - never rewritten, never guessed.
                var narrative = ResidentSeed.narrative(id);
                assertThat(persona.wantSelf()).isEqualTo(narrative.wantSelf());
                assertThat(persona.oughtSelf()).isEqualTo(narrative.oughtSelf());
                assertThat(persona.actingSelf()).isEqualTo(narrative.actingSelf());
                assertThat(persona.memoryBias()).isEqualTo(narrative.memoryBias());
                assertThat(persona.looseningNote()).isEqualTo(narrative.looseningNote());
                distinctPersonas.add(String.join("|", persona.wantSelf(), persona.oughtSelf(), persona.actingSelf(), persona.memoryBias(), persona.looseningNote()));
            }
            assertThat(distinctPersonas).as("all six residents must have distinct personality text").hasSize(RESIDENT_IDS.size());

            // The avatar ("self") is not one of the six hand-authored residents: no persona text was ever
            // written for it, and none may be guessed - see ResidentSeed.narrative's own contract.
            var selfPersona = director.perspective(world, "self", now, List.of()).persona();
            assertThat(selfPersona).as("avatar persona").isNull();
        } finally {
            director.close();
        }
    }

    @Test void modelFacingContextNeverSerializesInternalNumericFields() throws Exception {
        Instant now = Instant.parse("2026-09-09T06:00:00Z");
        var world = CompanionRules.join("persona-json", "体验审阅", "Asia/Shanghai", now, true);
        var director = new ResidentDirector(new SnapshotStore(world), new DisabledMind(), Clock.fixed(now, ZoneOffset.UTC));
        var json = new ObjectMapper().findAndRegisterModules();
        try {
            for (String id : List.of("owner", "student", "self")) {
                var context = director.perspective(world, id, now, List.of());
                String serialized = json.writeValueAsString(context);
                assertThat(serialized)
                    .as("serialized context for %s", id)
                    .doesNotContain("\"energy\":", "\"social\":", "\"dutyPressure\":", "\"lastSocialAt\":");
            }
        } finally {
            director.close();
        }
    }

    @Test void personaTextNeverChangesWhichActionsAreAvailable() {
        Instant now = Instant.parse("2026-09-09T06:00:00Z");
        var world = CompanionRules.join("persona-gate", "体验审阅", "Asia/Shanghai", now, true);
        var director = new ResidentDirector(new SnapshotStore(world), new DisabledMind(), Clock.fixed(now, ZoneOffset.UTC));
        try {
            for (String id : List.of("owner", "student", "artist", "gardener", "fixer", "weaver", "self")) {
                var context = director.perspective(world, id, now, List.of());
                // availableActions must come straight from the rules engine, exactly as it did before
                // persona text existed - persona is never consulted when computing it (see
                // ResidentDirector.perspective, which passes ResidentSimulation.availableActions(...)
                // through unchanged regardless of whether personaView(id) returned text or null).
                var rulesOnly = com.betterself.growth.town.companion.domain.ResidentSimulation.availableActions(world, id, now);
                assertThat(context.availableActions()).as("availableActions for %s", id).isEqualTo(rulesOnly);
            }
        } finally {
            director.close();
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
