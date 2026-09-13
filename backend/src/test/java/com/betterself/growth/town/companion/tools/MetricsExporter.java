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
        return compute(sortedEntries, collector, finalWorld, List.of());
    }

    /** {@code simulatedDays} is every local date the run actually covered. Without it a day on which
     * nothing happened simply has no key, and "days meeting the target" then divides by the number of
     * days that DID have something - which reads as 1/1 for a run whose first day scored zero. A
     * metric that cannot report a zero is a metric that flatters itself. */
    public static Map<String, Object> compute(List<Map<String, Object>> sortedEntries, TimelineCollector collector, CompanionWorld finalWorld, List<String> simulatedDays) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("serviceRequests", serviceRequestMetrics(collector));
        metrics.put("personality", personalityMetrics(sortedEntries, finalWorld));
        metrics.put("duty", dutyMetrics(sortedEntries));
        metrics.put("relationshipAsymmetry", relationshipAsymmetryMetrics(finalWorld));
        metrics.put("memories", memoryMetrics(sortedEntries));
        metrics.put("coLocation", coLocationMetrics(collector));
        metrics.put("jointAction", jointActionMetrics(collector, sortedEntries, simulatedDays));
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

    /**
     * <b>{@code reflectionsTriggered} counts a shared narrative-tier label, not a mechanism.</b> Eight
     * different call sites tag a memory {@code sourceType="reflection"} (decision rationale, conversation
     * summaries, occupation changes, duty conscientiousness bumps, missed-plan notes, self-reflection...)
     * purely so {@code CompanionRecall.tier} treats them as a layer above raw memory - the timeline this
     * reads cannot tell which of the eight wrote any given one. Only
     * {@code ResidentSimulation.applyReflection} can attach a {@code supersedesKey} and mint a real
     * {@code belief} (see that method: {@code String type=supersedesKey!=null?"belief":"reflection";}).
     * Dividing {@code NormDetector}'s {@code beliefs} count by this one is exactly the wrong-denominator
     * mistake docs/05-notes.md's "146 次反思出 5 条信念" made - the real denominator is belief-capable
     * calls (measured ≈2.7/resident/day, structural ceiling 8/day from {@code REFLECTION_MIN_GAP_SECONDS}
     * =3h), which cannot be isolated from the other seven sites using only what this class reads. This
     * count stays only for the duty feedback loop it was built for ({@code reflectionsByTopic}'s "duty"
     * bucket) - see {@code writeMarkdown}'s label for the same caveat spelled out for a human reader.
     */
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

    // ---- doing one thing together ---------------------------------------------------------------

    /** How often two people actually did one thing together, split by how much each kind is worth.
     * The headline is {@code chosen} - a shared project, or somebody going over to sit with somebody -
     * because those are the only ones where a resident picked a person. {@code sameActivity} is
     * reported beside it and deliberately kept out of the total: everybody's place habits point at
     * the cafe, so two people reading in the same room is mostly the map, not a decision.
     * <p>Episodes shorter than {@link #JOINT_MIN_MINUTES} are dropped. Walking past somebody who is
     * doing what you are doing is not doing it with them. */
    private static final long JOINT_MIN_MINUTES = 5;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jointActionMetrics(TimelineCollector collector, List<Map<String, Object>> entries, List<String> simulatedDays) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> chosenPerDay = new TreeMap<>();
        for (String day : simulatedDays) chosenPerDay.put(day, 0); // a zero day has to be able to say so
        List<Object> kept = new ArrayList<>();
        int chosen = 0;
        for (Map<String, Object> e : collector.jointEpisodes()) {
            long minutes = ((Number) e.get("minutes")).longValue();
            if (minutes < JOINT_MIN_MINUTES) continue;
            String kind = (String) e.get("kind");
            byKind.merge(kind, 1, Integer::sum);
            kept.add(e);
            if (!"sameActivity".equals(kind)) {
                chosen++;
                chosenPerDay.merge(((String) e.get("startedAt")).substring(0, 10), 1, Integer::sum);
            }
        }
            out.put("byKind", byKind);
        out.put("chosenTotal", chosen);
        out.put("chosenPerDay", chosenPerDay);
        // The target this whole line of work is aimed at, stated in the metric itself so a run either
        // meets it or visibly does not - see docs/04 for why it is three and not some larger number.
        out.put("targetPerDay", 3);
        out.put("daysMeetingTarget", chosenPerDay.values().stream().filter(n -> n >= 3).count());
        out.put("distinctDays", chosenPerDay.size());
        out.put("episodes", kept);
        // The other, looser reading of "一起做同一件事", and the one far more likely to actually
        // happen: two people put their hands on one project on the same day without ever standing
        // there at the same moment. They still made one thing together. Counted from the rules' own
        // "contribution" events, once per (day, project) no matter how many times each contributed,
        // and reported beside the simultaneous number rather than folded into it - the two say
        // different things and a single blended figure would hide which one the town is managing.
        Map<String, java.util.Set<String>> handsPerDayProject = new TreeMap<>();
        for (Map<String, Object> e : entries) {
            if (!"event".equals(e.get("kind"))) continue;
            Map<String, Object> extra = (Map<String, Object>) e.get("extra");
            if (extra == null || !"contribution".equals(extra.get("eventType")) || extra.get("projectId") == null) continue;
            String key = ((String) e.get("at")).substring(0, 10) + "|" + extra.get("projectId");
            for (String id : ((String) e.get("actorId")).split(",")) handsPerDayProject.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(id);
        }
        Map<String, Integer> sharedBuildsPerDay = new TreeMap<>();
        for (String day : simulatedDays) sharedBuildsPerDay.put(day, 0);
        for (var e : handsPerDayProject.entrySet())
            if (e.getValue().size() >= 2) sharedBuildsPerDay.merge(e.getKey().substring(0, e.getKey().indexOf('|')), 1, Integer::sum);
        out.put("sharedBuildsPerDay", sharedBuildsPerDay);
        out.put("sharedBuildsTotal", sharedBuildsPerDay.values().stream().mapToInt(Integer::intValue).sum());
        // Every project that anybody touched at all, and by how many different people - the rawest
        // form of the diagnosis this whole line of work started from (a communal project stops dead
        // at 75% until a second pair of hands arrives, and across a measured day none ever came).
        Map<String, Integer> handsPerProject = new TreeMap<>();
        for (var e : handsPerDayProject.entrySet())
            handsPerProject.merge(e.getKey().substring(e.getKey().indexOf('|') + 1), e.getValue().size(), Math::max);
        out.put("distinctContributorsPerProject", handsPerProject);
        // Context, never part of the total: conversations are together too, but the town already
        // makes plenty and folding them in would let the number pass without anything changing.
        long conversations = entries.stream().filter(e -> "dialogue".equals(e.get("kind"))).count();
        out.put("dialogueTurnsForContext", conversations);
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
        sb.append("- 打上『反思层』标记的记忆条数：").append(duty.get("reflectionsTriggered"))
            .append("（按 topic：").append(duty.get("reflectionsByTopic"))
            .append("——覆盖 8 个不同写入点的合集，不是 reflect 机制的调用次数，不能拿它当"
                + "反思→信念转化率的分母，见 MetricsExporter#dutyMetrics 的类注释）\n\n");

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
        sb.append('\n');

        Map<String, Object> joint = (Map<String, Object>) metrics.get("jointAction");
        sb.append("## 两个人一起做同一件事\n\n");
        sb.append("- 主动的（共同项目 + 围着做出来的东西 + 主动过去坐下）：").append(joint.get("chosenTotal"))
          .append("，按天：").append(joint.get("chosenPerDay")).append('\n');
        sb.append("- 达标天数（每天≥").append(joint.get("targetPerDay")).append("）：")
          .append(joint.get("daysMeetingTarget")).append(" / ").append(joint.get("distinctDays")).append('\n');
        sb.append("- 分类计数：").append(joint.get("byKind"))
          .append("（sameActivity 是碰巧同处一室做同类事，不计入上面的主动数）\n");
        sb.append("- 同一天里被两个以上的人动过手的项目数：").append(joint.get("sharedBuildsTotal"))
          .append("，按天：").append(joint.get("sharedBuildsPerDay"))
          .append("（不要求同时在场——他们仍然是一起做出了一件东西）\n");
        sb.append("- 每个项目最多有几个人动过手：").append(joint.get("distinctContributorsPerProject")).append('\n');
        for (Object rowObj : (List<Object>) joint.get("episodes")) {
            Map<String, Object> row = (Map<String, Object>) rowObj;
            if ("sameActivity".equals(row.get("kind"))) continue;
            sb.append("  - ").append(row.get("startedAt")).append(' ').append(row.get("kind"))
              .append(" @").append(row.get("place")).append("：").append(row.get("residentNames"))
              .append("，").append(row.get("subject")).append("，持续 ").append(row.get("minutes")).append(" 分钟\n");
        }
        sb.append('\n');

        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
