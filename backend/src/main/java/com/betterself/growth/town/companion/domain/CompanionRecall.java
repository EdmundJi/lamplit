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

    /**
     * Fixed capacity of {@link #selfAccount} - "自述文档" (docs/01 「心智」): "一份固定容量的短文（十几行）
     * ——我是谁、我对谁怎么看、这里通常会怎样、我欠谁什么". Twelve, matching that "十几行" ("a dozen or so
     * lines") literally, and bounding the PROMPT, never the store: {@link
     * com.betterself.growth.town.companion.domain.ResidentSimulation#MEMORIES_PER_RESIDENT} stays
     * uncapped for beliefs on purpose (see that constant's own doc comment) so a resident who has
     * formed more than twelve standing views is never made to forget one, only to leave the least
     * recently reaffirmed ones out of any one prompt - the same "store cap does not bound the prompt"
     * split every other retrieval limit in this file (10, 20, 8) already draws.
     */
    public static final int SELF_ACCOUNT_CAPACITY = 12;
    /**
     * Fixed capacity of {@link #workingSet} - "小工作集" (docs/01 「心智」). Deliberately the same 10 the
     * ordinary decision context has retrieved since before this batch (see {@code
     * ResidentDirector.perspective}), so replacing a situation-ranked mixed-tier retrieve() with this
     * raw-only, change-cut one does not grow that half of the prompt - only what fills those 10 slots
     * changes, from a pool up to half own-voice reflection (see {@link #OWN_VOICE_SHARE}'s own note) to
     * this resident's own raw experience since they were last asked anything at all.
     */
    public static final int WORKING_SET_CAPACITY = 10;

    /**
     * "自述文档" - this resident's own currently-true standing material, two parts, beliefs first:
     * <ol>
     *   <li><b>Live beliefs</b> - every belief-tier ({@link CompanionWorld.Memory#sourceType()}
     *   {@code =="belief"}) memory this resident holds that nothing has since superseded, most
     *   recently affirmed first. This is "我对谁怎么看、这里通常会怎样、我欠谁什么" (docs/01) - the part
     *   that grows and revises, via the one guarded door this project trusts for it, {@code
     *   ResidentSimulation#applyReflection}, which already refuses any evidence id that is not a real
     *   memory this same resident owns.</li>
     *   <li><b>Seed backstory backfills whatever capacity beliefs leave over</b> - every
     *   {@code seed} memory this resident was written with at genesis, oldest (most foundational)
     *   first. This is "我是谁" (docs/01), and it must be here rather than left to {@link #workingSet}:
     *   a seed's own timestamp predates the town starting, so the instant any {@code since} floor is
     *   non-null - which happens the very first time this resident is ever asked anything at all, see
     *   {@link CompanionWorld.ResidentState#lastAskedAt} - {@code workingSet} excludes it forever.
     *   Caught in review before this shipped: with beliefs at ~0 in every measured run (progress.md's
     *   "信念为什么是 0") and seed backstory reachable nowhere else, a resident would have permanently
     *   lost their own origin from every prompt the first time anyone spoke to them, gaining nothing in
     *   its place until a belief finally forms - the very failure this whole batch exists to fix,
     *   reintroduced by omission. {@code ResidentSimulation}'s own eviction ranking already protects
     *   seed memories in the STORE ("他在这条街开始之前是谁"); this is that same protection extended to
     *   the PROMPT.</li>
     * </ol>
     * Both halves are "currently true" rather than "recently written", which is the whole reason this
     * is a view over existing memories and not a fourth field bolted onto {@link
     * CompanionWorld.ResidentState} (docs/01 asks for one short document; a survey during this batch
     * floated a dedicated {@code SelfAccount} record instead, and a second, separately-stored
     * representation of "the same fact, again" would only give the two copies room to drift apart). A
     * belief is currently true because reaffirming it (choosing the same key again in a later
     * reflection - see {@code ResidentMind.StandingBeliefView}) is itself evidence it still holds,
     * which is why it outranks backstory whenever both are on offer; a seed is currently true because
     * nothing ever supersedes it - it is not "recent", it is foundational, and it never competes with a
     * belief for the same slot, only backfills what beliefs do not use. Bounded at {@link
     * #SELF_ACCOUNT_CAPACITY} regardless of how many beliefs a resident has formed - a belief always
     * wins that bound over backstory (see {@code selfAccountNeverLetsSeedBackstoryCrowdOutARealBelief}
     * in {@code CompanionRecallTest}), which only matters once a resident has formed more standing
     * views than the whole document has room for.
     */
    public static List<Memory> selfAccount(List<Memory> memories, String ownerId, Instant now, int limit) {
        if (ownerId == null || now == null || memories == null || limit <= 0) return List.of();
        List<Memory> beliefs = memories.stream()
            .filter(memory -> ownerId.equals(memory.ownerId()))
            .filter(memory -> memory.at() != null && !memory.at().isAfter(now))
            .filter(memory -> "belief".equals(memory.sourceType()))
            .filter(memory -> !memory.superseded())
            .sorted(Comparator.comparing(Memory::at).reversed().thenComparing(Memory::id))
            .limit(limit)
            .toList();
        if (beliefs.size() >= limit) return beliefs;
        List<Memory> seeds = memories.stream()
            .filter(memory -> ownerId.equals(memory.ownerId()))
            .filter(memory -> memory.at() != null && !memory.at().isAfter(now))
            .filter(memory -> "seed".equals(memory.sourceType()))
            .sorted(Comparator.comparing(Memory::at).thenComparing(Memory::id))
            .limit(limit - beliefs.size())
            .toList();
        List<Memory> account = new java.util.ArrayList<>(beliefs);
        account.addAll(seeds);
        return List.copyOf(account);
    }

    /**
     * "小工作集" - what has actually happened to this resident, in their own raw terms, since {@code
     * since} (docs/01 「心智」: "上次被问之后发生在你身上的事"; docs/04: "不按计时器切"). Raw tier only
     * ({@link #tier}{@code (sourceType)==0}, i.e. {@code seed}/{@code observed}/{@code heard}): a
     * {@code reflection} or {@code belief} is a synthesis this resident already produced, not new
     * material, and excluding that tier here - rather than merely capping its share the way {@link
     * #retrieve} does - is what removes the own-voice flood at its root instead of leaving room for it
     * to grow back. Ordered newest first and capped at {@code limit}: this is an account of what
     * happened, not an answer to a question, so it is deliberately NOT relevance-ranked against any
     * query - a relevance ranking is exactly the mechanism that let stale-but-important material crowd
     * out what actually just happened before (see {@link #retrieve}'s own note on why recency and
     * layer used to agree with each other every time).
     *
     * <p>{@code since} is exclusive-or-null-floor, not a duration: null means no floor at all (a brand
     * new resident, or an old save from before whatever marker the caller uses existed), so the result
     * is simply the most recent {@code limit} raw memories on record - correct for "nothing has been
     * asked of them yet" without a special case. A non-null {@code since} is normally the caller's own
     * "last asked" marker, which is itself set by rule-detected change (an encounter, a plan ending, a
     * perceivable difference), never a clock - see {@link CompanionWorld.ResidentState#lastAskedAt}'s
     * own doc comment for why that makes this a change-cut rather than a timer even though the
     * comparison here is a plain instant check.
     */
    public static List<Memory> workingSet(List<Memory> memories, String ownerId, Instant since, Instant now, int limit) {
        if (ownerId == null || now == null || memories == null || limit <= 0) return List.of();
        return memories.stream()
            .filter(memory -> ownerId.equals(memory.ownerId()))
            .filter(memory -> memory.at() != null && !memory.at().isAfter(now))
            .filter(memory -> since == null || memory.at().isAfter(since))
            .filter(memory -> tier(memory.sourceType()) == 0)
            .sorted(Comparator.comparing(Memory::at).reversed().thenComparing(Memory::id))
            .limit(limit)
            .toList();
    }

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
