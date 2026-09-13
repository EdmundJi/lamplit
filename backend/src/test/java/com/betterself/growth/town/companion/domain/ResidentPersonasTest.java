package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Covers the nineteen new personas as pure data: nobody is missing a field, nobody collides with an
 * existing id, the household/occupation/relationship accessors are internally consistent with each
 * other, and the relationship graph is the sparse, clustered shape docs/01-requirements.md 「初始关系
 * 按住所／职业聚类，其余互不相识」 asks for rather than the old all-pairs-40 shape. None of this touches
 * {@code CompanionWorld} or {@code ResidentSimulation} - these nineteen are not wired into a running
 * world yet (see {@link ResidentPersonas}'s class javadoc), so there is nothing here about seating,
 * homes actually existing, or a simulated day.
 */
class ResidentPersonasTest {
    private static final Set<String> EXISTING_SIX = Set.of("owner", "student", "artist", "gardener", "fixer", "weaver");

    @Test void exactlyNineteenNewIdsNoneOfWhichCollideWithAnExistingResidentOrSelf() {
        List<ResidentPersonas.NewResident> all = ResidentPersonas.all();
        assertThat(all).hasSize(19);
        Set<String> ids = new LinkedHashSet<>(ResidentPersonas.ids());
        assertThat(ids).hasSize(19); // no duplicate ids among the nineteen
        for (String id : ids) {
            assertThat(id).as(id + " must look like a resident id").matches("[a-z][a-z0-9-]{1,30}");
            assertThat(EXISTING_SIX).as(id + " must not shadow an existing resident").doesNotContain(id);
            assertThat(id).isNotEqualTo("self");
        }
    }

    @Test void everyNewResidentCarriesAFullyAuthoredThreeLayerNarrativeWithNoBlankField() {
        for (ResidentPersonas.NewResident r : ResidentPersonas.all()) {
            assertThat(r.name()).as(r.id() + "'s name").isNotBlank();
            assertThat(r.role()).as(r.id() + "'s role").isNotBlank();
            assertThat(r.occupation()).as(r.id() + "'s occupation").isNotBlank();
            assertThat(r.building()).as(r.id() + "'s building").isIn("cafe", "garden", "academy", "gym", "board", "shop");
            assertThat(r.lifeStage()).as(r.id() + "'s life stage").isNotNull();
            assertThat(r.householdId()).as(r.id() + "'s household").isNotBlank();
            ResidentSeed.PersonalityNarrative n = r.narrative();
            assertThat(n).as(r.id() + "'s narrative").isNotNull();
            assertThat(n.wantSelf()).as(r.id() + " wantSelf").isNotBlank();
            assertThat(n.oughtSelf()).as(r.id() + " oughtSelf").isNotBlank();
            assertThat(n.actingSelf()).as(r.id() + " actingSelf").isNotBlank();
            assertThat(n.memoryBias()).as(r.id() + " memoryBias").isNotBlank();
            assertThat(n.looseningNote()).as(r.id() + " looseningNote").isNotBlank();
            // Text, never a score: no digit should ever appear inside the authored persona prose.
            for (String field : List.of(n.wantSelf(), n.oughtSelf(), n.actingSelf(), n.memoryBias(), n.looseningNote()))
                assertThat(field).as(r.id() + "'s narrative must contain no digits").doesNotContainPattern("\\d");
        }
    }

    @Test void of_returnsNullForAnExistingResidentOrAnUnknownId() {
        assertThat(ResidentPersonas.of("owner")).isNull();
        assertThat(ResidentPersonas.of("self")).isNull();
        assertThat(ResidentPersonas.of("nobody-by-this-name")).isNull();
        assertThat(ResidentPersonas.of("barista")).isNotNull();
    }

    @Test void householdsPartitionAllNineteenAndEveryMemberAgreesWithItsOwnHouseholdIdField() {
        Map<String, List<String>> households = ResidentPersonas.households();
        Set<String> seen = new LinkedHashSet<>();
        for (var entry : households.entrySet()) {
            String host = entry.getKey();
            List<String> members = entry.getValue();
            assertThat(members).as(host + "'s household must list the host first").isNotEmpty();
            assertThat(members.get(0)).isEqualTo(host);
            for (String member : members) {
                assertThat(seen.add(member)).as(member + " must not appear in two households").isTrue();
                ResidentPersonas.NewResident r = ResidentPersonas.of(member);
                assertThat(r).as(member + " must be one of the nineteen").isNotNull();
                assertThat(r.householdId()).as(member + "'s householdId field").isEqualTo(host);
            }
            // A household of one is a resident living alone; anything bigger only ever holds young
            // residents together, or exactly two older residents paired up (docs/01's two housing
            // shapes) - never a lone household claiming to be a multi-person flat.
            if (members.size() > 1) {
                boolean allYoung = members.stream().allMatch(id -> ResidentPersonas.of(id).lifeStage() == ResidentPersonas.LifeStage.YOUNG);
                boolean olderPair = members.size() == 2 && members.stream().allMatch(id -> ResidentPersonas.of(id).lifeStage() == ResidentPersonas.LifeStage.OLDER);
                assertThat(allYoung || olderPair).as(host + "'s household must be an all-young flat or an older pair").isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ResidentPersonas.ids());
    }

    @Test void occupationClustersCoverTheSixPublicBuildingsWithFourResidentsEach() {
        Map<String, List<String>> clusters = ResidentPersonas.occupationClusters();
        assertThat(clusters.keySet()).containsExactlyInAnyOrder("cafe", "garden", "academy", "gym", "board", "shop");
        Set<String> allMembers = new LinkedHashSet<>();
        for (var entry : clusters.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey() + " cluster size").hasSize(4);
            allMembers.addAll(entry.getValue());
        }
        // 24 of the 25 residents (six existing + nineteen new) land in exactly one building; 知夏
        // (artist) is the one deliberately left out, matching how ResidentSeed already writes her
        // occupation with no building tie.
        assertThat(allMembers).hasSize(24);
        assertThat(allMembers).doesNotContain("artist", "self");
        for (String id : ResidentPersonas.ids())
            assertThat(clusters.get(ResidentPersonas.of(id).building())).as(id + " must appear in its own building's cluster").contains(id);
    }

    @Test void initialRelationshipsAreSparseAndClusteredRatherThanEveryoneKnowingEveryone() {
        Map<String, Map<String, Integer>> rel = ResidentPersonas.initialRelationships();
        // Every id that appears is a real resident (new or one of the six/existing occupation anchors).
        Set<String> known = new LinkedHashSet<>(ResidentPersonas.ids());
        known.addAll(EXISTING_SIX);
        int directedEdges = 0;
        for (var entry : rel.entrySet()) {
            assertThat(known).as(entry.getKey() + " must be a real resident").contains(entry.getKey());
            for (var to : entry.getValue().entrySet()) {
                assertThat(known).as(to.getKey() + " must be a real resident").contains(to.getKey());
                assertThat(to.getKey()).as(entry.getKey() + " cannot be acquainted with themselves").isNotEqualTo(entry.getKey());
                assertThat(to.getValue()).as(entry.getKey() + "->" + to.getKey()).isBetween(0, 100);
                directedEdges++;
            }
        }
        // Two strangers - nobody who shares neither a household nor a building - have no edge in
        // either direction (a stranger by omission, not by an authored zero).
        assertThat(rel.getOrDefault("scholar", Map.of())).doesNotContainKey("trader");
        assertThat(rel.getOrDefault("trader", Map.of())).doesNotContainKey("scholar");
        assertThat(rel.getOrDefault("baker", Map.of())).doesNotContainKey("scribe");
        // At least one hand-authored asymmetric pair actually differs by direction - the whole point
        // of not diluting the asymmetry-rate metric toward zero.
        assertThat(rel.get("baker").get("beekeeper")).isNotEqualTo(rel.get("beekeeper").get("baker"));
        assertThat(rel.get("broker").get("messenger")).isNotEqualTo(rel.get("messenger").get("broker"));
        // Density well below the old "every pair mutual 40" shape (a full 25-person town would be
        // 300/300 = 1.0): docs/01 cites the paper's own starting density of 0.167 as the reference
        // point. Undirected pairs = distinct unordered {a,b} keys seen across all directed edges.
        Set<String> unorderedPairs = new LinkedHashSet<>();
        rel.forEach((from, edges) -> edges.keySet().forEach(to -> unorderedPairs.add(from.compareTo(to) < 0 ? from + "|" + to : to + "|" + from)));
        double density = unorderedPairs.size() / 300.0; // C(25,2) across the full 25-person town
        assertThat(directedEdges).isGreaterThanOrEqualTo(unorderedPairs.size()); // every pair has at least one direction authored
        assertThat(density).isLessThan(0.5);
        assertThat(density).isGreaterThan(0.05);
    }
}
