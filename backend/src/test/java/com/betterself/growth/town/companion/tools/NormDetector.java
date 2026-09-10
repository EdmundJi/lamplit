package com.betterself.growth.town.companion.tools;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Looks through a run for regularities that nobody wrote down: candidates for "这个镇上的一条规矩".
 *
 * The acceptance goal this serves (docs/06-society.md 七) is
 * <em>同一份种子跑两次，两次都长出一条我们从没写过的规矩，而且至少有一个居民能自己说出来</em>,
 * and every part of this class exists to stop that sentence from being satisfied cheaply:
 *
 * <ul>
 *   <li><b>"我们从没写过的"</b> is not decided by a hand-kept list of our own rules - that list would
 *       rot, and we would be judging our own case. It is decided by a <b>negative control</b>: the same
 *       world, same seed, model switched off. Whatever the rules alone produce is by definition ours,
 *       so {@link #notWrittenByUs} subtracts the control's candidates from the run's. A detector that
 *       fires on a rule-only run is not reporting a discovery, it is reporting our own code back to us.</li>
 *   <li><b>"长出来的"</b> requires beating a null baseline computed inside the same run (each dimension
 *       says how), not merely being frequent. 分工 is frequent because everyone has their own habit.</li>
 *   <li><b>"不是台钟"</b> - every candidate carries {@code minuteSpread} and {@code distinctCasts}, and
 *       one that recurs at the same minute with the same cast is marked {@link Candidate#clockLike}
 *       and dropped. 2026-09-10 的那次「每天 3 次」就是这个形状（01-02 16:04 / 01-03 16:04 /
 *       01-04 16:03 / 01-05 16:03，同一个项目同一批人）。</li>
 *   <li><b>"居民能自己说出来"</b> is deliberately <em>not</em> decided here. Matching a belief's text to a
 *       statistic by keyword would let us grade our own homework; that half goes to a reader who has
 *       not seen the repo (see {@link Verdict} and docs/04-decisions.md「盲测要真的盲」). This class
 *       cannot return {@link Stage#MET} on its own.</li>
 * </ul>
 *
 * Reads only what a run already exports - the timeline entry maps of {@link TimelineCollector} - so it
 * can be re-run over past runs without spending another model call.
 */
public final class NormDetector {
    private NormDetector() {}

    /** Minimum evidence before a regularity is even considered. Four occurrences on two different days
     * is low, and deliberately so: the gate is here to reject one-offs, and everything past it still has
     * to beat its dimension's null baseline. Candidates that fail this are counted, never hidden. */
    public static final int MIN_SUPPORT = 4;
    public static final int MIN_DAYS = 2;
    /** How far above its own null a regularity has to sit. 1.5x is the "half again as often as chance"
     * line; below it we would be reporting the shape of the world, not a choice anyone made. */
    public static final double MIN_STRENGTH = 1.5;

    /**
     * @param strength     observed over the dimension's own null baseline; 1.0 means indistinguishable from chance
     * @param minuteSpread standard deviation, in minutes, of the time of day this recurs at
     * @param variants     how many different casts - or different things - it happened across; the
     *                     anti-timer count, one means it was the same people on the same thing every time
     * @param clockLike    same minute, same people, every time - a timer wearing a norm's clothes
     */
    public record Candidate(String dimension, String key, String statement, int support, int days,
                            double strength, double minuteSpread, int variants, boolean clockLike,
                            List<String> evidence) {}

    /** {@code dropped} keeps every regularity that failed a gate together with which gate it failed.
     * A dimension that produced nothing appears as an explicit zero - same rule as {@link MetricsExporter}. */
    public record Report(String label, List<Candidate> candidates, Map<String, Object> counts,
                         List<Map<String, Object>> dropped) {}

    // ---- the run, in the shape the dimensions want it ---------------------------------------

    private record Act(String actorId, String projectId, String place, Instant at) {}

    private record Run(List<Act> contributions, List<Map<String, Object>> events, ZoneId zone) {}

    @SuppressWarnings("unchecked")
    private static Run read(List<Map<String, Object>> entries, ZoneId zone) {
        List<Act> acts = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (!"event".equals(e.get("kind"))) continue;
            Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
            events.add(e);
            if (!"contribution".equals(extra.get("eventType"))) continue;
            String project = (String) extra.get("projectId");
            String actor = (String) e.get("actorId");
            if (project == null || actor == null || actor.isBlank()) continue;
            acts.add(new Act(actor, project, (String) extra.get("place"), Instant.parse((String) e.get("at"))));
        }
        acts.sort(Comparator.comparing(Act::at));
        return new Run(acts, events, zone);
    }

    public static Report detect(String label, List<Map<String, Object>> entries, String timezone) {
        Run run = read(entries, ZoneId.of(timezone));
        List<Candidate> raw = new ArrayList<>();
        Map<String, Object> counts = new TreeMap<>();
        raw.addAll(pairAffinity(run, counts));
        raw.addAll(whoJoinsWhom(run, counts));
        raw.addAll(occasions(run, counts));
        raw.addAll(reciprocity(run, counts));

        List<Candidate> kept = new ArrayList<>();
        List<Map<String, Object>> dropped = new ArrayList<>();
        for (Candidate c : raw) {
            String why = gate(c);
            if (why == null) kept.add(c);
            else dropped.add(new LinkedHashMap<>(Map.of("dimension", c.dimension(), "key", c.key(),
                    "statement", c.statement(), "reason", why, "support", c.support(),
                    "strength", round(c.strength()))));
        }
        kept.sort(Comparator.comparingDouble(Candidate::strength).reversed());
        counts.put("candidates", kept.size());
        counts.put("dropped", dropped.size());
        counts.put("contributionEvents", run.contributions().size());
        return new Report(label, List.copyOf(kept), counts, List.copyOf(dropped));
    }

    private static String gate(Candidate c) {
        if (c.support() < MIN_SUPPORT) return "支持事件不足 " + MIN_SUPPORT;
        if (c.days() < MIN_DAYS) return "只发生在一天里";
        if (c.clockLike()) return "同一分钟同一批人——这是台钟，不是规矩";
        if (c.strength() < MIN_STRENGTH) return "没有比它自己的零假设高出 " + MIN_STRENGTH + " 倍";
        return null;
    }

    // ---- 搭伙：两个人反复落在同一件事上 --------------------------------------------------------

    /** Null baseline: <b>the other pairs in this same town</b>. Asking "would these two land together by
     * luck" against a pool built from the run's own contributions cannot work - build the pool with their
     * contributions in it and a devoted pair vouches for itself; build it without and their project is
     * impossible under the null. What is answerable is "is this pair unusual <em>here</em>", so the
     * comparison is against the average overlap across every pair of contributors in the run. */
    private static List<Candidate> pairAffinity(Run run, Map<String, Object> counts) {
        Map<String, List<Act>> byActor = groupBy(run.contributions(), Act::actorId);
        // Sorted, so that a pair has one key and not two. Unsorted, "weaver+gardener" in the control and
        // "gardener+weaver" in the run are different strings, and notWrittenByUs hands back as a discovery
        // the very thing the control was built to subtract.
        List<String> actors = new ArrayList<>(new java.util.TreeSet<>(byActor.keySet()));
        Map<String, Integer> overlaps = new LinkedHashMap<>();
        Map<String, Set<String>> sharedBy = new LinkedHashMap<>();
        int possiblePairs = 0;
        long totalOverlap = 0;
        for (int i = 0; i < actors.size(); i++) {
            for (int j = i + 1; j < actors.size(); j++) {
                String a = actors.get(i), b = actors.get(j);
                possiblePairs++;
                Map<String, Integer> ca = countByProject(byActor.get(a)), cb = countByProject(byActor.get(b));
                Set<String> shared = new LinkedHashSet<>(ca.keySet());
                shared.retainAll(cb.keySet());
                int overlap = 0;
                for (String pr : shared) overlap += Math.min(ca.get(pr), cb.get(pr));
                totalOverlap += overlap;
                if (overlap == 0) continue;
                overlaps.put(a + "+" + b, overlap);
                sharedBy.put(a + "+" + b, shared);
            }
        }
        counts.put("pairsSharingAProject", overlaps.size());
        counts.put("contributorPairs", possiblePairs);
        if (overlaps.isEmpty()) return List.of();
        double average = totalOverlap / (double) Math.max(1, possiblePairs);
        List<Candidate> out = new ArrayList<>();
        for (var e : overlaps.entrySet()) {
            String[] pair = e.getKey().split("\\+");
            Set<String> shared = sharedBy.get(e.getKey());
            List<Act> support = new ArrayList<>();
            for (Act act : run.contributions())
                if ((act.actorId().equals(pair[0]) || act.actorId().equals(pair[1])) && shared.contains(act.projectId()))
                    support.add(act);
            Spread spread = spread(support, run.zone());
            out.add(new Candidate("pairAffinity", e.getKey(),
                    name(pair[0]) + " 和 " + name(pair[1]) + " 会落在同一件事上：" + String.join("、", shared),
                    support.size(), spread.days(), (e.getValue() + 0.5) / (average + 0.5),
                    spread.minuteSpread(), shared.size(),
                    spread.clockLike() && shared.size() <= 1, evidence(support)));
        }
        return out;
    }

    private static Map<String, Integer> countByProject(List<Act> acts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Act a : acts) out.merge(a.projectId(), 1, Integer::sum);
        return out;
    }

    // ---- 搭手：谁走向别人已经开了头的事 --------------------------------------------------------

    /** A join is a contribution to a project somebody else touched first. Nothing in the rules picks a
     * person to be the one who joins - habits are keyed to each resident's own place and own project -
     * so a town where the same person keeps walking over to other people's work is saying something. */
    private static List<Candidate> whoJoinsWhom(Run run, Map<String, Object> counts) {
        Map<String, String> starter = new LinkedHashMap<>();
        Map<String, List<Act>> joinsBy = new LinkedHashMap<>();
        int joins = 0;
        for (Act act : run.contributions()) {
            String first = starter.putIfAbsent(act.projectId(), act.actorId());
            if (first == null || first.equals(act.actorId())) continue;
            joinsBy.computeIfAbsent(act.actorId(), k -> new ArrayList<>()).add(act);
            joins++;
        }
        counts.put("joins", joins);
        if (joins == 0) return List.of();
        int actors = groupBy(run.contributions(), Act::actorId).size();
        double even = (double) joins / Math.max(1, actors);
        List<Candidate> out = new ArrayList<>();
        for (var e : joinsBy.entrySet()) {
            Spread spread = spread(e.getValue(), run.zone());
            Set<String> whose = new LinkedHashSet<>();
            for (Act act : e.getValue()) whose.add(starter.get(act.projectId()));
            out.add(new Candidate("whoJoins", e.getKey(),
                    name(e.getKey()) + " 是会走过去搭手的那个（去过 " + whose.size() + " 个人开的头）",
                    e.getValue().size(), spread.days(), e.getValue().size() / Math.max(0.5, even),
                    spread.minuteSpread(), whose.size(), spread.clockLike() && whose.size() <= 1,
                    evidence(e.getValue())));
        }
        return out;
    }

    // ---- 场合：反复在同一个地方、同一个时段聚起来 ------------------------------------------------

    /** A gathering, not a busy hour. Two things the first version of this got wrong and that the cafe
     * exposed immediately: counting <em>events</em> makes every hour the cafe is open look like an
     * occasion, and comparing a bucket to the town-wide average makes the busiest place win every time.
     * So this counts <b>windows in which two different residents were in the same place within a quarter
     * of an hour</b>, and compares each hour against <b>that same place's own other hours</b> - the
     * question is whether people converge at a particular time here, not whether this is where the town
     * happens to live. (Composite actor ids like {@code "owner,artist"} are split; a joint event is two
     * people, and counting the pair as one stranger inflated the cast to ten in a town of six.) */
    @SuppressWarnings("unchecked")
    private static List<Candidate> occasions(Run run, Map<String, Object> counts) {
        record Moment(String place, Instant at, String actor) {}
        List<Moment> moments = new ArrayList<>();
        for (Map<String, Object> e : run.events()) {
            Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
            String type = (String) extra.get("eventType");
            if (!Set.of("contribution", "conversation", "greeting", "celebration").contains(type)) continue;
            String place = (String) extra.get("place");
            String actorField = (String) e.get("actorId");
            if (place == null || actorField == null || actorField.isBlank()) continue;
            for (String actor : actorField.split(","))
                if (!actor.isBlank()) moments.add(new Moment(place, Instant.parse((String) e.get("at")), actor.trim()));
        }
        moments.sort(Comparator.comparing(Moment::at));

        record Window(String place, Instant at, Set<String> cast) {}
        List<Window> windows = new ArrayList<>();
        Map<String, List<Moment>> byPlace = groupBy(moments, Moment::place);
        for (var placeEntry : byPlace.entrySet()) {
            List<Moment> list = placeEntry.getValue();
            for (int i = 0; i < list.size(); ) {
                Instant open = list.get(i).at();
                Set<String> cast = new LinkedHashSet<>();
                int j = i;
                while (j < list.size() && list.get(j).at().isBefore(open.plusSeconds(15 * 60))) {
                    cast.add(list.get(j).actor());
                    j++;
                }
                if (cast.size() >= 2) windows.add(new Window(placeEntry.getKey(), open, cast));
                i = j;
            }
        }
        counts.put("gatheringWindows", windows.size());
        if (windows.isEmpty()) return List.of();

        Map<String, Integer> windowsPerPlace = new LinkedHashMap<>();
        Map<String, Set<Integer>> hoursPerPlace = new LinkedHashMap<>();
        for (Window w : windows) {
            windowsPerPlace.merge(w.place(), 1, Integer::sum);
            hoursPerPlace.computeIfAbsent(w.place(), k -> new LinkedHashSet<>())
                    .add(w.at().atZone(run.zone()).getHour());
        }
        Map<String, List<Window>> byBucket = new LinkedHashMap<>();
        for (Window w : windows)
            byBucket.computeIfAbsent(w.place() + "@" + w.at().atZone(run.zone()).getHour(), k -> new ArrayList<>()).add(w);

        List<Candidate> out = new ArrayList<>();
        for (var e : byBucket.entrySet()) {
            String place = e.getKey().substring(0, e.getKey().lastIndexOf('@'));
            String hour = e.getKey().substring(e.getKey().lastIndexOf('@') + 1);
            Set<String> people = new LinkedHashSet<>();
            Set<String> days = new LinkedHashSet<>();
            Set<String> casts = new LinkedHashSet<>();
            List<Integer> minutes = new ArrayList<>();
            for (Window w : e.getValue()) {
                var local = w.at().atZone(run.zone());
                people.addAll(w.cast());
                days.add(local.toLocalDate().toString());
                minutes.add(local.getHour() * 60 + local.getMinute());
                casts.add(String.join(",", new java.util.TreeSet<>(w.cast())));
            }
            double expected = windowsPerPlace.get(place) / (double) Math.max(1, hoursPerPlace.get(place).size());
            double sd = stdDev(minutes);
            out.add(new Candidate("occasion", e.getKey(),
                    hour + " 点前后，" + placeName(place) + "里会凑起人来（" + String.join("、", people) + "）",
                    e.getValue().size(), days.size(), e.getValue().size() / Math.max(0.5, expected),
                    sd, casts.size(), sd < 5 && casts.size() <= 1, List.of()));
        }
        return out;
    }

    // ---- 互惠：你帮过我，我后来更愿意帮你 ------------------------------------------------------

    /** The one metric docs/06-society.md says to keep if we may only keep one, and the one that cannot be
     * written as a rule: we can write "A 帮了 B", we cannot write "B 后来自发地更愿意帮 A". At the event
     * volumes this town currently reaches it will usually fail {@link #MIN_SUPPORT} and be dropped - that
     * report is the true one, and is more useful than a ratio computed over three events. */
    private static List<Candidate> reciprocity(Run run, Map<String, Object> counts) {
        Map<String, String> starter = new LinkedHashMap<>();
        record Help(String from, String to, Instant at) {}
        List<Help> helps = new ArrayList<>();
        for (Act act : run.contributions()) {
            String first = starter.putIfAbsent(act.projectId(), act.actorId());
            if (first == null || first.equals(act.actorId())) continue;
            helps.add(new Help(act.actorId(), first, act.at()));
        }
        counts.put("helps", helps.size());
        int returned = 0;
        List<String> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Help h : helps) {
            for (Help earlier : helps) {
                if (earlier.at().isAfter(h.at())) continue;
                if (!earlier.from().equals(h.to()) || !earlier.to().equals(h.from())) continue;
                returned++;
                if (seen.add(h.from() + "->" + h.to()))
                    evidence.add(name(earlier.from()) + " 先帮了 " + name(earlier.to()) + "，后来 "
                            + name(h.from()) + " 帮回去");
                break;
            }
        }
        if (helps.isEmpty()) return List.of();
        // Chance level: if help went to a uniformly chosen other resident, a pair would close by luck at
        // roughly 1/(residents-1) of the second help.
        int residents = Math.max(2, groupBy(run.contributions(), Act::actorId).size());
        double expected = helps.size() / (double) (residents - 1);
        Set<String> days = new LinkedHashSet<>();
        for (Help h : helps) days.add(h.at().atZone(run.zone()).toLocalDate().toString());
        return List.of(new Candidate("reciprocity", "town",
                "帮过你的人，你后来会帮回去（" + returned + "/" + helps.size() + " 次搭手是还回去的）",
                returned, days.size(), (returned + 0.5) / (expected + 0.5), 0, returned, false, evidence));
    }

    // ---- 减掉规则自己就能产生的那些 -------------------------------------------------------------

    /**
     * The run's candidates minus everything the rule-only control produced on the same dimension and key.
     * This is the whole "不可直写" gate: the control ran the same world with the model switched off, so
     * anything it grew is ours. A candidate that survives is one the residents' own choices made.
     */
    public static List<Candidate> notWrittenByUs(Report run, Report ruleOnlyControl) {
        Set<String> ours = new LinkedHashSet<>();
        for (Candidate c : ruleOnlyControl.candidates()) ours.add(c.dimension() + "|" + c.key());
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : run.candidates()) if (!ours.contains(c.dimension() + "|" + c.key())) out.add(c);
        return List.copyOf(out);
    }

    /** How far the acceptance goal got. {@link Stage#MET} is unreachable from inside this class on purpose. */
    public enum Stage {
        /** Nothing beat its own null baseline. */
        NO_NORM,
        /** Everything found also appears in the rule-only control: we wrote it. */
        WRITTEN_BY_US,
        /** Both runs grew the same regularity, which means the seed did it, not the residents. */
        SAME_IN_BOTH_RUNS,
        /** Two runs, two different unwritten regularities. The statistics are done; now someone who has
         * not read this repository has to find it in the residents' own words. */
        AWAITING_SPOKEN_CHECK,
        /** A blind reader found it stated by a resident. Only {@link #withSpokenBy} can set this. */
        MET
    }

    public record Verdict(Stage stage, String reason, List<Candidate> fromRunA, List<Candidate> fromRunB,
                          String spokenBy) {
        /** Records a blind reader's finding. Nothing inside {@link NormDetector} may call this for itself. */
        public Verdict withSpokenBy(String residentId, String quotedBelief) {
            if (stage != Stage.AWAITING_SPOKEN_CHECK)
                return new Verdict(stage, reason + "（有人报了一句居民的话，但统计这一半还没过）", fromRunA, fromRunB, residentId);
            return new Verdict(Stage.MET, "盲读的人在 " + residentId + " 自己的话里读到了它：" + quotedBelief,
                    fromRunA, fromRunB, residentId);
        }
    }

    public static Verdict judge(Report runA, Report runB, Report ruleOnlyControl) {
        List<Candidate> a = notWrittenByUs(runA, ruleOnlyControl);
        List<Candidate> b = notWrittenByUs(runB, ruleOnlyControl);
        if (a.isEmpty() || b.isEmpty()) {
            boolean anything = !runA.candidates().isEmpty() || !runB.candidates().isEmpty();
            return new Verdict(anything ? Stage.WRITTEN_BY_US : Stage.NO_NORM,
                    anything ? "两次跑里剩下的规律，关掉模型也照样长出来" : "没有任何规律高过它自己的零假设",
                    a, b, null);
        }
        Set<String> keysA = new LinkedHashSet<>();
        for (Candidate c : a) keysA.add(c.dimension() + "|" + c.key());
        boolean identical = true;
        for (Candidate c : b) if (!keysA.contains(c.dimension() + "|" + c.key())) identical = false;
        if (identical && a.size() == b.size())
            return new Verdict(Stage.SAME_IN_BOTH_RUNS, "两次长出的是同一条——那是种子决定的，不是他们决定的", a, b, null);
        return new Verdict(Stage.AWAITING_SPOKEN_CHECK,
                "两次各长出了一条关掉模型不会有的规律；还差最后一半：镇上得有人自己说得出它", a, b, null);
    }

    // ---- writing it out -----------------------------------------------------------------------

    /** The report a person reads. Dropped candidates are printed in full and on purpose: which
     * regularities <em>failed</em> which gate is the part that tells us where the town is thin. */
    public static String markdown(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 这个镇上有没有长出规矩：").append(report.label()).append("\n\n");
        sb.append("| 数 | 值 |\n| --- | --- |\n");
        for (var e : report.counts().entrySet())
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        sb.append("\n## 候选（过了闸门的）\n\n");
        if (report.candidates().isEmpty()) sb.append("没有。**这是一个真实的 0**，不是没算。\n");
        for (Candidate c : report.candidates()) {
            sb.append("### ").append(c.statement()).append("\n\n");
            sb.append("- 维度 `").append(c.dimension()).append("`，键 `").append(c.key()).append("`\n");
            sb.append("- 支持 ").append(c.support()).append(" 件，跨 ").append(c.days()).append(" 天，")
              .append("强度 ").append(round(c.strength())).append(" 倍\n");
            sb.append("- 时间散布 ").append(round(c.minuteSpread())).append(" 分钟，")
              .append(c.variants()).append(" 种不同的组合\n");
            for (String ev : c.evidence()) sb.append("  - ").append(ev).append("\n");
            sb.append("\n");
        }
        sb.append("## 被闸门挡下的\n\n");
        if (report.dropped().isEmpty()) sb.append("没有。\n");
        for (Map<String, Object> d : report.dropped())
            sb.append("- `").append(d.get("dimension")).append("` ").append(d.get("statement"))
              .append(" —— **").append(d.get("reason")).append("**\n");
        return sb.toString();
    }

    // ---- small shared pieces ------------------------------------------------------------------

    private record Spread(int days, double minuteSpread, boolean clockLike) {}

    private static Spread spread(List<Act> acts, ZoneId zone) {
        Set<String> days = new LinkedHashSet<>();
        List<Integer> minutes = new ArrayList<>();
        for (Act a : acts) {
            var local = a.at().atZone(zone);
            days.add(local.toLocalDate().toString());
            minutes.add(local.getHour() * 60 + local.getMinute());
        }
        double sd = stdDev(minutes);
        return new Spread(days.size(), sd, sd < 5);
    }

    private static double stdDev(List<Integer> values) {
        if (values.size() < 2) return 0;
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
        double sum = 0;
        for (int v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static List<String> evidence(List<Act> acts) {
        List<String> out = new ArrayList<>();
        for (Act a : acts.subList(0, Math.min(3, acts.size())))
            out.add(a.at() + " " + name(a.actorId()) + " → " + a.projectId());
        return out;
    }

    private static Set<String> projectsOf(List<Act> acts) {
        Set<String> out = new LinkedHashSet<>();
        for (Act a : acts) out.add(a.projectId());
        return out;
    }

    private static <T> Map<String, List<T>> groupBy(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        for (T t : items) out.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t);
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String name(String residentId) {
        return residentId == null ? "?" : residentId;
    }

    private static String placeName(String place) {
        return place == null ? "某处" : place;
    }
}
