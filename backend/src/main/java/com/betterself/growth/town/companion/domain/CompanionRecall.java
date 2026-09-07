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
 * not required for the basic life loop. Time and the question are explicit inputs. */
public final class CompanionRecall {
    private static final Pattern WORD = Pattern.compile("[a-z0-9_-]{2,}|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);
    private CompanionRecall() {}

    public static List<Memory> retrieve(List<Memory> memories, String ownerId, String query, Instant now, int limit) {
        if (ownerId == null || now == null || memories == null || limit <= 0) return List.of();
        Set<String> question = terms(query);
        return memories.stream()
            .filter(memory -> ownerId.equals(memory.ownerId()))
            .filter(memory -> memory.at() != null && !memory.at().isAfter(now))
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
        return relevance * 3 + recent + importance;
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
