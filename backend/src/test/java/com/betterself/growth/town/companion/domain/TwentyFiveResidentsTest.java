package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The town docs/01-requirements.md 第二版「人」 asks for, checked as a shape rather than as a count.
 *
 * <p>A green suite proves the twenty-five did not break anything. It does not prove they arrived the
 * way they were designed to, and the two failure modes that matter here are both silent: a flat-mate
 * whose {@code homeOf} resolves to a home nobody built (they would be routed to a Location that does
 * not exist every time they went home), and a relationship graph that quietly came out fully connected
 * anyway - which docs/04 names as the thing that would dilute asymmetry to zero and leave diffusion no
 * path to travel: 「现在每对居民都以双向 40 开局，扩到 25 人就是 300 对全部相识」.
 */
class TwentyFiveResidentsTest {
    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");

    private static CompanionWorld town() {
        return CompanionRules.join("twenty-five", "我", "Asia/Shanghai", NOW, true);
    }

    @Test
    @DisplayName("二十五个人真的搬进来了，六个老居民一个没动")
    void theTownHoldsTwentyFiveResidentsAndTheOriginalSixAreUntouched() {
        CompanionWorld w = town();
        assertThat(w.residents).as("25 people, plus the avatar which is not one of them").hasSize(25);
        assertThat(w.residents).extracting(CompanionWorld.Actor::id)
            .contains("owner", "student", "artist", "gardener", "fixer", "weaver")
            .doesNotContain("self");
        for (ResidentPersonas.NewResident person : ResidentPersonas.all())
            assertThat(ResidentSimulation.state(w, person.id())).as(person.id() + " must exist").isNotNull();
    }

    @Test
    @DisplayName("合租的人回的是室友那间屋，不是一个没盖的地址")
    void everyFlatmateHasABedInsideTheHomeTheyActuallyGoHomeTo() {
        CompanionWorld w = town();
        ResidentPersonas.households().forEach((host, members) -> {
            for (String member : members) {
                String home = TownPlaces.homeOf(member);
                assertThat(home).as(member + " lives with " + host).isEqualTo(TownPlaces.homeOf(host));
                assertThat(w.locations).as(home + " must be a real Location")
                    .anyMatch(l -> l.id().equals(home));
                // Sharing a flat must not mean sharing a bed - that four-in-one-bed shape was a real
                // earlier bug, and doing it on purpose here would look identical from the outside.
                assertThat(w.positions).as(member + " needs their own bed inside " + home)
                    .anyMatch(p -> home.equals(p.place) && member.equals(p.ownerId) && "bed".equals(p.kind));
            }
        });
    }

    @Test
    @DisplayName("开局不是三百对全都认识——聚类、稀疏，而且有不对称")
    void theInitialGraphIsClusteredAndSparseRatherThanEveryPairAtAMutualForty() {
        CompanionWorld w = town();
        Set<String> related = new HashSet<>();
        int asymmetric = 0;
        for (CompanionWorld.ResidentState a : w.residentStates) {
            if ("self".equals(a.id)) continue;
            for (CompanionWorld.ResidentState b : w.residentStates) {
                if ("self".equals(b.id) || a.id.equals(b.id)) continue;
                Integer forward = a.relationships.get(b.id), back = b.relationships.get(a.id);
                if (forward == null && back == null) continue;
                related.add(a.id.compareTo(b.id) < 0 ? a.id + "|" + b.id : b.id + "|" + a.id);
                if (forward != null && back != null && !forward.equals(back)) asymmetric++;
            }
        }
        int pairs = 25 * 24 / 2;
        double density = (double) related.size() / pairs;
        assertThat(density).as("聚类之后应当稀疏（论文起点 0.167），而不是 1.0 的全相识")
            .isGreaterThan(0.05).isLessThan(0.45);
        assertThat(asymmetric).as("必须真的有单向更热/更冷的边，否则不对称率天生是 0").isGreaterThan(0);
        // The specific thing that must NOT be true: somebody acquainted with the entire town.
        for (CompanionWorld.ResidentState r : w.residentStates) {
            if ("self".equals(r.id)) continue;
            assertThat(r.relationships.size()).as(r.id + " must not open acquainted with everyone")
                .isLessThan(24);
        }
    }

    @Test
    @DisplayName("十九个新人的人设文本，通过老入口就能读到")
    void thePersonaTextOfTheNineteenIsReachableThroughTheSameAccessorAsTheSix() {
        assertThat(ResidentSeed.narrative("owner")).isNotNull();
        for (ResidentPersonas.NewResident person : ResidentPersonas.all())
            assertThat(ResidentSeed.narrative(person.id())).as(person.id() + "'s persona").isNotNull();
        assertThat(ResidentSeed.narrative("self")).as("the avatar is the user, never authored").isNull();
    }
}
