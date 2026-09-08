package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import java.util.Map;

/**
 * Four residents' personalities as numbers, not prose. The numbers themselves are NOT stored here:
 * they live as ordinary mutable fields on {@link ResidentState} (extroversion/conscientiousness/
 * sensitivity/volatility), the same way energy/social/curiosity already do, and travel with the
 * world's JSON. A future batch is expected to nudge them slowly and boundedly out of {@code
 * reflect()} (e.g. the shopkeeper reflecting on a complaint about a long wait and becoming a little
 * less willing to step out during business hours); this batch only gives them a writable home. This
 * class is a stateless read - {@link #of} takes a resident's own state, self-heals it once from the
 * initial-value table below if it has never been seeded, and returns an immutable snapshot of its
 * current values. Every dimension has at least one place in {@link ResidentSimulation} that actually
 * reads it and changes a decision, a memory, or a decay rate - a dimension nobody reads is
 * decoration, not personality, and does not belong here. See each accessor's javadoc for exactly
 * where it is read.
 *
 * Values are 0-100 with 50 as a neutral midpoint. The four residents' initial values are not
 * clustered around that midpoint or toward a flattering end: owner and student sit near opposite
 * ends of extroversion, owner and artist sit near opposite ends of conscientiousness (with artist
 * deliberately low - "higher is better" is not the design), and artist and gardener split hard on
 * sensitivity and volatility too.
 */
public record Personality(int extroversion, int conscientiousness, int sensitivity, int volatility) {
    // owner (阿禾, 店主): outgoing and, for now, the most reliable of the four - opposite ends of
    // extroversion from student, and opposite ends of conscientiousness from artist. High
    // conscientiousness here is deliberate: it is the hook the next batch's "owner minds the
    // counter" duty-of-care behavior is expected to read - and, per this batch, the hook a future
    // reflect() write is expected to move, possibly downward, from a bad day at the counter.
    // student (小川, 备考生): guards quiet and follow-through above almost everything else - the one
    // least likely to seek company or abandon what she is doing, but notices detail.
    // artist (知夏, 插画师): the one who says yes and then, sometimes, does not follow through - low
    // conscientiousness paired with the sharpest eye for detail when she does pay attention. This is
    // a genuinely low score, not a placeholder - it must never be mistaken for "unseeded".
    // gardener (青叔, 园艺爱好者): steady and even-tempered, but the least attentive to other
    // people's small doings - practical, plant-focused, easy to miss what he was not looking at.
    private static final Map<String, int[]> INITIAL = Map.of(
        "owner",    new int[]{78, 85, 48, 45},
        "student",  new int[]{20, 78, 70, 32},
        "artist",   new int[]{62, 25, 88, 72},
        "gardener", new int[]{42, 60, 28, 18}
    );
    private static final int[] NEUTRAL = {50, 50, 50, 50};

    /** Reads r's own current personality. The first time r is ever seen (an explicit
     * `personalitySeeded` flag, not a sentinel value like "all four fields are 0" - a legitimately
     * low score, such as the artist's conscientiousness of 25, must never be mistaken for "not yet
     * seeded" and overwritten), this fills r's fields from the initial-value table below - the same
     * self-heal shape as {@code TownPlaces.seed()}/{@code reconcileLegacyPlaces()} use for an older
     * save missing other structures - and falls back to a neutral personality for the user's own
     * avatar ("self") and any id this table does not recognize. Every later call, including after a
     * future reflect() has written new values into r, simply reads what is already there: this method
     * never resets an already-seeded resident back to their initial values. */
    public static Personality of(ResidentState r) {
        if (!r.personalitySeeded) {
            int[] v = INITIAL.getOrDefault(r.id, NEUTRAL);
            r.extroversion = v[0]; r.conscientiousness = v[1]; r.sensitivity = v[2]; r.volatility = v[3];
            r.personalitySeeded = true;
        }
        return new Personality((int) r.extroversion, (int) r.conscientiousness, (int) r.sensitivity, (int) r.volatility);
    }

    /** How readily this resident seeks company: the social-need threshold in
     * ResidentSimulation.choose(). Extroverts go find someone even when reasonably social already;
     * introverts wait until they are genuinely lonely. */
    public double socialThreshold() { return 50 + (extroversion - 50) * 0.4; }

    /** How soon after their last conversation this resident is ready to start another, read in
     * ResidentSimulation.step()'s partner search. Extroverts recover faster; introverts slower. */
    public long socialRefractorySeconds() { return Math.round(55 * (1 - (extroversion - 50) / 100.0 * 0.4)); }

    /** The minimum gap, in seconds, before this resident re-approaches the same person about the
     * same project, read in ResidentSimulation.canInvite(). Never shorter than the base cooldown -
     * only introverts wait longer, so this can only ever lengthen the gap, never shorten it. */
    public long inviteCooldownSeconds() { return 900 + Math.max(0, 50 - extroversion) * 6; }

    /** Scales how strongly energy and social recharge or drain each tick in ResidentSimulation.step,
     * and how strongly a single relational event moves this resident's own private affection number
     * in ResidentSimulation.relation(). Emotionally steady residents barely move; volatile ones swing
     * harder in both directions. */
    public double intensity() { return 0.7 + volatility / 100.0 * 0.6; }

    /** A 0-100 threshold compared against a deterministic hash (never Math.random) in
     * ResidentSimulation.choose() to decide whether this resident quits their own unfinished project
     * partway through and drifts to something else - conscientious/responsible residents essentially
     * never do; this is also the hook a future duty-of-care behavior (e.g. the shopkeeper minding the
     * counter) reads to decide whether an errand wins out over what they were doing. */
    public int abandonThreshold() { return Math.max(0, 60 - conscientiousness); }

    /** A small, deterministic nudge to how much a single contribution advances a project, read in
     * ResidentSimulation.complete() - conscientious residents follow through a little more
     * thoroughly once they actually commit; the least conscientious does a little less per attempt. */
    public int diligenceBonus() { return (conscientiousness - 50) / 10; }
}
