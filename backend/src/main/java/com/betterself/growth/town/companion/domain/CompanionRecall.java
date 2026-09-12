package com.betterself.growth.town.companion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;

/** Local, deterministic retrieval: a resident can retrieve only their own memories.
 * Relevance uses words and Chinese character pairs, so a remote embedding service is
 * not required for the basic life loop. Time and the question are explicit inputs.
 *
 * <p>Scoring follows the generative-agents idea of combining recency, importance and relevance,
 * but combines them so the three factors genuinely constrain each other rather than simply adding
 * up: relevance is a multiplier over the recency/importance/layer base, not a fourth term sitting
 * next to them. A merely-fresh, merely-important memory that has nothing to do with the question
 * gets scaled down hard by {@link #RELEVANCE_FLOOR}; a memory with even partial relevance keeps
 * most of its base score. When there is no question at all (an open "what have you been living
 * through" browse, as {@link ResidentSimulation#reflectionSource} uses), relevance drops out of the
 * multiplication entirely rather than zeroing everything. */
public final class CompanionRecall {
    private static final Pattern WORD = Pattern.compile("[a-z0-9_-]{2,}|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);
    /** How much a completely irrelevant memory's recency/importance/layer base still counts for.
     * Kept small but non-zero: a real question always prefers even a weakly relevant memory over an
     * unrelated one, yet two equally irrelevant memories still order sensibly by how fresh/important/
     * durable they are instead of colliding on an identical zero score. */
    private static final double RELEVANCE_FLOOR = 0.08;
    private static final double WEIGHT_RECENCY = 0.45;
    private static final double WEIGHT_IMPORTANCE = 0.25;
    private static final double WEIGHT_LAYER = 0.30;
    private CompanionRecall() {}

    /**
     * At most this share of what comes back may be the resident's own account of their own doings.
     *
     * <p>Measured, twice. A decision files the reason the resident gave for it as a {@code reflection}
     * - one per decision, so they are both the most numerous thing in anybody's memory and always the
     * freshest. {@code reflection} also sits a layer above raw memory in {@link #tier}. Recency and
     * layer therefore agree with each other, every time, and the top of the ranking fills up with the
     * resident talking to themselves: 51% of everything retrieved across 243 real decisions, and 66%
     * in a later run whose query matched less prose and so leaned even harder on recency and layer.
     * 44%, then 58%, of decisions received a byte-identical memory set to that resident's previous
     * one.
     *
     * <p>Weights cannot fix this, because it is not a weighting problem: whatever the numbers, one
     * kind of memory is produced far faster than any other and will crowd the window. So the window
     * itself reserves room. A person recalls what happened to them, not only what they have told
     * themselves about it.
     *
     * <p>Never reduces how much comes back: if there is not enough that the resident did not author,
     * the remaining slots are filled from their own account as before. A resident whose whole
     * recorded life is their own voice still gets their whole recorded life.
     */
    private static final double OWN_VOICE_SHARE = 0.5;

    public static List<Memory> retrieve(List<Memory> memories, String ownerId, String query, Instant now, int limit) {
        if (ownerId == null || now == null || memories == null || limit <= 0) return List.of();
        Set<String> question = terms(query);
        List<Memory> ranked = ranked(memories, ownerId, question, now);
        int ownVoiceCap = Math.max(1, (int) Math.floor(limit * OWN_VOICE_SHARE));
        List<Memory> kept = new java.util.ArrayList<>();
        List<Memory> overflow = new java.util.ArrayList<>();
        int ownVoice = 0;
        for (Memory memory : ranked) {
            if (kept.size() >= limit) break;
            if (isOwnAccount(memory, ownerId) && ownVoice >= ownVoiceCap) { overflow.add(memory); continue; }
            if (isOwnAccount(memory, ownerId)) ownVoice++;
            kept.add(memory);
        }
        // Nothing else to say - hand back their own voice rather than a shorter list.
        for (Memory memory : overflow) { if (kept.size() >= limit) break; kept.add(memory); }
        return List.copyOf(kept);
    }

    /** Their own account of their own doings - not merely a memory they happen to own (every memory
     * here is owned by them), but one whose source is themselves and whose layer is a synthesis they
     * made rather than something that happened. A standing belief is deliberately NOT this: those are
     * rare, hard-won, and the whole point of the layer above. */
    private static boolean isOwnAccount(Memory memory, String ownerId) {
        return "reflection".equals(memory.sourceType()) && ownerId.equals(memory.sourceId());
    }

    private static List<Memory> ranked(List<Memory> memories, String ownerId, Set<String> question, Instant now) {
        return memories.stream()
            .filter(memory -> ownerId.equals(memory.ownerId()))
            .filter(memory -> memory.at() != null && !memory.at().isAfter(now))
            // A superseded conclusion is still on record (see Memory's own doc comment) but a
            // resident deciding what to do next should act on their current belief, not one they
            // have since moved on from. The old one stays reachable by reading the raw list/store
            // directly - just never through this ranked recall.
            .filter(memory -> !memory.superseded())
            .sorted(Comparator.<Memory>comparingDouble(memory -> score(memory, question, now)).reversed()
                .thenComparing(Memory::at, Comparator.reverseOrder()).thenComparing(Memory::id))
            .toList();
    }

    private static double score(Memory memory, Set<String> question, Instant now) {
        Set<String> content = terms(memory.text() + " " + memory.topicId());
        long matches = question.stream().filter(content::contains).count();
        double relevance = question.isEmpty() ? 0 : (double) matches / question.size();
        double hours = Math.max(0, Duration.between(memory.at(), now).toSeconds()) / 3600.0;
        double recent = 1.0 / (1.0 + hours / 6.0);
        double importance = Math.max(0, Math.min(10, memory.importance())) / 10.0;
        double layer = tier(memory.sourceType()) / 2.0;
        double base = WEIGHT_RECENCY * recent + WEIGHT_IMPORTANCE * importance + WEIGHT_LAYER * layer;
        // No question at all means there is nothing for relevance to constrain - an open browse
        // ranks purely on recency/importance/layer, same as before this batch.
        double relevanceFactor = question.isEmpty() ? 1.0 : RELEVANCE_FLOOR + (1 - RELEVANCE_FLOOR) * relevance;
        return base * relevanceFactor;
    }

    /** The three memory layers, most disposable to most durable - see {@link CompanionWorld.Memory}'s
     * own doc comment. Used both to weight retrieval (higher tier ranks higher, all else equal) and
     * to choose what gets evicted first when a resident's memory fills up (lowest tier first). An
     * unrecognized sourceType is treated as raw rather than throwing, so an old save with a value
     * this method does not yet know about degrades to "most disposable" instead of crashing. */
    public static int tier(String sourceType) {
        if ("belief".equals(sourceType)) return 2;
        if ("reflection".equals(sourceType)) return 1;
        return 0;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return result;
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = word.codePoints().toArray();
                if (points.length == 1) result.add(word);
                for (int i = 0; i < points.length - 1; i++) result.add(new String(points, i, 2));
            } else result.add(word);
        }
        return result;
    }
}
