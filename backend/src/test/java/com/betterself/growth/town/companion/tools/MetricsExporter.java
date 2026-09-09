package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a run's {@link TimelineCollector} output plus the final {@link CompanionWorld} into the
 * one artifact docs/01-requirements.md's "先跑给自己看" and docs/05-notes.md actually ask this
 * harness to produce: a number, not an impression, for whether emergence happened. See
 * docs/01-requirements.md "让他们自己产生秩序" for what each of these numbers is standing in for.
 *
 * Every metric here reads only what the timeline or the final world actually recorded. A
 * mechanism that never fired must show as an explicit 0, never be silently absent or defaulted -
 * that is the one hard rule this class exists to enforce (see the task's own "如果某个机制一次都
 * 没触发，指标里要明确显示 0，不要用默认值掩盖").
 */
public final class MetricsExporter {
    private MetricsExporter() {}

    private static final List<String> INITIAL_RESIDENT_IDS = List.of("owner", "student", "artist", "gardener");

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compute(List<Map<String, Object>> sortedEntries, TimelineCollector collector, CompanionWorld finalWorld) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("serviceRequests", serviceRequestMetrics(collector));
        metrics.put("personality", personalityMetrics(sortedEntries, finalWorld));
        metrics.put("duty", dutyMetrics(sortedEntries));
        metrics.put("relationshipAsymmetry", relationshipAsymmetryMetrics(finalWorld));
        metrics.put("memories", memoryMetrics(sortedEntries));
        metrics.put("coLocation", coLocationMetrics(collector));
        return metrics;
    }

    // ---- service requests ------------------------------------------------------------------

    private static Map<String, Object> serviceRequestMetrics(TimelineCollector collector) {
        var requests = collector.serviceRequests();
        // byStatus is the whole story now: every request here was explicitly asked for by a
        // resident, so "how many broke, and where" is the only thing worth counting. There is no
        // unprompted pour to tally - see CafeService's class javadoc for why that was removed.
        Map<String, Integer> byStatus = new TreeMap<>();
        for (Map<String, Object> r : requests) byStatus.merge((String) r.get("status"), 1, Integer::sum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", requests.size());
        out.put("byStatus", byStatus);
        return out;
    }

    // ---- personality drift ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> personalityMetrics(List<Map<String, Object>> entries, CompanionWorld finalWorld) {
        Map<String, List<Map<String, Object>>> snapshotsByResident = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            if (!"personality_snapshot".equals(e.get("kind"))) continue;
            String id = (String) e.get("actorId");
            snapshotsByResident.computeIfAbsent(id, k -> new ArrayList<>()).add((Map<String, Object>) e.get("extra"));
        }
        List<Object> out = new ArrayList<>();
        List<String> residentIds = finalWorld == null
            ? INITIAL_RESIDENT_IDS
            : finalWorld.residentStates.stream().map(r -> r.id).filter(id -> !"self".equals(id)).toList();
        for (String id : residentIds) {
            List<Map<String, Object>> snaps = snapshotsByResident.getOrDefault(id, List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            String name = finalWorld == null ? id : finalWorld.residents.stream().filter(a -> a.id().equals(id)).map(CompanionWorld.Actor::name).findFirst().orElse(id);
            row.put("name", name);
            row.put("snapshotsObserved", snaps.size());
            if (snaps.isEmpty()) {
                // No snapshot was ever captured for this resident - honest zero/empty, not a guess.
                row.put("initial", null);
                row.put("final", null);
                row.put("everDeviated", false);
                out.add(row);
                continue;
            }
            Map<String, Object> initial = snaps.get(0);
            Map<String, Object> last = snaps.get(snaps.size() - 1);
            row.put("initial", initial);
            row.put("final", last);
            boolean deviated = snaps.stream().anyMatch(s -> !dimsEqual(s, initial));
            row.put("everDeviated", deviated);
            out.add(row);
        }
        return out;
    }

    private static boolean dimsEqual(Map<String, Object> a, Map<String, Object> b) {
        for (String dim : List.of("extroversion", "conscientiousness", "sensitivity", "volatility"))
            if (!java.util.Objects.equals(a.get(dim), b.get(dim))) return false;
        return true;
    }

    // ---- duty: complaints, interruptions, reflections ----------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dutyMetrics(List<Map<String, Object>> entries) {
        long complaints = entries.stream()
            .filter(e -> "event".equals(e.get("kind")))
            .filter(e -> "complaint".equals(((Map<String, Object>) e.getOrDefault("extra", Map.of())).get("eventType")))
            .count();
        long interruptions = entries.stream()
            .filter(e -> "memory".equals(e.get("kind")))
            .filter(e -> "owner".equals(e.get("actorId")))
            .filter(e -> ((String) e.get("text")).contains("又被柜台叫住了"))
            .count();
        List<Map<String, Object>> reflections = entries.stream()
            .filter(e -> "memory".equals(e.get("kind")))
            .filter(e -> "reflection".equals(((Map<String, Object>) e.getOrDefault("extra", Map.of())).get("sourceType")))
            .toList();
        long dutyReflections = reflections.stream()
            .filter(e -> "duty".equals(((Map<String, Object>) e.getOrDefault("extra", Map.of())).get("topicId")))
            .count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("complaints", complaints);
        out.put("interruptions", interruptions);
        out.put("reflectionsTriggered", reflections.size());
        Map<String, Object> byTopic = new LinkedHashMap<>();
        byTopic.put("duty", dutyReflections);
        byTopic.put("other", reflections.size() - dutyReflections);
        out.put("reflectionsByTopic", byTopic);
        return out;
    }

    // ---- relationship matrix asymmetry --------------------------------------------------------

    private static Map<String, Object> relationshipAsymmetryMetrics(CompanionWorld finalWorld) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (finalWorld == null || finalWorld.residentStates.isEmpty()) {
            out.put("pairsCompared", 0);
            out.put("asymmetricPairs", 0);
            out.put("asymmetryRatio", 0.0);
            out.put("meanAbsDiff", 0.0);
            out.put("maxAbsDiff", null);
            return out;
        }
        Map<String, ResidentState> byId = new LinkedHashMap<>();
        for (ResidentState r : finalWorld.residentStates) byId.put(r.id, r);
        int pairsCompared = 0, asymmetric = 0;
        long sumAbsDiff = 0;
        Map<String, Object> maxEntry = null;
        int maxDiff = -1;
        List<String> ids = new ArrayList<>(byId.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                String a = ids.get(i), b = ids.get(j);
                Integer ab = byId.get(a).relationships.get(b);
                Integer ba = byId.get(b).relationships.get(a);
                if (ab == null || ba == null) continue; // one side never formed an opinion of the other
                pairsCompared++;
                int diff = Math.abs(ab - ba);
                sumAbsDiff += diff;
                if (diff != 0) asymmetric++;
                if (diff > maxDiff) {
                    maxDiff = diff;
                    Map<String, Object> entry = new LinkedHashMap<>(); // see LinkedHashMap note above - Map.of() here broke reproducibility
                    entry.put(a + "->" + b, ab);
                    entry.put(b + "->" + a, ba);
                    entry.put("diff", diff);
                    maxEntry = entry;
                }
            }
        }
        out.put("pairsCompared", pairsCompared);
        out.put("asymmetricPairs", asymmetric);
        out.put("asymmetryRatio", pairsCompared == 0 ? 0.0 : round2((double) asymmetric / pairsCompared));
        out.put("meanAbsDiff", pairsCompared == 0 ? 0.0 : round2((double) sumAbsDiff / pairsCompared));
        out.put("maxAbsDiff", maxEntry);
        return out;
    }

    // ---- memories: per-resident counts and same-topic divergence -----------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> memoryMetrics(List<Map<String, Object>> entries) {
        Map<String, Integer> perResident = new LinkedHashMap<>();
        for (String id : INITIAL_RESIDENT_IDS) perResident.put(id, 0);
        // topicId -> ownerId -> set of distinct texts that owner recorded for that topic
        Map<String, Map<String, java.util.Set<String>>> byTopic = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            if (!"memory".equals(e.get("kind"))) continue;
            String owner = (String) e.get("actorId");
            perResident.merge(owner, 1, Integer::sum);
            Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
            String topicId = (String) extra.get("topicId");
            if (topicId == null) continue;
            byTopic.computeIfAbsent(topicId, k -> new LinkedHashMap<>())
                .computeIfAbsent(owner, k -> new java.util.LinkedHashSet<>())
                .add((String) e.get("text"));
        }
        int topicsWithMultipleOwners = 0, topicsWithDivergentText = 0;
        for (var ownersToTexts : byTopic.values()) {
            if (ownersToTexts.size() < 2) continue;
            topicsWithMultipleOwners++;
            java.util.Set<String> allTexts = new java.util.LinkedHashSet<>();
            ownersToTexts.values().forEach(allTexts::addAll);
            if (allTexts.size() > 1) topicsWithDivergentText++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("perResident", perResident);
        Map<String, Object> divergence = new LinkedHashMap<>();
        divergence.put("topicsWithMultipleOwners", topicsWithMultipleOwners);
        divergence.put("topicsWithDivergentText", topicsWithDivergentText);
        divergence.put("ratio", topicsWithMultipleOwners == 0 ? 0.0 : round2((double) topicsWithDivergentText / topicsWithMultipleOwners));
        out.put("sameTopicDivergence", divergence);
        return out;
    }

    // ---- spatial clustering ---------------------------------------------------------------------

    private static Map<String, Object> coLocationMetrics(TimelineCollector collector) {
        Map<Integer, Long> histogram = collector.coLocationHistogram();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> byGroupSize = new TreeMap<>();
        long total = 0;
        for (var e : histogram.entrySet()) { byGroupSize.put(String.valueOf(e.getKey()), e.getValue()); total += e.getValue(); }
        out.put("groupSizeHistogram", byGroupSize);
        out.put("totalPlaceSamples", total);
        return out;
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    // ---- writing -----------------------------------------------------------------------------

    public static void writeJson(Path file, Map<String, Object> metrics) throws IOException {
        TimelineExporter.writeJson(file, metrics);
    }

    @SuppressWarnings("unchecked")
    public static void writeMarkdown(Path file, Map<String, Object> metrics) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 涌现指标摘要\n\n");

        Map<String, Object> sr = (Map<String, Object>) metrics.get("serviceRequests");
        sb.append("## 服务请求\n\n");
        sb.append("- 总数：").append(sr.get("total")).append('\n');
        sb.append("- 按状态：").append(sr.get("byStatus")).append('\n');
        sb.append('\n');

        sb.append("## 人格漂移\n\n");
        for (Object rowObj : (List<Object>) metrics.get("personality")) {
            Map<String, Object> row = (Map<String, Object>) rowObj;
            sb.append("- ").append(row.get("name")).append("（").append(row.get("id")).append("）：初值 ")
                .append(row.get("initial")).append(" → 终值 ").append(row.get("final"))
                .append("，是否偏离过：").append(row.get("everDeviated"))
                .append("（观察到 ").append(row.get("snapshotsObserved")).append(" 次快照）\n");
        }
        sb.append('\n');

        Map<String, Object> duty = (Map<String, Object>) metrics.get("duty");
        sb.append("## 责任感反馈闭环\n\n");
        sb.append("- 抱怨次数：").append(duty.get("complaints")).append('\n');
        sb.append("- 被打断次数：").append(duty.get("interruptions")).append('\n');
        sb.append("- 反思实际触发次数：").append(duty.get("reflectionsTriggered"))
            .append("（按 topic：").append(duty.get("reflectionsByTopic")).append("）\n\n");

        Map<String, Object> asym = (Map<String, Object>) metrics.get("relationshipAsymmetry");
        sb.append("## 关系矩阵不对称程度\n\n");
        sb.append("- 比较的对数：").append(asym.get("pairsCompared")).append('\n');
        sb.append("- 不对称的对数：").append(asym.get("asymmetricPairs"))
            .append("（比例 ").append(asym.get("asymmetryRatio")).append("）\n");
        sb.append("- 平均绝对差：").append(asym.get("meanAbsDiff")).append("，最大差：").append(asym.get("maxAbsDiff")).append("\n\n");

        Map<String, Object> mem = (Map<String, Object>) metrics.get("memories");
        sb.append("## 记忆\n\n");
        sb.append("- 每人记忆条数：").append(mem.get("perResident")).append('\n');
        sb.append("- 同源事件被记成不同文本的比例：").append(mem.get("sameTopicDivergence")).append("\n\n");

        Map<String, Object> colo = (Map<String, Object>) metrics.get("coLocation");
        sb.append("## 同一时刻同一地点人数分布\n\n");
        sb.append("- 分组规模直方图（key=同一地点人数，value=样本数）：").append(colo.get("groupSizeHistogram")).append('\n');
        sb.append("- 采样点总数：").append(colo.get("totalPlaceSamples")).append('\n');

        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
