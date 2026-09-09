package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Covers this batch's three additions to genesis: the two hand-authored newcomers (周野/fixer with
 * his own home, 阿满/weaver sharing 知夏's), and the rules-only passers-by. Personality narrative text
 * itself has no dedicated assertions here beyond existing - it is prose, and its only real test is
 * "does every one of the six residents have it", which {@link #allSixResidentsCarryAuthoredThreeLayerText}
 * covers.
 */
class ResidentSeedTest {
    private static final List<String> SIX = List.of("owner", "student", "artist", "gardener", "fixer", "weaver");

    @Test void sixResidentsEachEndUpWithTheirOwnBedAndTheirOwnDesk() {
        CompanionWorld w = CompanionRules.join("six-residents", "我", "Asia/Shanghai", Instant.parse("2026-09-08T02:00:00Z"), false);
        assertThat(w.residentStates).extracting(r -> r.id).containsAll(SIX);
        assertThat(w.residents).extracting(Actor::id).containsAll(SIX);
        List<String> bedIds = SIX.stream().map(id -> ownedPosition(w, "bed", id).id).toList();
        List<String> deskIds = SIX.stream().map(id -> ownedPosition(w, "desk", id).id).toList();
        // Six real, distinct beds and desks - never fewer than six because someone's furniture was
        // silently skipped (the shape of the old four-in-one-bed bug this batch was warned about).
        assertThat(bedIds).doesNotHaveDuplicates();
        assertThat(deskIds).doesNotHaveDuplicates();
        for (String id : SIX) {
            assertThat(ownedPosition(w, "bed", id).capacity).as(id + "'s bed capacity").isEqualTo(1);
            assertThat(ownedPosition(w, "desk", id).capacity).as(id + "'s desk capacity").isEqualTo(1);
        }
    }

    @Test void weaverAndArtistShareALocationButNeverFurniture() {
        CompanionWorld w = CompanionRules.join("flatmates", "我", "Asia/Shanghai", Instant.parse("2026-09-08T02:00:00Z"), false);
        // Same location, on purpose - homeOf resolves the flat-mate straight to the host's home.
        assertThat(TownPlaces.homeOf("weaver")).isEqualTo("home-artist");
        assertThat(TownPlaces.homeOf("artist")).isEqualTo("home-artist");
        Position artistBed = ownedPosition(w, "bed", "artist"), weaverBed = ownedPosition(w, "bed", "weaver");
        Position artistDesk = ownedPosition(w, "desk", "artist"), weaverDesk = ownedPosition(w, "desk", "weaver");
        assertThat(artistBed.place).isEqualTo("home-artist");
        assertThat(weaverBed.place).isEqualTo("home-artist");
        assertThat(artistDesk.place).isEqualTo("home-artist");
        assertThat(weaverDesk.place).isEqualTo("home-artist");
        // But never the same piece of furniture.
        assertThat(artistBed.id).isNotEqualTo(weaverBed.id);
        assertThat(artistDesk.id).isNotEqualTo(weaverDesk.id);
        // Both can actually claim a bed in the shared home at once - claiming one never evicts the
        // other, which is exactly the distinction from the old bug this batch was warned about.
        Instant now = Instant.parse("2026-09-08T02:00:00Z");
        assertThat(TownPlaces.claim(w, "artist", "home-artist", "bed", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.claim(w, "weaver", "home-artist", "bed", now)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(artistBed.occupantIds).containsExactly("artist");
        assertThat(weaverBed.occupantIds).containsExactly("weaver");
    }

    @Test void allSixResidentsCarryAuthoredThreeLayerText() {
        for (String id : SIX) {
            ResidentSeed.PersonalityNarrative n = ResidentSeed.narrative(id);
            assertThat(n).as(id + "'s narrative").isNotNull();
            assertThat(n.wantSelf()).isNotBlank();
            assertThat(n.oughtSelf()).isNotBlank();
            assertThat(n.actingSelf()).isNotBlank();
            assertThat(n.memoryBias()).isNotBlank();
            assertThat(n.looseningNote()).isNotBlank();
        }
        // The avatar and any future hand-added resident are not guessed at.
        assertThat(ResidentSeed.narrative("self")).isNull();
    }

    @Test void studentAloneCarriesTheSeededAnomalyMemory() {
        CompanionWorld w = CompanionRules.join("secret-seed", "我", "Asia/Shanghai", Instant.parse("2026-09-08T02:00:00Z"), false);
        assertThat(w.memories).anyMatch(m -> "student".equals(m.ownerId()) && "anomaly".equals(m.topicId())
            && m.text().contains("三点到四点") && m.text().contains("没有动过"));
        for (String other : List.of("owner", "artist", "gardener", "fixer", "weaver"))
            assertThat(w.memories).as(other + " must not already know it").noneMatch(m -> "anomaly".equals(m.topicId()) && other.equals(m.ownerId()));
    }

    @Test void passersByAreSeenByWhoeverIsOnTheStreetButNeverGetAModelBrainOrTheirOwnMemory() {
        // 23:55 UTC == 07:55 Shanghai, just before the delivery window.
        CompanionWorld w = CompanionRules.join("passersby", "我", "Asia/Shanghai", Instant.parse("2026-09-08T23:55:00Z"), false);
        for (int i = 0; i < w.residents.size(); i++) {
            Actor a = w.residents.get(i);
            if (a.id().equals("gardener")) w.residents.set(i, new Actor(a.id(), a.name(), a.role(), "street", "observe", "在街上走走", a.x(), a.y(), Instant.parse("2026-09-09T02:00:00Z")));
        }
        // 00:05 UTC == 08:05 Shanghai, inside the delivery window.
        CompanionRules.advance(w, Instant.parse("2026-09-09T00:05:00Z"));
        assertThat(w.events).anyMatch(e -> "passerby_delivery".equals(e.type()) && e.place().equals("street") && e.actorIds().isEmpty());
        assertThat(w.memories).anyMatch(m -> "gardener".equals(m.ownerId()) && "passerby".equals(m.sourceId()) && "observed".equals(m.sourceType()));
        // Nobody who was not on the street picked up a memory of it.
        for (String other : List.of("owner", "student", "artist", "fixer", "weaver"))
            assertThat(w.memories).as(other + " was not on the street").noneMatch(m -> "passerby".equals(m.sourceId()) && other.equals(m.ownerId()));
        // No resident-shaped entity, and therefore no model brain, was ever created for the passer-by.
        assertThat(w.residentStates).noneMatch(r -> r.id.startsWith("passerby"));
        assertThat(w.residents).noneMatch(a -> a.id().startsWith("passerby"));
        assertThat(w.memories).noneMatch(m -> m.ownerId().startsWith("passerby"));
        assertThat(ResidentSimulation.state(w, "passerby-delivery")).isNull();
    }

    private static Position ownedPosition(CompanionWorld w, String kind, String ownerId) {
        return w.positions.stream().filter(p -> kind.equals(p.kind) && ownerId.equals(p.ownerId)).findFirst()
            .orElseThrow(() -> new AssertionError("no " + kind + " owned by " + ownerId));
    }
}
