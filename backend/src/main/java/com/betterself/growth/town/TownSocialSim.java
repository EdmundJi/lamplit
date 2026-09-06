package com.betterself.growth.town;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * The deterministic, side-effect-free heart of the town's gossip-propagation model.
 * <p>
 * Everything in here is a pure function of its arguments plus a caller-supplied
 * {@link RandomGenerator} — no clock, no database, no Spring. That separation from
 * persistence is deliberate: the nightly job ({@code TownSocietyJob}) is the only place
 * that reads/writes state, while this class only ever answers "given today's meetings and
 * everyone's personality, who told whom what, and how likely was it to happen". Fixing the
 * seed handed to {@link #simulate} therefore reproduces an entire day's gossip chain byte
 * for byte, which is what lets {@code TownSocietyJob} be re-run idempotently and what lets
 * this class be unit-tested without a database in sight.
 */
public final class TownSocialSim {

    private static final double AFFINITY_HALF_LIFE_DAYS = 14.0;
    // "Never met" has no last-met date to measure from; treat it as long enough ago that
    // the bond has decayed to essentially nothing, rather than special-casing it.
    private static final long NEVER_MET_SENTINEL_DAYS = 3650L;

    private static final double AFFINITY_BASE_GAIN = 0.08;
    private static final double AFFINITY_MAX_JITTER = 0.02;
    private static final double RESONANCE_SMOOTHING = 0.10;

    private static final double SALIENCE_BASE_DAILY_DECAY = 0.10;
    private static final double SALIENCE_PER_HOP_DECAY = 0.05;

    private static final double RELAY_BASELINE = 0.05;
    private static final double RELAY_WEIGHT_AFFINITY = 0.35;
    private static final double RELAY_WEIGHT_SHARE_DRIVE = 0.20;
    private static final double RELAY_WEIGHT_CURIOSITY = 0.15;
    private static final double RELAY_WEIGHT_OVERLAP = 0.15;
    private static final double RELAY_WEIGHT_SALIENCE = 0.10;
    private static final double GOSSIP_HUB_MULTIPLIER = 1.25;
    private static final double TIGHT_LIPPED_MULTIPLIER = 0.6;

    private TownSocialSim() {
    }

    /**
     * How two NPCs' timelines crossed (CONTRACT-M7.md §3). {@code CO_LOCATED} means both were
     * {@code AT} the same place at the same time — there was time to sit down and talk, so it
     * carries the highest relay weight. {@code EN_ROUTE} is two people walking past each other
     * mid-commute — a few words in passing. {@code PASSING} is one person standing still while
     * the other merely walks through that spot — barely a nod. The weight multipliers below are
     * the contract's numbers verbatim; nobody downstream should hardcode a second copy of them.
     */
    public enum EncounterType {
        CO_LOCATED, EN_ROUTE, PASSING
    }

    private static final Map<EncounterType, Double> ENCOUNTER_TYPE_WEIGHT = Map.of(
        EncounterType.CO_LOCATED, 1.0,
        EncounterType.EN_ROUTE, 0.6,
        EncounterType.PASSING, 0.3
    );

    /** A personality facet of one NPC, projected out of {@code town_npc}. */
    public record Persona(String npcCode, int layer, double shareDrive, double curiosity,
                    Map<String, Double> interests, Set<String> quirks) {
    }

    /** One directed edge of the relationship graph. */
    public record Bond(double affinity, double resonance, int meetCount, LocalDate lastMetOn) {
    }

    /**
     * One meeting between two townsfolk, classified by {@link EncounterType}. {@code atMinute}
     * is local-time-of-day minutes ({@code [0,1440)}), replacing the old hour-slot index now
     * that meetings come out of {@link TownDayPlan}'s minute-granular timelines rather than the
     * seven-slot hourly schedule.
     */
    public record Encounter(String a, String b, String place, int atMinute, EncounterType type) {
    }

    /** A candidate relay: someone who could tell someone else a fact they already know. */
    public record RelayCandidate(String speaker, String listener, long factId, String dimension,
                            int speakerHops, double salience, String previousText) {
    }

    /** The outcome of a relay that actually happened. */
    public record RelayResult(long factId, String listener, String speaker, int hops, double salience) {
    }

    /**
     * Applies time decay to a symmetric bond's affinity. No decay happens the same day a
     * meeting occurred; from the day after, affinity halves every {@value #AFFINITY_HALF_LIFE_DAYS}
     * days. {@code lastMetOn == null} (no recorded meeting at all) decays as if it had been
     * an extremely long time, since there is nothing to measure "recently" against.
     */
    public static double decayedAffinity(double affinity, LocalDate lastMetOn, LocalDate today) {
        if (affinity <= 0) {
            return 0.0;
        }
        long daysSince = lastMetOn == null ? NEVER_MET_SENTINEL_DAYS : ChronoUnit.DAYS.between(lastMetOn, today);
        if (daysSince <= 0) {
            return affinity;
        }
        double decayed = affinity * Math.pow(0.5, daysSince / AFFINITY_HALF_LIFE_DAYS);
        return Math.max(0.0, Math.min(affinity, decayed));
    }

    /**
     * Cosine similarity between two five-dimension interest-weight vectors. A dimension
     * missing from either map is treated as weight 0. A zero vector on either side has no
     * defined direction, so overlap is defined as 0 rather than dividing by zero.
     */
    public static double interestOverlap(Map<String, Double> a, Map<String, Double> b) {
        Set<String> dimensions = new HashSet<>(a.keySet());
        dimensions.addAll(b.keySet());

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (String dimension : dimensions) {
            double va = a.getOrDefault(dimension, 0.0);
            double vb = b.getOrDefault(dimension, 0.0);
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        double cosine = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return clamp01(cosine);
    }

    /**
     * Updates a bond after a meeting between {@code a} and {@code b}. Affinity rises toward
     * 1 with diminishing returns ({@code affinity += (1 - affinity) * base}); {@code base}
     * scales with how much the two NPCs' interests overlap and how curious both are, so two
     * curious people who like the same things click faster than two indifferent strangers.
     * A bounded random jitter (at most {@value #AFFINITY_MAX_JITTER} in absolute value) is
     * layered on top so identical meetings do not all raise affinity by exactly the same
     * amount. Resonance drifts toward the interest overlap itself, smoothed rather than
     * jumped to, since "how well we vibe" should not swing wildly from a single meeting.
     */
    public static Bond afterMeeting(Bond bond, Persona a, Persona b, LocalDate today, RandomGenerator rng) {
        double overlap = interestOverlap(a.interests(), b.interests());
        double avgCuriosity = (a.curiosity() + b.curiosity()) / 2.0;
        double base = AFFINITY_BASE_GAIN * (0.5 + 0.5 * overlap) * (0.5 + 0.5 * avgCuriosity);
        double jitter = (rng.nextDouble() * 2.0 - 1.0) * AFFINITY_MAX_JITTER;

        // 见了一面之后不该反而生分。收益本身随 affinity 递减（(1-a) 那一项），到高位时会小于
        // 扰动幅度，于是"见面"有可能算出一个更低的值——那是 M1-5「日程重合上升」的反例。扰动
        // 只用来让涨幅有快有慢，不允许把符号翻过去，所以这里把增量夹在 0 以上。
        double gain = Math.max(0.0, (1.0 - bond.affinity()) * base + jitter);
        double newAffinity = clamp01(bond.affinity() + gain);
        double newResonance = clamp01(bond.resonance() + (overlap - bond.resonance()) * RESONANCE_SMOOTHING);

        return new Bond(newAffinity, newResonance, bond.meetCount() + 1, today);
    }

    /**
     * One day's worth of forgetting. Facts heard second- or third-hand fade faster than
     * things witnessed directly — {@code hops} steepens the decay rate — but this only ever
     * shrinks salience, never grows it.
     */
    public static double dailySalienceDecay(double salience, int hops) {
        double rate = Math.min(1.0, SALIENCE_BASE_DAILY_DECAY + Math.max(0, hops) * SALIENCE_PER_HOP_DECAY);
        double decayed = salience * (1.0 - rate);
        return Math.max(0.0, Math.min(salience, decayed));
    }

    /**
     * Probability that {@code speaker} relays candidate {@code c} to {@code listener} at
     * this meeting. Built as a weighted sum of bounded [0,1] signals plus a small baseline,
     * so raising any one signal (affinity, the speaker's share drive, the listener's
     * curiosity, interest overlap, or the fact's salience) while holding everything else
     * fixed can only raise or hold the probability — never lower it. A speaker who is a
     * {@code GOSSIP_HUB} multiplies the whole score up; a {@code TIGHT_LIPPED} speaker
     * multiplies it down. Facts marked {@code no_relay} must never reach this method — that
     * filtering is the caller's job, not this one's.
     */
    public static double relayProbability(RelayCandidate c, Persona speaker, Persona listener, Bond bond,
                                    double interestOverlap) {
        return relayProbability(c, speaker, listener, bond, interestOverlap, EncounterType.CO_LOCATED);
    }

    /**
     * Same as the five-argument overload, but scaled by how the two speakers actually crossed
     * paths (CONTRACT-M7.md §3 / M7-5). The multiplier is applied last and is itself in
     * {@code (0,1]}, so it can only ever lower the probability relative to a {@code CO_LOCATED}
     * meeting — never raise it — which is what guarantees
     * {@code P(CO_LOCATED) >= P(EN_ROUTE) >= P(PASSING)} for every other signal held fixed.
     */
    public static double relayProbability(RelayCandidate c, Persona speaker, Persona listener, Bond bond,
                                    double interestOverlap, EncounterType encounterType) {
        double score = RELAY_BASELINE
            + RELAY_WEIGHT_AFFINITY * clamp01(bond.affinity())
            + RELAY_WEIGHT_SHARE_DRIVE * clamp01(speaker.shareDrive())
            + RELAY_WEIGHT_CURIOSITY * clamp01(listener.curiosity())
            + RELAY_WEIGHT_OVERLAP * clamp01(interestOverlap)
            + RELAY_WEIGHT_SALIENCE * clamp01(c.salience());

        if (speaker.quirks().contains("GOSSIP_HUB")) {
            score *= GOSSIP_HUB_MULTIPLIER;
        }
        if (speaker.quirks().contains("TIGHT_LIPPED")) {
            score *= TIGHT_LIPPED_MULTIPLIER;
        }
        score *= ENCOUNTER_TYPE_WEIGHT.get(encounterType);
        return clamp01(score);
    }

    /**
     * Runs one day's propagation over a fixed sequence of encounters. Every random decision
     * is drawn from {@code rng}, and encounters/candidates are always walked in the order
     * they were given, so the same inputs and the same seeded generator reproduce byte-for-byte
     * identical output — that reproducibility is the whole point of keeping this class pure.
     * <p>
     * Within one call, a listener can only ever pick up a given {@code factId} once; a fact
     * picked up mid-run immediately becomes something that NPC can pass on to whoever they
     * meet next in the same encounter list, with {@code hops} one more than however many
     * hops it had already travelled.
     */
    public static List<RelayResult> simulate(List<Encounter> encounters,
                                       Map<String, Persona> personas,
                                       BiFunction<String, String, Bond> bonds,
                                       Function<String, List<RelayCandidate>> knownBySpeaker,
                                       RandomGenerator rng) {
        Map<String, Map<Long, Learned>> knowledge = new LinkedHashMap<>();
        List<RelayResult> results = new ArrayList<>();

        for (Encounter encounter : encounters) {
            attemptRelay(encounter.a(), encounter.b(), encounter.type(), personas, bonds, knownBySpeaker,
                knowledge, rng, results);
            attemptRelay(encounter.b(), encounter.a(), encounter.type(), personas, bonds, knownBySpeaker,
                knowledge, rng, results);
        }
        return results;
    }

    private static void attemptRelay(String speakerCode, String listenerCode, EncounterType encounterType,
                                      Map<String, Persona> personas,
                                      BiFunction<String, String, Bond> bonds,
                                      Function<String, List<RelayCandidate>> knownBySpeaker,
                                      Map<String, Map<Long, Learned>> knowledge,
                                      RandomGenerator rng,
                                      List<RelayResult> results) {
        Persona speaker = personas.get(speakerCode);
        Persona listener = personas.get(listenerCode);
        if (speaker == null || listener == null) {
            return;
        }

        Map<Long, Learned> speakerKnowledge = knowledgeOf(speakerCode, knownBySpeaker, knowledge);
        Map<Long, Learned> listenerKnowledge = knowledgeOf(listenerCode, knownBySpeaker, knowledge);
        double overlap = interestOverlap(speaker.interests(), listener.interests());
        Bond bond = bonds.apply(speakerCode, listenerCode);

        for (Map.Entry<Long, Learned> entry : new ArrayList<>(speakerKnowledge.entrySet())) {
            long factId = entry.getKey();
            if (listenerKnowledge.containsKey(factId)) {
                continue;
            }
            Learned learned = entry.getValue();
            RelayCandidate candidate = new RelayCandidate(speakerCode, listenerCode, factId,
                learned.dimension(), learned.hops(), learned.salience(), learned.previousText());

            double probability = relayProbability(candidate, speaker, listener, bond, overlap, encounterType);
            if (rng.nextDouble() < probability) {
                int newHops = learned.hops() + 1;
                results.add(new RelayResult(factId, listenerCode, speakerCode, newHops, learned.salience()));
                listenerKnowledge.put(factId,
                    new Learned(learned.dimension(), newHops, learned.salience(), learned.previousText()));
            }
        }
    }

    private static Map<Long, Learned> knowledgeOf(String npcCode,
                                                    Function<String, List<RelayCandidate>> knownBySpeaker,
                                                    Map<String, Map<Long, Learned>> knowledge) {
        return knowledge.computeIfAbsent(npcCode, code -> {
            Map<Long, Learned> map = new LinkedHashMap<>();
            for (RelayCandidate candidate : knownBySpeaker.apply(code)) {
                map.put(candidate.factId(), new Learned(candidate.dimension(), candidate.speakerHops(),
                    candidate.salience(), candidate.previousText()));
            }
            return map;
        });
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Internal bookkeeping for what one NPC currently knows about one fact. */
    private record Learned(String dimension, int hops, double salience, String previousText) {
    }
}
