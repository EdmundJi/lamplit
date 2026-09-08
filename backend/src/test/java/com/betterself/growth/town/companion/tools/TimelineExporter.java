package com.betterself.growth.town.companion.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Turns a {@link TimelineCollector}'s captured entries into files a person can read, plus a
 * name-stripped sample for the blind test the project's acceptance bar actually rests on (see
 * docs/01-requirements.md "先跑给自己看" and docs/04-decisions.md "验收"):
 * pull ~20 lines from the timeline, remove who said them, and see whether a person can still tell
 * the four residents apart.
 */
public final class TimelineExporter {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimelineExporter() {}

    public static List<Map<String, Object>> sortedByTime(List<Map<String, Object>> entries) {
        List<Map<String, Object>> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(e -> Instant.parse((String) e.get("at"))));
        return sorted;
    }

    public static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(value), StandardCharsets.UTF_8);
    }

    public static void writeMarkdown(Path file, List<Map<String, Object>> sortedEntries, String timezone) throws IOException {
        ZoneId zone = ZoneId.of(timezone);
        StringBuilder sb = new StringBuilder();
        sb.append("# 加速跑时间线\n\n");
        String currentDay = null;
        for (Map<String, Object> e : sortedEntries) {
            Instant at = Instant.parse((String) e.get("at"));
            String localDay = at.atZone(zone).toLocalDate().toString();
            if (!localDay.equals(currentDay)) {
                currentDay = localDay;
                sb.append("\n## ").append(localDay).append("\n\n");
            }
            String time = DAY_FORMAT.format(at.atZone(zone));
            sb.append("- `").append(time).append("` ").append(renderLine(e)).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String renderLine(Map<String, Object> e) {
        String kind = (String) e.get("kind");
        String actorName = (String) e.get("actorName");
        if (actorName == null || actorName.isBlank()) actorName = "小镇";
        String text = (String) e.get("text");
        return switch (kind) {
            case "diary" -> "【日记】" + text;
            case "event" -> "【" + actorName + "】" + text;
            case "memory" -> "【" + actorName + "的记忆】" + text;
            case "dialogue" -> actorName + "：" + text;
            case "relationship" -> "【好感变化】" + text;
            case "personality_snapshot" -> "【性格快照】" + text + " " + e.get("extra");
            default -> "【" + kind + "】" + actorName + "：" + text;
        };
    }

    /** A condensed read: dialogue, world events, relationship shifts, personality snapshots, and
     * only the "reflection"/"observed" memories (a resident's own words, not the templated "heard"
     * echo of someone else's - see {@link #quotablePool}). The full timeline.md keeps everything,
     * including every listener's own copy of each exchange, which is accurate but heavy: a few
     * days of a lively conversation-driven town produces tens of thousands of rows. This is the
     * file meant to actually be read start to finish. */
    @SuppressWarnings("unchecked")
    public static void writeHighlightsMarkdown(Path file, List<Map<String, Object>> sortedEntries, String timezone) throws IOException {
        List<Map<String, Object>> filtered = sortedEntries.stream()
            .filter(e -> {
                String kind = (String) e.get("kind");
                if (Set.of("dialogue", "event", "relationship", "personality_snapshot").contains(kind)) return true;
                if (!kind.equals("memory")) return false;
                Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
                return Set.of("reflection", "observed", "seed").contains(extra.get("sourceType"));
            })
            .toList();
        writeMarkdown(file, filtered, timezone);
    }

    /** Lines that carry one person's own voice: an actual spoken turn, or a memory they wrote in
     * their own words. Diary/event/relationship rows are third-person narration written the same
     * way for everyone, so they would not test whether four residents sound different.
     *
     * Within memories, a "heard" one is deliberately excluded: its text is a listener's verbatim
     * record of what someone ELSE said ("阿禾当面说：……"), so attributing it to the memory's owner
     * (the listener) would test the wrong person's voice - the words themselves belong to whoever
     * was quoted, not whoever wrote them down. "observed"/"reflection"/"seed" memories are all
     * genuinely first-person, in that resident's own words. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> quotablePool(List<Map<String, Object>> entries) {
        return entries.stream()
            .filter(e -> {
                String kind = (String) e.get("kind");
                if (kind.equals("dialogue")) return true;
                if (!kind.equals("memory")) return false;
                Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
                return !"heard".equals(extra.get("sourceType"));
            })
            .filter(e -> !"self".equals(e.get("actorId"))) // the avatar is user-driven, not one of the four residents under test
            .filter(e -> ((String) e.get("text")).length() >= 6)
            .toList();
    }

    public record BlindTest(List<Map<String, Object>> quiz, List<Map<String, Object>> answerKey) {}

    /**
     * Deterministically samples {@code count} quotable lines (same {@code seed} -> same sample),
     * masks any of the four residents' names that appear inside the text itself (a line that names
     * another resident is fine; a line that names its own speaker would give the answer away), and
     * splits the result into a quiz file (numbered lines only) and a separate answer key.
     */
    public static BlindTest buildBlindTest(List<Map<String, Object>> entries, int count, long seed) {
        List<Map<String, Object>> pool = new ArrayList<>(quotablePool(entries));
        Random random = new Random(seed);
        java.util.Collections.shuffle(pool, random);
        List<Map<String, Object>> picked = pool.stream().limit(count).toList();

        List<Map<String, Object>> quiz = new ArrayList<>();
        List<Map<String, Object>> answerKey = new ArrayList<>();
        int n = 1;
        for (Map<String, Object> e : picked) {
            String masked = maskNames((String) e.get("text"));
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("no", n);
            q.put("text", masked);
            quiz.add(q);

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("no", n);
            a.put("actorId", e.get("actorId"));
            a.put("actorName", e.get("actorName"));
            a.put("kind", e.get("kind"));
            a.put("at", e.get("at"));
            a.put("originalText", e.get("text"));
            answerKey.add(a);
            n++;
        }
        return new BlindTest(quiz, answerKey);
    }

    private static String maskNames(String text) {
        String masked = text;
        for (String name : List.of("阿禾", "小川", "知夏", "青叔")) {
            masked = masked.replace(name, "○○");
        }
        return masked;
    }

    public static void writeBlindTestQuizText(Path file, List<Map<String, Object>> quiz) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("盲测：以下每句话去掉了是谁说的/写的。凭语气和内容猜猜看是四个居民中的哪一位。\n\n");
        for (Map<String, Object> q : quiz) {
            sb.append(q.get("no")).append(". ").append(q.get("text")).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
