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

    public static List<Memory> retrieve(List<Memory> memories, String ownerId, String query, Instant now, int limit) {
        if (ownerId == null || now == null || memories == null || limit <= 0) return List.of();
        Set<String> question = terms(query);
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
            .limit(Math.min(limit, 30)).toList();
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
