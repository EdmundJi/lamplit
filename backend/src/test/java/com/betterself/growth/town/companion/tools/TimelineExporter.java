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
            // The complete seat record ("seat_state") is for NormDetector's replay only - never for a
            // human reader (docs/05-notes.md "座位事件拆成两股"). It has no place in a timeline a
            // person reads start to finish.
            if ("seat_state".equals(e.get("kind"))) continue;
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

    /** Lines that actually carry one person's own voice AS THE MODEL WROTE IT - the pool the blind
     * test draws from. Two earlier, broader pools were both wrong in practice (see docs/05-notes.md
     * "盲测抽样是错的" - twenty sampled lines turned out to be mostly rule-template narration and
     * memory echoes of the same exchange, testing template diversity instead of personality):
     *
     * <p>Only {@code dialogue} turns whose {@code extra.source == "model"} qualify. A dialogue turn's
     * {@code source} field is set by the domain itself - "model" when {@link
     * com.betterself.growth.town.companion.domain.ConversationLifecycle#applyTurn} committed a real
     * model reply, "rules" for every fallback/timeout line (see {@code ConversationLifecycle.tick}'s
     * {@code fallback()} and the two fixed lines in its "fallback" mode branch) - and those rule lines
     * are, by construction, the same handful of fixed sentences for all four residents, so sampling
     * them tests nothing about personality. Memory entries are excluded entirely, not filtered by
     * sourceType: {@code Memory} carries no equivalent model/rule provenance field, and several
     * "observed"/"reflection" memories are themselves rule-generated templates (e.g. ResidentSimulation's
     * "…在…为「」添了一笔" project-progress line, or ConversationLifecycle's rule-driven conversation
     * summaries) that would silently reintroduce the same problem under a different kind. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> quotablePool(List<Map<String, Object>> entries) {
        return entries.stream()
            .filter(e -> "dialogue".equals(e.get("kind")))
            .filter(e -> !"self".equals(e.get("actorId"))) // the avatar is user-driven, not one of the four residents under test
            .filter(e -> "model".equals(((Map<String, Object>) e.getOrDefault("extra", Map.of())).get("source")))
            .filter(e -> ((String) e.get("text")).length() >= 6)
            .toList();
    }

    public record BlindTest(List<Map<String, Object>> quiz, List<Map<String, Object>> answerKey, Map<String, Object> poolStats) {}

    /**
     * Deterministically samples up to {@code count} quotable lines (same {@code seed} -> same
     * sample), stratified evenly across the four residents (so the 25% random baseline in
     * docs/04-decisions.md "验收" is actually clean - a pool skewed toward whichever resident talks
     * most would let a guesser do better than chance without reading a single line), deduplicated by
     * exact text (the same line - "同一句话" - must never appear twice), masks any of the four
     * residents' names that appear inside the text itself, and splits the result into a quiz (numbered
     * lines only) and a separate answer key. {@code poolStats} reports, per resident, how many unique
     * model-sourced lines were actually available versus how many this sample asked for - an honest
     * count rather than silently under-filling when a short or quiet run has not produced enough yet.
     */
    public static BlindTest buildBlindTest(List<Map<String, Object>> entries, int count, long seed) {
        List<Map<String, Object>> pool = quotablePool(entries);

        // Dedupe by exact text, keeping the first (chronological) occurrence.
        Map<String, Map<String, Object>> byText = new LinkedHashMap<>();
        for (Map<String, Object> e : pool) byText.putIfAbsent((String) e.get("text"), e);

        // Stratify by speaker.
        Map<String, List<Map<String, Object>>> byActor = new LinkedHashMap<>();
        for (Map<String, Object> e : byText.values())
            byActor.computeIfAbsent((String) e.get("actorId"), k -> new ArrayList<>()).add(e);

        int residentCount = Math.max(1, byActor.size());
        int perResident = Math.max(1, count / residentCount);
        Random random = new Random(seed);
        List<Map<String, Object>> picked = new ArrayList<>();
        Map<String, Object> availablePerResident = new LinkedHashMap<>();
        for (var e : byActor.entrySet()) {
            List<Map<String, Object>> shuffled = new ArrayList<>(e.getValue());
            java.util.Collections.shuffle(shuffled, random);
            availablePerResident.put(e.getKey(), shuffled.size());
            picked.addAll(shuffled.stream().limit(perResident).toList());
        }
        java.util.Collections.shuffle(picked, random); // mix speaking order so consecutive quiz numbers aren't grouped by resident
        if (picked.size() > count) picked = picked.subList(0, count);

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
        Map<String, Object> poolStats = new LinkedHashMap<>();
        poolStats.put("requestedTotal", count);
        poolStats.put("requestedPerResident", perResident);
        poolStats.put("residentsRepresented", byActor.size());
        poolStats.put("uniqueModelLinesAvailablePerResident", availablePerResident);
        poolStats.put("pickedTotal", picked.size());
        return new BlindTest(quiz, answerKey, poolStats);
    }

    /** The material for the other blind test - the social one. See {@link #buildNormBlindTest}. */
    public record NormBlindTest(String quiz, String key) {}

    /**
     * docs/01-requirements.md 的「怎么验收」 asks for a second blind test, and it is the half of the acceptance goal that
     * no statistic can stand in for: <b>hand a reader who has never seen this repository a stretch of
     * the town's life and ask one question - 「这个镇上有什么规矩？」</b> If they read out what we
     * measured, the norm is really in the text and not only in our arithmetic. If they read out
     * something we did <em>not</em> measure, better still - our instrument is too narrow. If they read
     * out nothing, we have not done it, however green the metrics are.
     *
     * <p>Split quiz/key the same way {@link #buildBlindTest} is, and for the same reason (docs/05-notes
     * 「手抄是个静默失败点」): the reader gets the events, never the residents' own conclusions. Those
     * sit in the key, because they answer a different question - whether anybody in town can say the
     * rule out loud - and showing them first would simply be telling the reader the answer.
     *
     * <p>Names are kept here, unlike the personality blind test. That test hides who is speaking because
     * the guess IS who is speaking; this one is about what people do around each other, and stripping
     * the names would destroy the very thing being looked for.
     */
    public static NormBlindTest buildNormBlindTest(List<Map<String, Object>> sortedEntries,
                                                   List<Map<String, Object>> memories, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        StringBuilder quiz = new StringBuilder();
        quiz.append("""
            你没有读过这个项目的任何代码或文档，也请不要去读。

            下面是一个小镇上几天里发生的事，按时间顺序排。镇上住着六个人。

            只回答一个问题：**这个镇上有什么规矩？**

            这里说的"规矩"是指：这些人之间反复出现的、大家似乎都照着做的做法——不是某一个人的个人
            癖好，而是关于人跟人之间该怎么相处的。比如谁该先开口、东西归谁用、有人开了头别人怎么办。

            请把你看出来的每一条都写出来，并各附上让你这么认为的具体行数。
            如果你觉得看不出什么规矩，就说看不出——**"没有"是一个真实的答案**，不必凑。

            ---

            """.stripIndent());
        int no = 1;
        String currentDay = null;
        for (Map<String, Object> e : sortedEntries) {
            if (!isSocialTrace(e)) continue;
            Instant at = Instant.parse((String) e.get("at"));
            String day = at.atZone(zone).toLocalDate().toString();
            if (!day.equals(currentDay)) { currentDay = day; quiz.append("\n## ").append(day).append("\n\n"); }
            quiz.append(no++).append(". `").append(DAY_FORMAT.format(at.atZone(zone))).append("` ")
                .append(renderLine(e)).append('\n');
        }

        StringBuilder key = new StringBuilder("# 答案页：居民自己说出来的话\n\n");
        key.append("这一页**不给做盲测的人看**。它回答的是另一个问题：镇上有没有人能自己把那条规矩说出来。\n\n");
        key.append("## 长期看法（带 supersedesKey 的信念）\n\n");
        int beliefs = 0;
        for (Map<String, Object> m : memories) {
            Object supersedes = m.get("supersedesKey");
            if (supersedes == null || String.valueOf(supersedes).isBlank()) continue;
            beliefs++;
            key.append("- **").append(m.get("ownerId")).append("** `").append(supersedes).append("`：")
               .append(m.get("text")).append(Boolean.TRUE.equals(m.get("superseded")) ? "  _（后来被自己推翻了）_" : "")
               .append('\n');
        }
        if (beliefs == 0) key.append("一条都没有。**这是一个真实的 0**——镇上没有任何人形成过长期看法。\n");
        return new NormBlindTest(quiz.toString(), key.toString());
    }

    /** What a bystander would have been able to see happen. Inner monologue ({@code thought}) is left
     * out: 863 of them in a three-day run would bury the events, and a norm has to be visible from the
     * outside or it is not one.
     *
     * <p>Routine seat changes are furniture, not narrative: docs/05-notes.md found 67% of a run's
     * quiz material was "占了/离开了" - a resident sitting back at their own desk, one more time. Only
     * the handful {@code TownPlaces} itself flagged as the kind a bystander would actually remark on -
     * took someone else's spot, sat down next to someone, got up because the spot's owner just
     * reclaimed it (see {@code TownPlaces.SeatTransitionNote} and {@code TimelineCollector}'s
     * {@code noticeReason}) - earn a line here. The judgment call ("would someone in the room notice
     * this") is made once, at the moment the seat change happens and the full context for it still
     * exists; this method only reads the answer back. {@code seat_state} (the complete record kept for
     * {@code NormDetector}'s replay) never reaches here at all - {@link #sortedByTime} and the caller's
     * own entries list mix it in, but this method's own {@code kind} check excludes it same as any
     * other non-event, non-dialogue kind. */
    @SuppressWarnings("unchecked")
    private static boolean isSocialTrace(Map<String, Object> e) {
        if ("dialogue".equals(e.get("kind"))) return true;
        if (!"event".equals(e.get("kind"))) return false;
        Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
        Object type = extra.get("eventType");
        if ("thought".equals(type)) return false;
        if ("took_spot".equals(type) || "left_spot".equals(type)) return extra.get("noticeReason") != null;
        return true;
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
