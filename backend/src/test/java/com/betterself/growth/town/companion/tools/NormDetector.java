package com.betterself.growth.town.companion.tools;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Looks through a run for regularities that nobody wrote down: candidates for "这个镇上的一条规矩".
 *
 * The acceptance goal this serves (docs/01-requirements.md 的「怎么验收」) is
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

    /** Minimum evidence before a regularity is even considered, calibrated at this value for
     * {@link #MIN_SUPPORT_CALIBRATION_RESIDENTS} residents. Four occurrences on two different days is
     * low, and deliberately so: the gate is here to reject one-offs, and everything past it still has
     * to beat its dimension's null baseline. Candidates that fail this are counted, never hidden.
     *
     * <p><b>This is no longer the gate by itself</b> - see {@link #minSupportFor}. docs/04-decisions.md
     * 「第二版定下来的」 says plainly that this "4" was calibrated for "a town of six" and does not survive
     * the jump to 25: C(6,2)=15 candidate pairs become C(25,2)=300, twenty times as many simultaneous
     * comparisons against the very same flat floor. A bigger town is not just more residents, it is
     * every dimension here racking up more raw events per unit time from sheer population churn alone,
     * with no new behaviour required - keeping this an absolute count would make the floor *relatively*
     * easier to clear exactly as the town grows, the opposite of what a calibrated instrument should do
     * when the thing it is calibrated against changes size. */
    public static final int MIN_SUPPORT = 4;
    /** The population {@link #MIN_SUPPORT} was tuned against - the six residents of the first version
     * (docs/01-requirements.md 第二版 "现有六人保留"). {@link #minSupportFor} is anchored here so it
     * reproduces {@link #MIN_SUPPORT} exactly at this population and never below it - nothing about a
     * run smaller than six loses the floor that history already validated. */
    public static final int MIN_SUPPORT_CALIBRATION_RESIDENTS = 6;
    public static final int MIN_DAYS = 2;
    /** How far above its own null a regularity has to sit. 1.5x is the "half again as often as chance"
     * line; below it we would be reporting the shape of the world, not a choice anyone made. */
    public static final double MIN_STRENGTH = 1.5;

    /**
     * {@link #MIN_SUPPORT}, scaled linearly by how many residents this run actually shows acting -
     * never a hand-fed town size, so a short diagnostic run with three residents present is judged by
     * three residents' worth of chance, not by whatever the town's eventual headcount will be, and a
     * run that quietly grew past six does not keep the old floor either (see {@link #countResidents}).
     * Never goes below {@link #MIN_SUPPORT}: that floor rejects one-offs regardless of population, and
     * shrinking it for a sparse run would make the gate easiest exactly where noise is worst.
     *
     * <p>Linear in residents, not in the C(6,2)=15 -&gt; C(25,2)=300 pair count docs/04 computes to
     * justify the change. That ratio explains <em>why</em> a flat floor rots as the town grows - more
     * residents means more simultaneous pairwise comparisons, so a fixed count gets relatively easier to
     * clear by chance somewhere among them - it is not a claim that the count itself should grow 20x.
     * Every dimension here except {@code pairAffinity} counts something that scales with population
     * roughly once per resident (a join, an actor's own seatings, a place-hour's cast), not with the
     * number of pairs; scaling the shared floor by C(n,2) would make those nearly unsatisfiable at 25
     * residents while barely moving the one dimension the ratio was computed from. {@code pairAffinity}
     * already carries its own defence against the pair-count explosion: its null baseline is the
     * *average* overlap across every pair actually seen this run, which grows with the same population
     * this method reads, so more candidate pairs raises the bar a specific pair must clear at the same
     * time as it raises how many pairs are being tested.
     */
    private static int minSupportFor(int residents) {
        return Math.max(MIN_SUPPORT,
            (int) Math.ceil(residents * (double) MIN_SUPPORT / MIN_SUPPORT_CALIBRATION_RESIDENTS));
    }

    /** The user's avatar. It has no {@link com.betterself.growth.town.companion.application.ResidentMind}
     * behind it - {@link com.betterself.growth.town.companion.domain.CompanionRules} drives it and nothing
     * else - so every one of its deeds is ours by construction, in a model run exactly as much as in the
     * rule-only control. Reading a norm out of it is the same category error as reading one out of the
     * seat allocator, and it is not a small one: in the neutral control the avatar alone was 146 of 287
     * seatings, and <em>all</em> 56 "sat in somebody else's spot" events in that run were its own. Left
     * in, it dragged {@code spotRespect}'s control candidate down to strength 1.21 - under
     * {@link #MIN_STRENGTH}, so the control produced no candidate, so nothing was subtracted, so the same
     * regularity would have come back out of a model run wearing the word "discovery". Excluded, the
     * control reads 81 seatings, 0 of them somebody else's, chance 22.8.
     *
     * <p>Excluded as an <b>actor</b> only. Residents' behaviour <em>towards</em> 我 is still theirs. */
    public static final String AVATAR = "self";

    /** A single resident who is not the avatar. Composite ids ({@code "owner,artist"}) are one joint
     * entry, not one person, and each caller splits them before asking. */
    private static boolean isResident(String actorId) {
        return actorId != null && !actorId.isBlank() && !actorId.equals(AVATAR) && !actorId.contains(",");
    }

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
     * A dimension that produced nothing appears as an explicit zero - same rule as {@link MetricsExporter}.
     * {@code beliefs} is the other half's raw material: every resident belief found in the run, as
     * {@code {ownerId, supersedesKey, text}}, verbatim and unjudged - see the class comment's
     * "居民能自己说出来" bullet for why this class stops at handing it over rather than grading it.
     *
     * <p>{@code sharedBeliefAudit} and {@code changedMind} are analyst-facing, not blind-reader-facing -
     * they never touch {@code beliefs}' verbatim material, which must stay unannotated so a blind reader
     * is not handed our own conclusions alongside the quotes (see {@link #beliefs} javadoc). Each row of
     * {@code sharedBeliefAudit} is one canonical key held by 2+ owners: which raw keys were folded
     * together, why, and which owners cleared {@link #isIndependentlyGrounded} - the auditability
     * docs/01 requires of the {@code sharedBeliefKeys} fix ("这个改法是被数据提示的，改完必须在一批新数据
     * 上验"). {@code changedMind} is one row per (owner, canonical key) that owner wrote under with 2+
     * distinct texts - see the class comment's "worth adding" metric. */
    public record Report(String label, List<Candidate> candidates, Map<String, Object> counts,
                         List<Map<String, Object>> dropped, List<Map<String, Object>> beliefs,
                         List<Map<String, Object>> sharedBeliefAudit, List<Map<String, Object>> changedMind) {}

    // ---- the run, in the shape the dimensions want it ---------------------------------------

    private record Act(String actorId, String projectId, String place, Instant at) {}

    /** {@code seatStates} is the complete record {@code TownPlaces}' seat-transition listener produces
     * (see {@code TimelineCollector}) - {@code kind: "seat_state"} entries, never throttled, each
     * carrying an exact "from" for its "to". {@code events} stays the narrative {@code "event"} stream
     * every other dimension already reads, took_spot/left_spot included - those are still throttled and
     * still used only for what a bystander would actually remark on, never for occupancy replay any
     * more (see {@link #seatStates}, {@link #takes}, {@link #seatRecordGaps}). */
    private record Run(List<Act> contributions, List<Map<String, Object>> events,
                        List<Map<String, Object>> seatStates, ZoneId zone) {}

    @SuppressWarnings("unchecked")
    private static Run read(List<Map<String, Object>> entries, ZoneId zone) {
        List<Act> acts = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> seatStates = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if ("seat_state".equals(e.get("kind"))) { seatStates.add(e); continue; }
            if (!"event".equals(e.get("kind"))) continue;
            Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
            events.add(e);
            if (!"contribution".equals(extra.get("eventType"))) continue;
            String project = (String) extra.get("projectId");
            String actor = (String) e.get("actorId");
            if (project == null || !isResident(actor)) continue;
            acts.add(new Act(actor, project, (String) extra.get("place"), Instant.parse((String) e.get("at"))));
        }
        acts.sort(Comparator.comparing(Act::at));
        return new Run(acts, events, seatStates, zone);
    }

    /** How many distinct residents (never {@link #AVATAR}) this run actually shows doing something,
     * across contributions, the narrative event stream, and the complete seat record. Feeds
     * {@link #minSupportFor} - see that method's own doc comment for why this is measured from the run
     * rather than taken as an external town-size parameter. */
    private static int countResidents(Run run) {
        Set<String> ids = new LinkedHashSet<>();
        for (Act a : run.contributions()) ids.add(a.actorId());
        for (Map<String, Object> e : run.events()) {
            Object actorField = e.get("actorId");
            if (actorField instanceof String s)
                for (String part : s.split(",")) if (isResident(part.trim())) ids.add(part.trim());
        }
        for (Map<String, Object> e : run.seatStates()) {
            Object actorField = e.get("actorId");
            if (actorField instanceof String s && isResident(s)) ids.add(s);
        }
        return ids.size();
    }

    public static Report detect(String label, List<Map<String, Object>> entries, String timezone) {
        return detect(label, entries, List.of(), timezone);
    }

    /**
     * Same detection, plus the "能说出来" material. {@code memories} is a run's exported
     * {@code world-snapshot.json} {@code memories} array (see {@link NormReportIT}); the beliefs in it -
     * the ones with a non-blank {@code supersedesKey}, since a belief is exactly a memory that superseded
     * an earlier one on the same key - are counted into {@link Report#counts()} and carried out verbatim
     * as {@link Report#beliefs()} for a blind reader, never judged here (see the class comment).
     *
     * <p>Judging whether a belief is about someone else needs a resident id → name table, and this
     * overload does not receive one from a file - it has only what {@code entries} itself carries. Every
     * timeline entry already pairs an {@code actorId} with the resident's real {@code actorName} (that is
     * how the exported timeline reads at all), so the table is built from that instead of being written
     * down here: whichever id this run actually saw act, under whatever name it acted, is what "someone
     * else" gets checked against. An id this run never saw named falls back to matching its bare id -
     * exactly the "只在读不到时才回退到 id 匹配" this was asked to do, just discovered from the run's own
     * data rather than a second file.
     */
    public static Report detect(String label, List<Map<String, Object>> entries,
                                 List<Map<String, Object>> memories, String timezone) {
        return detect(label, entries, memories, List.of(), timezone);
    }

    /**
     * Same again, plus the town's catalogue of places to sit and stand ({@code world-snapshot.json}'s
     * {@code positions}). Two of these dimensions exist because of what a blind reader - someone who had
     * seen nothing of this repository and only a stretch of the timeline - said the town's rules were,
     * unprompted, on both of two runs:
     *
     * <blockquote>谁在用什么东西，别人默认不动，除非物主表态。饮料、座位、桌子这些东西，谁在用就是谁的。<br>
     * 座位谁先占谁用，后来者道歉或让开。</blockquote>
     *
     * <p>Possession was the town's most legible rule and this instrument could not see one word of it.
     * <b>Writing a dimension after a blind reader names the thing it measures is instrument-building, not
     * fitting</b> - the dimension is a general rule, it still has to clear the same four gates, and it
     * still has to survive subtraction of the rule-only control. But it has NOT been confirmed: these two
     * were designed against runs already read, so their first honest test is a run that did not exist
     * when they were written.
     */
    public static Report detect(String label, List<Map<String, Object>> entries,
                                 List<Map<String, Object>> memories,
                                 List<Map<String, Object>> positions, String timezone) {
        Run run = read(entries, ZoneId.of(timezone));
        List<Candidate> raw = new ArrayList<>();
        Map<String, Object> counts = new TreeMap<>();
        List<Map<String, Object>> dropped = new ArrayList<>();
        raw.addAll(pairAffinity(run, counts));
        raw.addAll(whoJoinsWhom(run, counts));
        raw.addAll(occasions(run, counts));
        raw.addAll(reciprocity(run, counts));
        raw.addAll(spotRespect(run, positions, counts, dropped));
        raw.addAll(ownSpot(run, positions, counts));

        int residents = countResidents(run);
        int minSupport = minSupportFor(residents);
        counts.put("residents", residents);
        counts.put("minSupport", minSupport);

        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : raw) {
            String why = gate(c, minSupport);
            if (why == null) kept.add(c);
            else dropped.add(new LinkedHashMap<>(Map.of("dimension", c.dimension(), "key", c.key(),
                    "statement", c.statement(), "reason", why, "support", c.support(),
                    "strength", round(c.strength()))));
        }
        kept.sort(Comparator.comparingDouble(Candidate::strength).reversed());
        counts.put("candidates", kept.size());
        counts.put("dropped", dropped.size());
        counts.put("contributionEvents", run.contributions().size());
        List<Map<String, Object>> sharedBeliefAudit = new ArrayList<>();
        List<Map<String, Object>> changedMind = new ArrayList<>();
        List<Map<String, Object>> beliefs = beliefs(memories, residentNamesFrom(entries), counts,
                sharedBeliefAudit, changedMind);
        return new Report(label, List.copyOf(kept), counts, List.copyOf(dropped), beliefs,
                List.copyOf(sharedBeliefAudit), List.copyOf(changedMind));
    }

    /** {@code actorId → actorName}, read off the run's own timeline instead of a hand-kept table - see
     * the {@code detect(..., memories, ...)} overload's javadoc. A joint entry's composite id
     * ({@code "owner,artist"}) is split alongside its composite name ({@code "阿禾、知夏"}); a name that
     * never shows up singly still resolves once it appears in any joint entry.
     *
     * <p>{@code "self"} is skipped on purpose, not swept up by the same logic as every other id: it is
     * the codebase's own long-standing sentinel for "the user's avatar, not a resident" (see the repeated
     * {@code if (r.id.equals("self")) continue;} across the domain code, and {@link TimelineCollector}
     * exporting it under {@code actorName "我"}). Left in, every belief that so much as says "我" reads as
     * being about this phantom resident - which is how the first version of this method turned three
     * beliefs that are each entirely about their own owner into two false "about someone else" hits. */
    private static Map<String, String> residentNamesFrom(List<Map<String, Object>> entries) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            Object idField = e.get("actorId");
            Object nameField = e.get("actorName");
            if (!(idField instanceof String ids) || !(nameField instanceof String actorNames)) continue;
            String[] idParts = ids.split(",");
            String[] nameParts = actorNames.split("、");
            if (idParts.length != nameParts.length) continue;
            for (int i = 0; i < idParts.length; i++) {
                String id = idParts[i].trim(), nm = nameParts[i].trim();
                if (id.isBlank() || nm.isBlank() || id.equals("self")) continue;
                names.putIfAbsent(id, nm);
            }
        }
        return names;
    }

    // ---- 信念：材料，不是判决 -----------------------------------------------------------------

    /** Recurrence markers paired with a small list of relational-role verbs - see
     * {@link #mentionsRelationalRole}. */
    private static final List<String> RECURRENCE_MARKERS = List.of(
            "总是", "总要", "老是", "每次都", "每次", "从来", "一直都", "一直");
    private static final List<String> RELATIONAL_ROLE_VERBS = List.of(
            "配合", "迁就", "让着", "让步", "将就", "忍", "跟着", "陪着", "照顾", "带头", "出头",
            "张罗", "收拾", "操心", "护着", "兜着", "顶着", "扛着", "接着", "等着");

    /**
     * A belief is a memory that superseded an earlier one on the same key - docs/01-requirements.md 的「怎么验收」's own
     * definition of a norm is the same belief, held independently by enough residents, so
     * {@code sharedBeliefKeys} is that definition measured directly. This function only counts and
     * carries the material; whether any of it actually names a statistic's candidate is for the blind
     * reader (see the class comment's "居民能自己说出来" bullet).
     *
     * <p>Three things changed here for the 25-person town (docs/01「这一版还欠着的」):
     * <ul>
     *   <li><b>{@code sharedBeliefKeys} used to compare whole key strings.</b> 周野 and 青叔 each wrote
     *       {@code habit:fixer:lend_a_hand} / {@code habit:gardener:lend_a_hand} - identical but for
     *       their own id, which every {@code habit:} key always carries in its middle segment (see
     *       {@code ResidentSimulation}'s {@code habitBeliefDamping}/{@code validHabitKey}) - and 阿满
     *       wrote {@code weaver配合者} and {@code weaver-配合者} from one sentence. Both are folded by
     *       {@link #canonicalBeliefKey}, and every fold is dumped into {@link Report#sharedBeliefAudit()}
     *       with which raw keys it merged and why - a ruler that silently reclassifies data is not one
     *       anybody can check, and this fix is exactly the kind docs/01 says "必须在一批新数据上验".</li>
     *   <li><b>Two apparent holders can be one person having told the other.</b> {@code evidenceIds}
     *       chase back through each owner's own {@code reflection}/{@code belief} memories to whatever
     *       raw memory grounds them, and {@link #isIndependentlyGrounded} asks whether that trail ever
     *       reaches something this resident actually observed rather than only things they were told
     *       ({@code sourceType} "heard"). {@code sharedBeliefKeysIndependent} counts only the canonical
     *       keys where at least two <em>different</em> owners each clear that bar.</li>
     *   <li><b>{@code beliefsAboutOthers} only ever matched another resident's name</b>, which misses
     *       阿满's 「我好像总是那个配合的人」 - unnamed, and per docs/01 the most norm-like line measured
     *       so far. {@code beliefsAboutSocialRole} is a second, explicitly separate count for that shape
     *       (see {@link #mentionsRelationalRole}) rather than loosening the strict, unambiguous name
     *       match that {@code beliefsAboutOthers} still is.</li>
     * </ul>
     */
    private static List<Map<String, Object>> beliefs(List<Map<String, Object>> memories,
                                                       Map<String, String> residentNames,
                                                       Map<String, Object> counts,
                                                       List<Map<String, Object>> sharedBeliefAudit,
                                                       List<Map<String, Object>> changedMind) {
        record Belief(String ownerId, String supersedesKey, String text, List<String> evidenceIds) {}
        List<Belief> beliefs = new ArrayList<>();
        Map<String, Map<String, Object>> memoryById = new LinkedHashMap<>();
        for (Map<String, Object> m : memories) {
            if (m.get("id") instanceof String id) memoryById.put(id, m);
            if (!(m.get("supersedesKey") instanceof String sk) || sk.isBlank()) continue;
            List<String> evidenceIds = m.get("evidenceIds") instanceof List<?> evs
                    ? evs.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                    : List.of();
            beliefs.add(new Belief((String) m.get("ownerId"), sk, (String) m.get("text"), evidenceIds));
        }
        counts.put("beliefs", beliefs.size());

        Set<String> knownIds = new LinkedHashSet<>(residentNames.keySet());
        for (Belief b : beliefs) if (b.ownerId() != null) knownIds.add(b.ownerId());

        int aboutOthers = 0, aboutSocialRole = 0, grounded = 0;
        Set<String> holders = new LinkedHashSet<>();
        // canonical key -> ownerId -> "was any instance of this owner's belief independently grounded".
        Map<String, Map<String, Boolean>> groundedByCanonicalAndOwner = new LinkedHashMap<>();
        Map<String, Set<String>> rawKeysByCanonical = new LinkedHashMap<>();
        // ownerId -> canonical key -> raw keys / distinct texts seen under it, in write order - the
        // material for "谁改主意了". Canonicalized (not raw-key) so 阿满's two spellings of one topic
        // still land in the same bucket instead of hiding a real change of mind behind a typo.
        Map<String, Map<String, Set<String>>> rawKeysByOwnerAndCanonical = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> textsByOwnerAndCanonical = new LinkedHashMap<>();
        List<Map<String, Object>> material = new ArrayList<>();
        for (Belief b : beliefs) {
            if (b.ownerId() != null) holders.add(b.ownerId());
            if (mentionsSomeoneElse(b.ownerId(), b.supersedesKey(), b.text(), knownIds, residentNames)) aboutOthers++;
            if (mentionsRelationalRole(b.text())) aboutSocialRole++;
            boolean isGrounded = isIndependentlyGrounded(b.evidenceIds(), memoryById);
            if (isGrounded) grounded++;
            String canonical = canonicalBeliefKey(b.ownerId(), b.supersedesKey());
            rawKeysByCanonical.computeIfAbsent(canonical, k -> new LinkedHashSet<>()).add(b.supersedesKey());
            if (b.ownerId() != null) {
                groundedByCanonicalAndOwner.computeIfAbsent(canonical, k -> new LinkedHashMap<>())
                        .merge(b.ownerId(), isGrounded, Boolean::logicalOr);
                rawKeysByOwnerAndCanonical.computeIfAbsent(b.ownerId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(canonical, k -> new LinkedHashSet<>()).add(b.supersedesKey());
                textsByOwnerAndCanonical.computeIfAbsent(b.ownerId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(canonical, k -> new ArrayList<>()).add(b.text());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ownerId", b.ownerId());
            item.put("supersedesKey", b.supersedesKey());
            item.put("text", b.text());
            material.add(item);
        }
        counts.put("beliefsAboutOthers", aboutOthers);
        counts.put("beliefsAboutSocialRole", aboutSocialRole);
        counts.put("beliefsGroundedInOwnExperience", grounded);
        counts.put("beliefHolders", holders.size());

        int shared = 0, sharedIndependent = 0;
        for (var e : groundedByCanonicalAndOwner.entrySet()) {
            Map<String, Boolean> owners = e.getValue();
            if (owners.size() < 2) continue;
            shared++;
            long independentOwners = owners.values().stream().filter(Boolean::booleanValue).count();
            boolean countsAsIndependent = independentOwners >= 2;
            if (countsAsIndependent) sharedIndependent++;
            Set<String> rawKeys = rawKeysByCanonical.get(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("canonicalKey", e.getKey());
            row.put("rawKeys", List.copyOf(rawKeys));
            row.put("owners", owners);
            row.put("independentOwners", independentOwners);
            row.put("countsAsIndependentlyShared", countsAsIndependent);
            row.put("whySameKey", rawKeys.size() == 1 ? "键完全相同"
                    : "标准化后一致——habit 键去掉了所属人段，自由键去掉了标点/大小写差异");
            sharedBeliefAudit.add(row);
        }
        counts.put("sharedBeliefKeys", shared);
        counts.put("sharedBeliefKeysIndependent", sharedIndependent);

        for (var ownerEntry : textsByOwnerAndCanonical.entrySet())
            for (var keyEntry : ownerEntry.getValue().entrySet()) {
                List<String> distinctTexts = new ArrayList<>(new LinkedHashSet<>(keyEntry.getValue()));
                if (distinctTexts.size() < 2) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ownerId", ownerEntry.getKey());
                row.put("canonicalKey", keyEntry.getKey());
                row.put("rawKeys", List.copyOf(rawKeysByOwnerAndCanonical.get(ownerEntry.getKey()).get(keyEntry.getKey())));
                row.put("texts", distinctTexts);
                changedMind.add(row);
            }
        counts.put("changedTheirMind", changedMind.size());
        return List.copyOf(material);
    }

    /** Someone-else-not-self, checked by name where a name is known and by bare id otherwise - the
     * fallback the class comment on the {@code memories} overload describes. Deliberately strict (a
     * literal name/id match): see {@link #mentionsRelationalRole} for the separate, looser count. */
    private static boolean mentionsSomeoneElse(String selfId, String supersedesKey, String text,
                                                Set<String> knownIds, Map<String, String> residentNames) {
        String haystack = (supersedesKey == null ? "" : supersedesKey) + " " + (text == null ? "" : text);
        for (String id : knownIds) {
            if (id.equals(selfId)) continue;
            String otherName = residentNames.get(id);
            if ((otherName != null && haystack.contains(otherName)) || haystack.contains(id)) return true;
        }
        return false;
    }

    /**
     * A second, deliberately separate reading of "关于别人" for {@code beliefsAboutSocialRole}:
     * {@link #mentionsSomeoneElse} requires a named other resident and misses the most norm-like line
     * measured so far - 阿满's 「我好像总是那个配合的人」, which never names anyone. This looks instead for
     * a recurrence marker ("总是"/"每次都"/...) next to a small, general list of relational-role verbs
     * ("配合"/"迁就"/"带头"/...): a self-report of a <em>pattern in a relationship</em>, not merely a
     * private habit ("我总是坐在窗边" has the marker and no role verb, and stays out of this count).
     *
     * <p>This is a small, hand-picked heuristic, not a semantic judge. docs/01 warns for
     * {@code sharedBeliefKeys} that "这个改法是被数据提示的，改完必须在一批新数据上验"; the same caution
     * applies here even more so, since a hand-picked verb list reads perfectly on the run that inspired
     * it and may miss the next one entirely. Kept as a count next to, never merged into,
     * {@link #mentionsSomeoneElse} so the precise measurement is never diluted by this looser one.
     */
    private static boolean mentionsRelationalRole(String text) {
        if (text == null) return false;
        boolean recurs = RECURRENCE_MARKERS.stream().anyMatch(text::contains);
        boolean role = RELATIONAL_ROLE_VERBS.stream().anyMatch(text::contains);
        return recurs && role;
    }

    /**
     * Folds a raw {@code supersedesKey} down to the form {@code sharedBeliefKeys} should actually compare
     * - see {@link #beliefs}' javadoc for the two measured cases this exists to fix. A {@code habit:}
     * key is always {@code habit:<ownerId>:<habitId>} (every habit key names its own owner in the middle
     * segment, never someone else's - see {@code ResidentSimulation.validHabitKey}), so that segment is
     * replaced with a wildcard rather than compared; anything else is a free key straight from the model,
     * normalized the same way as the habit id itself. If the middle segment does not actually match the
     * owner passed in (data this method did not expect), it falls back to normalizing the whole string
     * rather than guessing which segment to drop - conservative, so a malformed key merges with nothing
     * rather than merging with the wrong thing.
     */
    private static String canonicalBeliefKey(String ownerId, String rawKey) {
        if (rawKey == null) return "";
        if (rawKey.startsWith("habit:")) {
            String[] parts = rawKey.split(":", 3);
            if (parts.length == 3 && (ownerId == null || parts[1].equals(ownerId)))
                return "habit:*:" + normalizeFreeText(parts[2]);
            return "habit:*:" + normalizeFreeText(rawKey.substring("habit:".length()));
        }
        return normalizeFreeText(rawKey);
    }

    /** Letters and digits only, case-folded - drops the hyphen that turned one of 阿满's keys
     * ({@code weaver配合者} / {@code weaver-配合者}) into two, without needing to know in advance which
     * punctuation mark would show up. Unicode-aware, so Chinese characters (which are not touched by
     * {@link Character#toLowerCase(int)} in any way that changes them) pass through unchanged. */
    private static String normalizeFreeText(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(Character.toLowerCase(cp));
        });
        return out.toString();
    }

    /**
     * Whether at least one of this belief's own evidence memories traces back - through as many
     * {@code reflection}/{@code belief} hops as it takes - to something this resident actually observed
     * or experienced ({@code sourceType} anything but "heard") rather than only ever being told.
     * docs/01-requirements.md's definition of a norm is a belief "各自独立持有"; with 25 residents and
     * hearsay, four owners of one key can be one person's observation retold three times, and counting
     * that as four independent holders is exactly the fake finding this project has been burned by five
     * times (see the task brief this class's changes were made against).
     *
     * <p>Deliberately recursive rather than checking only the belief's immediate evidence: a resident
     * could otherwise launder hearsay through their own earlier reflection (heard "X" -&gt; reflected
     * into "我觉得 X" -&gt; cited as evidence for a new belief). A one-hop check would read that
     * reflection's {@code sourceType} ("reflection", not "heard") and call it firsthand; this instead
     * keeps following evidence chains down until it hits either a raw memory that is not hearsay
     * (independent) or runs out of chain without ever finding one (not independent -
     * {@code applyReflection} refuses to write a belief with no evidence at all, so reaching "not
     * independent" here means every traceable root was hearsay, not that there was nothing to check).
     *
     * <p><b>How this can still be fooled</b> - it trusts this codebase's own "heard" vs everything-else
     * tagging rather than tracing actual information flow, so any write site that logs something as
     * "observed" when the resident only knows it secondhand would defeat it silently; it asks only
     * "was there ANY firsthand root", not whether the belief's actual content came from that root, so one
     * flimsy, unrelated observed memory sitting in an evidence list beside several heard ones is enough
     * to mark the whole belief independent; and two residents who were both physically present at the
     * same conversation and wrote near-identical conclusions both pass as independent even though they
     * may simply be reporting the one shared moment rather than two minds converging on it separately -
     * a case worth a blind reader's eye, not a statistic's.
     */
    private static boolean isIndependentlyGrounded(List<String> evidenceIds, Map<String, Map<String, Object>> memoryById) {
        Set<String> visited = new LinkedHashSet<>();
        for (String id : evidenceIds) if (groundedInOwnExperience(id, memoryById, visited)) return true;
        return false;
    }

    private static boolean groundedInOwnExperience(String id, Map<String, Map<String, Object>> memoryById,
                                                    Set<String> visited) {
        if (id == null || !visited.add(id)) return false;
        Map<String, Object> m = memoryById.get(id);
        if (m == null) return false; // evidence not present in this export - cannot confirm, so it does not ground anything
        String type = (String) m.get("sourceType");
        if ("heard".equals(type)) return false; // told, not witnessed - a dead end, not a root
        if (!"reflection".equals(type) && !"belief".equals(type)) return true; // a raw, non-hearsay root
        if (m.get("evidenceIds") instanceof List<?> deeper)
            for (Object next : deeper)
                if (next instanceof String s && groundedInOwnExperience(s, memoryById, visited)) return true;
        return false;
    }

    private static String gate(Candidate c, int minSupport) {
        if (c.support() < minSupport) return "支持事件不足 " + minSupport;
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
     * so a town where the same person keeps walking over to other people's work is saying something.
     *
     * <p><b>An undefended hazard, documented rather than fixed</b> - this dimension's {@code starter} map
     * is exactly "who touched a project first", the same shape docs/01「这一版还欠着的」explicitly forbids
     * turning into its own dimension ("『谁最先动手』也不能是写死的...环路跑通了，起点是我们放的"): in the
     * rule-only control 阿满's own "first to act" count was 0 by construction, so every one of her
     * contributions was a join by definition, which is part of how she came to write 「我好像总是那个配合
     * 的人」. This dimension's null baseline (each actor's join count against the town's evenly-split
     * average) does not model how project-creation opportunity itself was allocated, so a resident who
     * structurally gets fewer chances to be first will read as an unusually devoted joiner even with no
     * preference of their own. Left as-is on purpose - fixing it means modelling our own opportunity
     * allocation, which is a rule-side change outside this file's scope, not a new statistic here. */
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
                if (isResident(actor.trim())) moments.add(new Moment(place, Instant.parse((String) e.get("at")), actor.trim()));
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

    /** The one metric docs/05-notes.md says to keep if we may only keep one, and the one that cannot be
     * written as a rule: we can write "A 帮了 B", we cannot write "B 后来自发地更愿意帮 A". At the event
     * volumes this town currently reaches it will usually fail {@link #minSupportFor} and be dropped -
     * that report is the true one, and is more useful than a ratio computed over three events.
     *
     * <p>Shares {@link #whoJoinsWhom}'s undefended hazard: {@code helps} is built from the same
     * "who touched this project first" {@code starter} map, so a resident with structurally fewer
     * chances to start something will also show up "helping" more often here, independent of any choice
     * of theirs. See that method's doc comment - documented, not fixed, per docs/01's explicit ban on
     * turning "who acts first" into its own dimension. */
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

    // ---- 占用权：有主的位置，别人绕着走 ------------------------------------------------------

    private record Spot(String id, String place, String ownerId) {}

    @SuppressWarnings("unchecked")
    private static List<Spot> spots(List<Map<String, Object>> positions) {
        List<Spot> out = new ArrayList<>();
        for (Map<String, Object> p : positions) {
            Object id = p.get("id"), place = p.get("place");
            if (id == null || place == null) continue;
            Object owner = p.get("ownerId");
            out.add(new Spot((String) id, (String) place, owner == null ? null : String.valueOf(owner)));
        }
        return out;
    }

    /** One entry from the complete seat record (see {@code Run#seatStates}): who, where they came
     * from, where they landed - {@code to == null} is a plain departure to nowhere, {@code from == null}
     * is arriving from nowhere. Unlike the narrative {@code took_spot}/{@code left_spot} pair this
     * replaces, a single row carries both halves of one hop, so there is nothing left to go missing
     * between them. */
    private record SeatState(String residentId, String from, String to, Instant at) {}

    @SuppressWarnings("unchecked")
    private static List<SeatState> seatStates(Run run) {
        List<SeatState> out = new ArrayList<>();
        for (Map<String, Object> e : run.seatStates()) {
            Map<String, Object> extra = (Map<String, Object>) e.getOrDefault("extra", Map.of());
            String actor = (String) e.get("actorId");
            if (!isResident(actor)) continue;
            Object from = extra.get("from"), to = extra.get("positionId");
            out.add(new SeatState(actor, from == null ? null : String.valueOf(from),
                to == null ? null : String.valueOf(to), Instant.parse((String) e.get("at"))));
        }
        out.sort(Comparator.comparing(SeatState::at));
        return out;
    }

    /** Sentinel for {@link #seatRecordGaps}: this run carries no complete seat record at all - an
     * export from before {@code TownPlaces}' seat-transition listener existed. That is not "zero gaps
     * found", it is "nothing to check in the first place", and it must decline exactly the way an
     * actual gap does rather than read as a clean replay. */
    private static final int NO_COMPLETE_SEAT_RECORD = -1;

    /**
     * How many times the complete seat record skips a step: a resident's landing names a "from" that
     * does not match wherever this replay last saw them. With the narrative {@code took_spot}/
     * {@code left_spot} stream this used to be common - {@code TownPlaces} suppresses that stream on a
     * 30-minute cooldown for non-priority seats (added to stop seat churn from flooding the timeline,
     * and it worked), which left the exported timeline a lossy record of who was sitting where: in the
     * neutral control, 52 of 287 seat changes skipped their {@code left_spot}. The complete record
     * (see {@code TownPlaces.SeatTransitionNote}) is produced outside that throttle specifically so
     * this should now read a real, gap-free 0 whenever it exists at all - see
     * {@link #NO_COMPLETE_SEAT_RECORD} for the one case where there is nothing to replay from.
     */
    private static int seatRecordGaps(Run run) {
        List<SeatState> states = seatStates(run);
        if (states.isEmpty()) return NO_COMPLETE_SEAT_RECORD;
        Map<String, String> held = new LinkedHashMap<>();
        int gaps = 0;
        for (SeatState s : states) {
            String expected = held.get(s.residentId());
            if (!Objects.equals(expected, s.from())) gaps++;
            if (s.to() == null) held.remove(s.residentId()); else held.put(s.residentId(), s.to());
        }
        return gaps;
    }

    /** Every time somebody actually landed somewhere, in order - read off the complete seat record, not
     * the narrative event stream, so a seat churned through faster than the narrative stream's own
     * throttle still counts here. */
    private record Take(String residentId, String spotId, Instant at) {}

    private static List<Take> takes(Run run) {
        List<Take> out = new ArrayList<>();
        for (SeatState s : seatStates(run)) if (s.to() != null) out.add(new Take(s.residentId(), s.to(), s.at()));
        return out;
    }

    /**
     * <b>有主的位置，别人绕着走。</b> The town has three spots in shared rooms that belong to somebody -
     * one window seat, one garden plot, one counter - standing among interchangeable unowned ones. Nobody
     * wrote a rule preventing anyone from using them, so how often they get taken by other people is a
     * choice the residents are making.
     *
     * <p>The null is availability: if a resident sat down indifferent to whose spot it was, they would
     * land on somebody else's as often as those spots make up <b>the part of the room that was actually
     * free at that moment</b>. Avoidance shows as the observed rate falling <em>below</em> that, so this
     * candidate's strength is the expected-over-observed ratio - the only dimension here that reads a
     * norm out of something not happening.
     *
     * <p>"Actually free" is the whole dimension, and the first version of it did not have that half. It
     * asked the catalogue whether the room <em>contains</em> a spot belonging to someone else and counted
     * every seating in such a room as a chance declined - 131 of them, against a chance of 35, which read
     * like the clearest finding of the round. Most of those spots were occupied by their own owners at
     * the time. Nobody was walking around anything.
     *
     * <p>Availability is reconstructed from the run's complete seat record (see {@link #seatStates}),
     * and when that record is missing or has gaps in it ({@link #seatRecordGaps}) this dimension
     * <b>declines to answer</b> rather than answer from a replay it knows is wrong.
     */
    private static List<Candidate> spotRespect(Run run, List<Map<String, Object>> positions,
                                               Map<String, Object> counts, List<Map<String, Object>> dropped) {
        List<Spot> catalogue = spots(positions);
        Map<String, Spot> byId = new LinkedHashMap<>();
        for (Spot spot : catalogue) byId.put(spot.id(), spot);
        Map<String, List<Spot>> byPlace = groupBy(catalogue, Spot::place);

        List<Take> takes = takes(run);
        counts.put("spotTakes", takes.size());
        int gaps = seatRecordGaps(run);
        counts.put("seatRecordGaps", gaps);
        if (gaps != 0) {
            counts.put("spotTakesWhereSomeoneElsesWasFree", 0);
            String reason = gaps == NO_COMPLETE_SEAT_RECORD
                ? "这份导出没有完整的座位记录（早于按 from/to 记录的改动），说不出当时那个位置空不空"
                : "座位记录有 " + gaps + " 处缺口，说不出当时那个位置空不空";
            dropped.add(new LinkedHashMap<>(Map.of(
                "dimension", "spotRespect", "key", "town",
                "statement", "有主的位置，别人绕着走",
                "reason", reason,
                "support", 0, "strength", 0.0)));
            return List.of();
        }

        // Occupancy, replayed off the complete seat record. Sound because that record has no gaps in
        // it (checked above) - each row already carries both halves of one hop (from and to), so there
        // is nothing left to pair up the way the narrative took_spot/left_spot stream used to require.
        Map<String, Set<String>> occupants = new LinkedHashMap<>();
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (Map<String, Object> p : positions) {
            Object id = p.get("id");
            if (id == null) continue;
            Object cap = p.get("capacity");
            capacity.put(String.valueOf(id), cap instanceof Number n && n.intValue() > 0 ? n.intValue() : 1);
        }

        int considered = 0, tookSomeoneElses = 0;
        double expected = 0;
        Set<String> days = new LinkedHashSet<>();
        Set<String> whoRespected = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        for (SeatState s : seatStates(run)) {
            String actor = s.residentId();
            if (s.from() != null) occupants.computeIfAbsent(s.from(), k -> new LinkedHashSet<>()).remove(actor);
            if (s.to() == null) continue;
            String spotId = s.to();
            Spot landed = byId.get(spotId);
            if (landed != null) {
                List<Spot> here = byPlace.getOrDefault(landed.place(), List.of());
                // Free right before this resident sat down - the spot they are about to take included,
                // since it was free until they took it.
                List<Spot> free = new ArrayList<>();
                for (Spot spot : here) {
                    int taken = occupants.getOrDefault(spot.id(), Set.of()).size();
                    if (spot.id().equals(spotId) || taken < capacity.getOrDefault(spot.id(), 1)) free.add(spot);
                }
                List<Spot> freeOwnedByOthers = free.stream()
                    .filter(spot -> spot.ownerId() != null && !spot.ownerId().equals(actor)).toList();
                // A choice exists only when somebody else's spot was standing there free AND there was
                // somewhere else to go. Either half missing and the seating says nothing either way -
                // which is the half the first version of this dimension left out: it asked the catalogue
                // whether the room contains somebody else's spot, never whether that spot was available,
                // and counted 81 seatings as 81 chances not taken.
                if (!freeOwnedByOthers.isEmpty() && freeOwnedByOthers.size() < free.size()) {
                    considered++;
                    expected += (double) freeOwnedByOthers.size() / free.size();
                    days.add(s.at().atZone(run.zone()).toLocalDate().toString());
                    if (landed.ownerId() != null && !landed.ownerId().equals(actor)) tookSomeoneElses++;
                    else {
                        whoRespected.add(actor);
                        if (evidence.size() < 3) evidence.add(s.at() + " " + actor + " 坐了 " + landed.id()
                            + "（" + freeOwnedByOthers.get(0).id() + " 当时空着）");
                    }
                }
            }
            occupants.computeIfAbsent(spotId, k -> new LinkedHashSet<>()).add(actor);
        }
        counts.put("spotTakesWhereSomeoneElsesWasFree", considered);
        if (considered == 0) return List.of();
        return List.of(new Candidate("spotRespect", "town",
            "有主的位置，别人绕着走（" + considered + " 次有得选的落座里只有 " + tookSomeoneElses
                + " 次坐了别人的位置，碰运气该有 " + Math.round(expected) + " 次）",
            considered - tookSomeoneElses, days.size(),
            (expected + 0.5) / (tookSomeoneElses + 0.5), 0, whoRespected.size(), false, evidence));
    }

    /**
     * <b>谁总坐同一个地方。</b> The other half of the same rule, from the owner's side, and the example the
     * reflection prompt itself offers a resident when it asks them to look for something recurring -
     * 某个人总是坐在某个位置. Concentration against the number of places they could have sat instead.
     */
    private static List<Candidate> ownSpot(Run run, List<Map<String, Object>> positions, Map<String, Object> counts) {
        List<Spot> catalogue = spots(positions);
        Map<String, Spot> byId = new LinkedHashMap<>();
        for (Spot spot : catalogue) byId.put(spot.id(), spot);
        Map<String, List<Spot>> byPlace = groupBy(catalogue, Spot::place);

        Map<String, List<Take>> byResident = groupBy(takes(run), Take::residentId);
        List<Candidate> out = new ArrayList<>();
        for (var e : byResident.entrySet()) {
            Map<String, Integer> perSpot = new LinkedHashMap<>();
            Set<String> days = new LinkedHashSet<>();
            Set<String> reachable = new LinkedHashSet<>();
            for (Take take : e.getValue()) {
                Spot landed = byId.get(take.spotId());
                if (landed == null) continue;
                perSpot.merge(take.spotId(), 1, Integer::sum);
                days.add(take.at().atZone(run.zone()).toLocalDate().toString());
                for (Spot spot : byPlace.getOrDefault(landed.place(), List.of())) reachable.add(spot.id());
            }
            if (perSpot.isEmpty() || reachable.size() < 2) continue;
            var favourite = perSpot.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            int total = perSpot.values().stream().mapToInt(Integer::intValue).sum();
            double share = (double) favourite.getValue() / total;
            double even = 1.0 / reachable.size();
            out.add(new Candidate("ownSpot", e.getKey(),
                e.getKey() + " 总是坐在 " + favourite.getKey() + "（" + total + " 次里 " + favourite.getValue() + " 次）",
                favourite.getValue(), days.size(), share / even, 0, perSpot.size(), false, List.of()));
        }
        return out;
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
        sb.append("\n## 共享信念键是怎么折叠的\n\n");
        sb.append("分析用的审计材料，不给盲读的人看——折的是键，不是判决。\n\n");
        if (report.sharedBeliefAudit().isEmpty()) sb.append("没有任何 key 被两个以上的人各自持有。\n");
        for (Map<String, Object> a : report.sharedBeliefAudit())
            sb.append("- 键 `").append(a.get("canonicalKey")).append("`（原始：").append(a.get("rawKeys"))
              .append("，").append(a.get("whySameKey")).append("）：持有者 ").append(a.get("owners"))
              .append("，其中独立成立 ").append(a.get("independentOwners")).append(" 人，")
              .append((Boolean) a.get("countsAsIndependentlyShared") ? "**算独立共享**" : "不算独立共享")
              .append("\n");

        sb.append("\n## 有没有人改主意了\n\n");
        sb.append("这是全部数字里最想盯的一个——不是 beliefs，也不是 beliefsAboutOthers。任何规则都造不出它。\n\n");
        if (report.changedMind().isEmpty()) sb.append("没有。**这是一个真实的 0**，不是没找。\n");
        for (Map<String, Object> m : report.changedMind())
            sb.append("- `").append(m.get("ownerId")).append("`（键 `").append(m.get("canonicalKey"))
              .append("`）先后写过：").append(m.get("texts")).append("\n");

        sb.append("\n## 居民自己说出来的话\n\n");
        sb.append("这一节不判断，只是把材料递给盲读的人 —— 上面的统计从不读这里。\n\n");
        if (report.beliefs().isEmpty()) sb.append("没有。**这是一个真实的 0**，不是没找。\n");
        for (Map<String, Object> b : report.beliefs())
            sb.append("- `").append(b.get("ownerId")).append("`（键 `").append(b.get("supersedesKey"))
              .append("`）：").append(b.get("text")).append("\n");
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
