package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static com.betterself.growth.town.TownSocialSim.Bond;
import static com.betterself.growth.town.TownSocialSim.Encounter;
import static com.betterself.growth.town.TownSocialSim.Persona;
import static com.betterself.growth.town.TownSocialSim.RelayCandidate;
import static com.betterself.growth.town.TownSocialSim.RelayResult;
import static org.assertj.core.api.Assertions.assertThat;

class TownSocialSimTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    private static Persona persona(String code, double shareDrive, double curiosity,
                                    Map<String, Double> interests, Set<String> quirks) {
        return new Persona(code, 2, shareDrive, curiosity, interests, quirks);
    }

    private static Map<String, Double> interests(double knowledge, double health, double career,
                                                   double relationship, double wellbeing) {
        return Map.of(
            "KNOWLEDGE", knowledge,
            "HEALTH", health,
            "CAREER", career,
            "RELATIONSHIP", relationship,
            "WELLBEING", wellbeing
        );
    }

    // ---------------------------------------------------------------- decayedAffinity

    @Test
    void nullLastMetOnDecaysTheBondSubstantially() {
        double decayed = TownSocialSim.decayedAffinity(0.8, null, TODAY);
        assertThat(decayed).isLessThan(0.8).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void halvesAffinityAfterExactlyOneHalfLife() {
        double decayed = TownSocialSim.decayedAffinity(0.8, TODAY.minusDays(14), TODAY);
        assertThat(decayed).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void quartersAffinityAfterTwoHalfLives() {
        double decayed = TownSocialSim.decayedAffinity(0.8, TODAY.minusDays(28), TODAY);
        assertThat(decayed).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void sameDayMeetingDoesNotDecay() {
        double decayed = TownSocialSim.decayedAffinity(0.6, TODAY, TODAY);
        assertThat(decayed).isEqualTo(0.6);
    }

    @Test
    void decayedAffinityNeverExceedsInputAndNeverGoesBelowZero() {
        for (int days = 0; days <= 400; days += 7) {
            double decayed = TownSocialSim.decayedAffinity(0.7, TODAY.minusDays(days), TODAY);
            assertThat(decayed).isLessThanOrEqualTo(0.7).isGreaterThanOrEqualTo(0.0);
        }
        assertThat(TownSocialSim.decayedAffinity(0.0, TODAY.minusDays(100), TODAY)).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------- interestOverlap

    @Test
    void identicalVectorsOverlapCompletely() {
        Map<String, Double> a = interests(0.4, 0.1, 0.2, 0.2, 0.1);
        assertThat(TownSocialSim.interestOverlap(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void disjointVectorsHaveZeroOverlap() {
        Map<String, Double> a = Map.of("KNOWLEDGE", 1.0);
        Map<String, Double> b = Map.of("HEALTH", 1.0);
        assertThat(TownSocialSim.interestOverlap(a, b)).isEqualTo(0.0);
    }

    @Test
    void missingDimensionsAreTreatedAsZeroWeight() {
        Map<String, Double> a = Map.of("KNOWLEDGE", 1.0);
        Map<String, Double> b = Map.of("KNOWLEDGE", 1.0, "HEALTH", 1.0);
        double overlap = TownSocialSim.interestOverlap(a, b);
        assertThat(overlap).isCloseTo(1.0 / Math.sqrt(2.0), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void interestOverlapIsAlwaysInZeroToOne() {
        SplittableRandom rng = new SplittableRandom(7);
        for (int i = 0; i < 200; i++) {
            Map<String, Double> a = interests(rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                rng.nextDouble(), rng.nextDouble());
            Map<String, Double> b = interests(rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                rng.nextDouble(), rng.nextDouble());
            double overlap = TownSocialSim.interestOverlap(a, b);
            assertThat(overlap).isBetween(0.0, 1.0);
        }
    }

    // ---------------------------------------------------------------- afterMeeting

    @Test
    void afterMeetingIncrementsMeetCount() {
        Bond bond = new Bond(0.3, 0.2, 4, TODAY.minusDays(3));
        Persona a = persona("A", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona b = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());

        Bond updated = TownSocialSim.afterMeeting(bond, a, b, TODAY, new SplittableRandom(1));

        assertThat(updated.meetCount()).isEqualTo(5);
        assertThat(updated.lastMetOn()).isEqualTo(TODAY);
    }

    @Test
    void affinityGainsDiminishAsItApproachesOne() {
        Persona a = persona("A", 0.6, 0.6, interests(0.3, 0.2, 0.2, 0.2, 0.1), Set.of());
        Persona b = persona("B", 0.6, 0.6, interests(0.3, 0.2, 0.2, 0.2, 0.1), Set.of());

        Bond lowStart = new Bond(0.1, 0.0, 0, null);
        Bond highStart = new Bond(0.9, 0.0, 0, null);

        // Same seed on two fresh generators => the jitter draw is identical for both calls,
        // so any difference in the resulting gain is purely the diminishing-returns term.
        double gainFromLow = TownSocialSim.afterMeeting(lowStart, a, b, TODAY, new SplittableRandom(99)).affinity()
            - lowStart.affinity();
        double gainFromHigh = TownSocialSim.afterMeeting(highStart, a, b, TODAY, new SplittableRandom(99)).affinity()
            - highStart.affinity();

        assertThat(gainFromLow).isGreaterThan(gainFromHigh);
    }

    @Test
    void affinityRandomJitterIsBoundedByTwoHundredthsAcrossManySeeds() {
        Persona a = persona("A", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona b = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.5, 0.0, 0, null);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (long seed = 0; seed < 500; seed++) {
            double affinity = TownSocialSim.afterMeeting(bond, a, b, TODAY, new SplittableRandom(seed)).affinity();
            min = Math.min(min, affinity);
            max = Math.max(max, affinity);
        }

        // The only source of variance across seeds is the jitter term, which is bounded in
        // absolute value by 0.02 — so the full spread across many seeds cannot exceed 0.04.
        assertThat(max - min).isLessThanOrEqualTo(0.04 + 1e-9);
    }

    @Test
    void afterMeetingAffinityAlwaysStaysInZeroToOne() {
        Persona a = persona("A", 1.0, 1.0, interests(1.0, 0.0, 0.0, 0.0, 0.0), Set.of());
        Persona b = persona("B", 1.0, 1.0, interests(1.0, 0.0, 0.0, 0.0, 0.0), Set.of());
        for (long seed = 0; seed < 200; seed++) {
            Bond nearMax = new Bond(0.999, 0.0, 0, null);
            Bond nearMin = new Bond(0.001, 0.0, 0, null);
            assertThat(TownSocialSim.afterMeeting(nearMax, a, b, TODAY, new SplittableRandom(seed)).affinity())
                .isBetween(0.0, 1.0);
            assertThat(TownSocialSim.afterMeeting(nearMin, a, b, TODAY, new SplittableRandom(seed)).affinity())
                .isBetween(0.0, 1.0);
        }
    }

    // ---------------------------------------------------------------- dailySalienceDecay

    @Test
    void higherHopsDecayFasterThanLowerHops() {
        double hop0 = TownSocialSim.dailySalienceDecay(0.8, 0);
        double hop2 = TownSocialSim.dailySalienceDecay(0.8, 2);
        double hop5 = TownSocialSim.dailySalienceDecay(0.8, 5);

        assertThat(hop2).isLessThan(hop0);
        assertThat(hop5).isLessThan(hop2);
    }

    @Test
    void dailySalienceDecayStaysBetweenZeroAndTheInput() {
        for (int hops = 0; hops < 20; hops++) {
            double decayed = TownSocialSim.dailySalienceDecay(0.5, hops);
            assertThat(decayed).isBetween(0.0, 0.5);
        }
    }

    // ---------------------------------------------------------------- relayProbability

    private static RelayCandidate candidate(double salience) {
        return new RelayCandidate("A", "B", 1L, "KNOWLEDGE", 0, salience, null);
    }

    @Test
    void relayProbabilityIsMonotonicInAffinity() {
        Persona speaker = persona("A", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona listener = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        RelayCandidate c = candidate(0.5);

        double previous = -1;
        for (double affinity = 0.0; affinity <= 1.0; affinity += 0.1) {
            Bond bond = new Bond(affinity, 0.0, 0, null);
            double p = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5);
            assertThat(p).isBetween(0.0, 1.0);
            assertThat(p).isGreaterThanOrEqualTo(previous - 1e-9);
            previous = p;
        }
    }

    @Test
    void relayProbabilityIsMonotonicInSpeakerShareDrive() {
        Persona listener = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.5, 0.0, 0, null);
        RelayCandidate c = candidate(0.5);

        double previous = -1;
        for (double shareDrive = 0.0; shareDrive <= 1.0; shareDrive += 0.1) {
            Persona speaker = persona("A", shareDrive, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
            double p = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5);
            assertThat(p).isBetween(0.0, 1.0);
            assertThat(p).isGreaterThanOrEqualTo(previous - 1e-9);
            previous = p;
        }
    }

    @Test
    void relayProbabilityIsMonotonicInListenerCuriosity() {
        Persona speaker = persona("A", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.5, 0.0, 0, null);
        RelayCandidate c = candidate(0.5);

        double previous = -1;
        for (double curiosity = 0.0; curiosity <= 1.0; curiosity += 0.1) {
            Persona listener = persona("B", 0.5, curiosity, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
            double p = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5);
            assertThat(p).isBetween(0.0, 1.0);
            assertThat(p).isGreaterThanOrEqualTo(previous - 1e-9);
            previous = p;
        }
    }

    @Test
    void relayProbabilityIsMonotonicInInterestOverlap() {
        Persona speaker = persona("A", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona listener = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.5, 0.0, 0, null);
        RelayCandidate c = candidate(0.5);

        double previous = -1;
        for (double overlap = 0.0; overlap <= 1.0; overlap += 0.1) {
            double p = TownSocialSim.relayProbability(c, speaker, listener, bond, overlap);
            assertThat(p).isBetween(0.0, 1.0);
            assertThat(p).isGreaterThanOrEqualTo(previous - 1e-9);
            previous = p;
        }
    }

    @Test
    void relayProbabilityIsMonotonicInSalience() {
        Persona speaker = persona("A", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona listener = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.5, 0.0, 0, null);

        double previous = -1;
        for (double salience = 0.0; salience <= 1.0; salience += 0.1) {
            double p = TownSocialSim.relayProbability(candidate(salience), speaker, listener, bond, 0.5);
            assertThat(p).isBetween(0.0, 1.0);
            assertThat(p).isGreaterThanOrEqualTo(previous - 1e-9);
            previous = p;
        }
    }

    @Test
    void gossipHubQuirkRaisesProbabilityAndTightLippedLowersIt() {
        Persona listener = persona("B", 0.5, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.4, 0.0, 0, null);
        RelayCandidate c = candidate(0.4);

        Persona plain = persona("A", 0.4, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona gossipHub = persona("A", 0.4, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of("GOSSIP_HUB"));
        Persona tightLipped = persona("A", 0.4, 0.5, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of("TIGHT_LIPPED"));

        double plainP = TownSocialSim.relayProbability(c, plain, listener, bond, 0.5);
        double gossipP = TownSocialSim.relayProbability(c, gossipHub, listener, bond, 0.5);
        double tightP = TownSocialSim.relayProbability(c, tightLipped, listener, bond, 0.5);

        assertThat(gossipP).isGreaterThan(plainP);
        assertThat(tightP).isLessThan(plainP);
    }

    // ---------------------------------------------------------------- simulate

    private static Map<String, Persona> twoPersonTown() {
        return Map.of(
            "A", persona("A", 1.0, 1.0, interests(1.0, 0.0, 0.0, 0.0, 0.0), Set.of()),
            "B", persona("B", 1.0, 1.0, interests(1.0, 0.0, 0.0, 0.0, 0.0), Set.of())
        );
    }

    private static Bond guaranteedRelayBond() {
        return new Bond(1.0, 0.0, 0, null);
    }

    @Test
    void simulateIsDeterministicForTheSameSeed() {
        List<Encounter> encounters = List.of(
            new Encounter("A", "B", "plaza", 9, TownSocialSim.EncounterType.CO_LOCATED),
            new Encounter("B", "C", "cafe", 12, TownSocialSim.EncounterType.CO_LOCATED),
            new Encounter("A", "C", "park", 15, TownSocialSim.EncounterType.CO_LOCATED)
        );
        Map<String, Persona> personas = Map.of(
            "A", persona("A", 0.7, 0.4, interests(0.3, 0.2, 0.2, 0.2, 0.1), Set.of("GOSSIP_HUB")),
            "B", persona("B", 0.5, 0.6, interests(0.2, 0.3, 0.2, 0.2, 0.1), Set.of()),
            "C", persona("C", 0.3, 0.8, interests(0.1, 0.1, 0.3, 0.3, 0.2), Set.of("TIGHT_LIPPED"))
        );
        java.util.function.BiFunction<String, String, Bond> bonds =
            (a, b) -> new Bond(0.5, 0.2, 3, TODAY.minusDays(2));
        java.util.function.Function<String, List<RelayCandidate>> known = npc -> "A".equals(npc)
            ? List.of(new RelayCandidate("A", null, 1L, "KNOWLEDGE", 0, 0.8, "text"),
                      new RelayCandidate("A", null, 2L, "HEALTH", 1, 0.5, "text2"))
            : List.of();

        List<RelayResult> first = TownSocialSim.simulate(encounters, personas, bonds, known, new SplittableRandom(42));
        List<RelayResult> second = TownSocialSim.simulate(encounters, personas, bonds, known, new SplittableRandom(42));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void listenerNeverReceivesTheSameFactTwice() {
        List<Encounter> encounters = List.of(
            new Encounter("A", "B", "plaza", 9, TownSocialSim.EncounterType.CO_LOCATED),
            new Encounter("A", "B", "plaza", 10, TownSocialSim.EncounterType.CO_LOCATED)
        );
        Map<String, Persona> personas = twoPersonTown();
        java.util.function.BiFunction<String, String, Bond> bonds = (a, b) -> guaranteedRelayBond();
        java.util.function.Function<String, List<RelayCandidate>> known = npc -> "A".equals(npc)
            ? List.of(new RelayCandidate("A", null, 1L, "KNOWLEDGE", 0, 1.0, null))
            : List.of();

        // affinity=1, shareDrive=1, curiosity=1, overlap=1, salience=1 => probability == 1.0,
        // so the relay succeeds on the very first encounter deterministically.
        List<RelayResult> results = TownSocialSim.simulate(encounters, personas, bonds, known, new SplittableRandom(1));

        long factOneCount = results.stream().filter(r -> r.factId() == 1L && "B".equals(r.listener())).count();
        assertThat(factOneCount).isEqualTo(1);
    }

    @Test
    void hopsIsAlwaysSpeakerHopsPlusOne() {
        List<Encounter> encounters = List.of(new Encounter("A", "B", "plaza", 9, TownSocialSim.EncounterType.CO_LOCATED));
        Map<String, Persona> personas = twoPersonTown();
        java.util.function.BiFunction<String, String, Bond> bonds = (a, b) -> guaranteedRelayBond();
        java.util.function.Function<String, List<RelayCandidate>> known = npc -> "A".equals(npc)
            ? List.of(new RelayCandidate("A", null, 7L, "CAREER", 2, 1.0, null))
            : List.of();

        List<RelayResult> results = TownSocialSim.simulate(encounters, personas, bonds, known, new SplittableRandom(5));

        List<RelayResult> matching = new ArrayList<>();
        for (RelayResult r : results) {
            if (r.factId() == 7L) {
                matching.add(r);
            }
        }
        assertThat(matching).hasSize(1);
        assertThat(matching.get(0).hops()).isEqualTo(3);
    }

    @Test
    void aMeetingNeverLowersAffinityNoMatterTheSeed() {
        // M1-5 的第一条验收就是「日程重合上升」。收益随 affinity 递减，到高位时会小于扰动幅度，
        // 所以这条必须对整个取值范围和大量种子都成立，不能只在低位试一把。
        Persona a = new Persona("A", 2, 0.5, 0.5,
            Map.of("KNOWLEDGE", 0.6, "HEALTH", 0.1, "CAREER", 0.1, "RELATIONSHIP", 0.1, "WELLBEING", 0.1),
            Set.of());
        Persona b = new Persona("B", 2, 0.5, 0.5,
            Map.of("KNOWLEDGE", 0.5, "HEALTH", 0.2, "CAREER", 0.1, "RELATIONSHIP", 0.1, "WELLBEING", 0.1),
            Set.of());
        LocalDate today = LocalDate.of(2026, 9, 6);

        for (double affinity : new double[]{0.0, 0.1, 0.3, 0.6, 0.7, 0.9, 0.99, 1.0}) {
            for (long seed = 0; seed < 500; seed++) {
                Bond before = new Bond(affinity, 0.2, 3, today.minusDays(1));
                Bond after = TownSocialSim.afterMeeting(before, a, b, today, new SplittableRandom(seed));
                assertThat(after.affinity())
                    .as("affinity=%s seed=%s must not go down after a meeting", affinity, seed)
                    .isGreaterThanOrEqualTo(before.affinity());
                assertThat(after.affinity()).isBetween(0.0, 1.0);
            }
        }
    }

    // ---------------------------------------------------------------- encounter type weight (M7-5)

    @Test
    void coLocatedOutranksEnRouteOutranksPassingWithEverythingElseFixed() {
        // CONTRACT-M7.md §3: 其余条件相同时 P(CO_LOCATED) >= P(EN_ROUTE) >= P(PASSING)。
        Persona speaker = persona("A", 0.6, 0.6, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of("GOSSIP_HUB"));
        Persona listener = persona("B", 0.6, 0.6, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.6, 0.0, 0, null);
        RelayCandidate c = candidate(0.6);

        double coLocated = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5,
            TownSocialSim.EncounterType.CO_LOCATED);
        double enRoute = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5,
            TownSocialSim.EncounterType.EN_ROUTE);
        double passing = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5,
            TownSocialSim.EncounterType.PASSING);

        assertThat(coLocated).isGreaterThanOrEqualTo(enRoute);
        assertThat(enRoute).isGreaterThanOrEqualTo(passing);
        assertThat(passing).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void fiveArgOverloadIsEquivalentToCoLocated() {
        // 没有指定相遇类型的旧签名（仍被其余单测大量使用）必须等价于"同处停留"——
        // 否则新增权重参数会悄悄改变所有既有调用点的语义。
        Persona speaker = persona("A", 0.4, 0.4, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Persona listener = persona("B", 0.4, 0.4, interests(0.2, 0.2, 0.2, 0.2, 0.2), Set.of());
        Bond bond = new Bond(0.4, 0.0, 0, null);
        RelayCandidate c = candidate(0.4);

        double viaOldOverload = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5);
        double viaExplicitType = TownSocialSim.relayProbability(c, speaker, listener, bond, 0.5,
            TownSocialSim.EncounterType.CO_LOCATED);

        assertThat(viaOldOverload).isEqualTo(viaExplicitType);
    }
}
