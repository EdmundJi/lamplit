package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import com.betterself.growth.town.companion.domain.TownPlaces;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Watches a {@link CompanionWorld} across many {@code advance()} calls and pulls out every diary
 * entry, world event, conversation turn, memory and relationship change as it appears.
 *
 * Why this exists at all: the domain caps diary/events/memories/conversations as rolling windows
 * (diary 80, events 80, memories 200, conversations 24 - see CompanionRules/ResidentSimulation).
 * That is the right choice for a live save, but an accelerated run spanning several simulated days
 * blows straight through every one of those caps. Calling {@link #capture} after every tick - far
 * more often than any cap could fill within a single {@code advance()} call - means nothing that
 * ever appeared in the world is lost, even after the world itself evicts it.
 */
public final class TimelineCollector {
    private final Map<String, String> residentNames = new HashMap<>(Map.of(
        "owner", "阿禾", "student", "小川", "artist", "知夏", "gardener", "青叔"));

    private final Set<String> seenDiary = new HashSet<>();
    private final Set<String> seenEvents = new HashSet<>();
    private final Set<String> seenMemories = new HashSet<>();
    private final Map<String, Integer> seenTurnCount = new HashMap<>(); // conversationId -> turns already captured
    private final Map<String, Map<String, Integer>> lastRelationships = new HashMap<>(); // residentId -> (otherId -> value)
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private String avatarName = "我";

    /** Every {@link CompanionWorld.ServiceRequest} ever seen, keyed by id, always holding the latest
     * snapshot of its mutable fields (status moves waiting -> preparing -> delivered -> consumed/
     * abandoned/cold over many ticks). Needed for the same reason as everything else here: the domain
     * caps {@code w.serviceRequests} to the most recent 60 (see CafeService#request), so a multi-day
     * run would otherwise lose most of its service history to eviction. See MetricsExporter. */
    private final Map<String, Map<String, Object>> serviceRequestsById = new LinkedHashMap<>();

    /** How many of the four NPC residents (never the avatar - "四个人" in the requirements means the
     * four residents) are standing in the same place, sampled once per {@link #capture}: the key is a
     * group size (1-4), the value how many (tick, place) groups of that size were observed. A run
     * where this histogram piles up at 3-4 is four people who never leave each other's side; spread
     * across 1-2 is a town that actually disperses. See MetricsExporter's "同一时刻同一地点" metric. */
    private final Map<Integer, Long> coLocationHistogram = new TreeMap<>();

    /** Every stretch of time in which two or more residents were doing one thing together, keyed by
     * episode id and holding who, where, what, and how long. Co-location is not this: four people who
     * happen to be in the cafe are four people in a room. What is counted here is a shared subject -
     * the same project under two pairs of hands, or one person having gone over to sit with another -
     * and it is counted once per episode, not once per tick, so a long afternoon together is one
     * thing that happened and not three hundred.
     * <p>The reason this exists as its own metric: a full measured day produced zero project
     * completions, because a communal project caps at 75% until it has as many contributors as it
     * needs (see ResidentSimulation's create/help branch) and nobody ever put their hands on somebody
     * else's. "Two people doing one thing" is the thinnest line in this town, and a thin line that is
     * not measured is a thin line nobody notices staying thin. */
    private final Map<String, Map<String, Object>> jointEpisodes = new LinkedHashMap<>();
    /** Open episode id per subject key, plus when that key was last seen, so consecutive ticks extend
     * one episode and a real gap starts a new one. */
    private final Map<String, String> openEpisodeId = new HashMap<>();
    private final Map<String, Instant> openEpisodeLastSeen = new HashMap<>();
    private int jointEpisodeSequence;

    /** Which world's seat-transition listener (see {@link TownPlaces#setSeatTransitionListener}) this
     * collector currently holds - set once per world id so repeated {@link #capture} calls across many
     * ticks do not keep re-registering the same lambda. */
    private String seatListenerWorldId;
    /**
     * Hands the listener back. Registering without ever unregistering is how this kind of side table
     * turns into a leak with teeth: the registry is keyed by world id and lives for the life of the
     * JVM, so a finished collector goes on receiving seat changes from the <em>next</em> world that
     * happens to reuse that id - quietly mixing another run's rows into a list this one already
     * considers complete. Surefire runs many tests in one JVM and world ids in this codebase are
     * short fixed strings, so that collision is ordinary, not exotic.
     *
     * <p>Idempotent, and safe to call on a collector that never attached.
     */
    public void detach() {
        if (seatListenerWorldId == null) return;
        TownPlaces.setSeatTransitionListener(seatListenerWorldId, null);
        seatListenerWorldId = null;
    }

    /** A {@code took_spot}/{@code left_spot} world event carries no field for "was this the kind of
     * seat change a bystander would actually remark on" - {@code TownPlaces.WorldEvent} is a fixed
     * shape this file must not grow. The seat-transition listener (see {@link #onSeatTransition})
     * knows that answer at the exact moment the change happens, from context that will already be
     * gone by the time {@link #captureEvents} sees the narrative event for the same change a moment
     * later - so it is stashed here, keyed the same way, and consumed (removed) the first time a
     * matching narrative event is captured. */
    private final Map<String, String> pendingSeatNotices = new HashMap<>();

    private static String seatNoticeKey(String residentId, String type, String positionId, Instant at) {
        return residentId + "|" + type + "|" + positionId + "|" + at;
    }

    /** All entries captured so far, in capture order (not necessarily chronological - sort by "at" before export). */
    public List<Map<String, Object>> entries() {
        return entries;
    }

    public Collection<Map<String, Object>> serviceRequests() {
        return serviceRequestsById.values();
    }

    public Map<Integer, Long> coLocationHistogram() {
        return coLocationHistogram;
    }

    public Collection<Map<String, Object>> jointEpisodes() {
        return jointEpisodes.values();
    }

    public void capture(CompanionWorld w) {
        if (w == null) return;
        avatarName = w.name;
        for (Actor actor : w.residents) residentNames.put(actor.id(), actor.name());
        attachSeatListener(w);
        captureDiary(w);
        captureEvents(w);
        captureMemories(w);
        captureDialogue(w);
        captureRelationshipChanges(w);
        captureServiceRequests(w);
        captureCoLocation(w);
        captureJointAction(w);
    }

    /** How long a shared subject may go unseen before the next sighting counts as a new episode
     * rather than a continuation - generously more than one tick (8 simulated seconds with a model,
     * 60 without), so a single missed sample does not split one afternoon in two. */
    private static final long JOINT_EPISODE_GAP_SECONDS = 300;
    /** Actions that are joinable work: doing one of these next to somebody doing the same is the
     * weak, coincidental tier of togetherness (see "sameActivity" below), never the headline. */
    private static final Set<String> SHARED_ACTIVITIES = Set.of("study", "read", "work", "make", "create", "help", "observe", "rest", "tend");

    /** One tick's worth of "who is doing one thing with whom". Three tiers, deliberately kept apart
     * because they are worth very different amounts:
     * <ul>
     * <li>{@code sharedProject} - two or more people with their hands on the SAME project at the same
     *     place. This is the unambiguous reading of "两个人一起做同一件事" and the one the town has
     *     never managed.
     * <li>{@code satTogether} - somebody chose to go and sit with somebody else ({@code join}). Real
     *     togetherness, weaker: being beside someone is not doing their thing with them.
     * <li>{@code sameActivity} - two people in one place doing the same kind of thing, neither of the
     *     above. Mostly coincidence: everybody's place habits point at the cafe, so this fires without
     *     anybody having chosen anybody. Counted so it can be SUBTRACTED from the impression the other
     *     two give, never added to it.
     * </ul>
     * Conversations are not counted at all. Talking is together, but the town already produces
     * conversations by the dozen and folding them in here would make the number meaningless.
     */
    private void captureJointAction(CompanionWorld w) {
        Instant atNow = w.simulatedAt == null ? w.updatedAt : w.simulatedAt;
        if (atNow == null) return;
        Instant at = atNow;
        Map<String, ResidentState> byId = new HashMap<>();
        for (ResidentState r : w.residentStates) byId.put(r.id, r);
        Map<String, Actor> actors = new HashMap<>();
        for (Actor a : w.residents) actors.put(a.id(), a);
        if (w.avatar != null) actors.put(w.avatar.id(), w.avatar);

        Set<String> claimed = new HashSet<>(); // ids already counted in a stronger tier this tick

        // Tier 1: the same project under more than one pair of hands.
        Map<String, List<String>> byProject = new LinkedHashMap<>();
        for (var e : byId.entrySet()) {
            ResidentState r = e.getValue();
            Actor a = actors.get(r.id);
            if (r.plan == null || a == null || a.activity().equals("walk")) continue;
            if (!Set.of("create", "help").contains(r.plan.action()) || r.plan.targetId() == null) continue;
            if (!a.place().equals(r.plan.place())) continue;
            byProject.computeIfAbsent(r.plan.targetId() + "@" + a.place(), k -> new ArrayList<>()).add(r.id);
        }
        for (var e : byProject.entrySet()) {
            if (e.getValue().size() < 2) continue;
            claimed.addAll(e.getValue());
            String projectId = e.getKey().substring(0, e.getKey().lastIndexOf('@'));
            String place = e.getKey().substring(e.getKey().lastIndexOf('@') + 1);
            noteJointEpisode(w, "sharedProject", place, projectId, e.getValue(), at);
        }

        // Tier 1b: standing round a finished thing together. Counted with the chosen ones and
        // labelled apart from them, because it is a different thing from working side by side: they
        // are not making it any more, they were called over to look at what they made. It was left
        // out of the first version of this metric by oversight rather than by decision - "create or
        // help" was all that came to mind - and it stayed invisible for as long as celebrating did.
        Map<String, List<String>> byGathering = new LinkedHashMap<>();
        for (var e : byId.entrySet()) {
            ResidentState r = e.getValue();
            Actor a = actors.get(r.id);
            if (r.plan == null || a == null || a.activity().equals("walk")) continue;
            if (!"celebrate".equals(r.plan.action()) || r.plan.targetId() == null) continue;
            if (!a.place().equals(r.plan.place())) continue;
            byGathering.computeIfAbsent(r.plan.targetId() + "@" + a.place(), k -> new ArrayList<>()).add(r.id);
        }
        for (var e : byGathering.entrySet()) {
            if (e.getValue().size() < 2) continue;
            claimed.addAll(e.getValue());
            int split = e.getKey().lastIndexOf('@');
            noteJointEpisode(w, "gathered", e.getKey().substring(split + 1), e.getKey().substring(0, split), e.getValue(), atNow);
        }

        // Tier 2: somebody went over and sat with somebody else.
        for (var e : byId.entrySet()) {
            ResidentState r = e.getValue();
            Actor a = actors.get(r.id);
            if (r.plan == null || a == null || !"join".equals(r.plan.action()) || r.plan.targetId() == null) continue;
            Actor other = actors.get(r.plan.targetId());
            if (other == null || !other.place().equals(a.place())) continue;
            if (claimed.contains(r.id)) continue;
            claimed.add(r.id); claimed.add(other.id());
            noteJointEpisode(w, "satTogether", a.place(), r.plan.targetId(), List.of(r.id, other.id()), at);
        }

        // Tier 3: the coincidental one - same place, same kind of thing, nobody chose anybody.
        Map<String, List<String>> byPlaceActivity = new LinkedHashMap<>();
        for (var e : byId.entrySet()) {
            Actor a = actors.get(e.getKey());
            if (a == null || claimed.contains(a.id()) || !SHARED_ACTIVITIES.contains(a.activity())) continue;
            byPlaceActivity.computeIfAbsent(a.place() + "|" + a.activity(), k -> new ArrayList<>()).add(a.id());
        }
        for (var e : byPlaceActivity.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String[] parts = e.getKey().split("\\|", 2);
            noteJointEpisode(w, "sameActivity", parts[0], parts[1], e.getValue(), at);
        }
    }

    /** Opens a new episode for this (kind, place, subject, cast) or extends the open one. The cast is
     * part of the key on purpose: a third person joining two others is a different thing happening,
     * and collapsing it into the first would hide exactly the growth this metric exists to see. */
    private void noteJointEpisode(CompanionWorld w, String kind, String place, String subject, List<String> ids, Instant at) {
        List<String> cast = ids.stream().distinct().sorted().toList();
        String key = kind + "|" + place + "|" + subject + "|" + String.join(",", cast);
        Instant lastSeen = openEpisodeLastSeen.get(key);
        boolean continues = lastSeen != null && java.time.Duration.between(lastSeen, at).getSeconds() <= JOINT_EPISODE_GAP_SECONDS;
        if (!continues) {
            String id = "je-" + (++jointEpisodeSequence);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("kind", kind);
            row.put("place", place);
            row.put("subject", subject);
            row.put("residentIds", cast);
            row.put("residentNames", cast.stream().map(this::nameOf).toList());
            row.put("startedAt", at.toString());
            row.put("endedAt", at.toString());
            row.put("minutes", 0L);
            jointEpisodes.put(id, row);
            openEpisodeId.put(key, id);
        } else {
            Map<String, Object> row = jointEpisodes.get(openEpisodeId.get(key));
            if (row != null) {
                row.put("endedAt", at.toString());
                row.put("minutes", java.time.Duration.between(Instant.parse((String) row.get("startedAt")), at).toMinutes());
            }
        }
        openEpisodeLastSeen.put(key, at);
    }

    private void captureDiary(CompanionWorld w) {
        for (Entry d : w.diary) {
            if (seenDiary.add(d.id())) {
                add(d.at(), "diary", "self", avatarName, d.text(), Map.of());
            }
        }
    }

    private void captureEvents(CompanionWorld w) {
        for (WorldEvent e : w.events) {
            if (seenEvents.add(e.id())) {
                List<String> names = e.actorIds().stream().map(this::nameOf).toList();
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("eventType", e.type());
                extra.put("place", e.place());
                if (e.projectId() != null) extra.put("projectId", e.projectId());
                // Which spot, not just which room: cafe-window-seat belongs to 小川 and cafe-window-2
                // through 6 belong to nobody, and they all read as "窗边的位子" in the sentence. Without
                // the id, the one dimension that can see the town's most legible rule is blind.
                if (e.positionId() != null) extra.put("positionId", e.positionId());
                // Whether this particular seat change is the kind a bystander would remark on - see
                // the note on pendingSeatNotices for why this has to be filled in from a side channel
                // rather than a field on the event itself.
                if (SEAT_EVENT_TYPES.contains(e.type()) && e.positionId() != null && e.actorIds().size() == 1) {
                    String notice = pendingSeatNotices.remove(seatNoticeKey(e.actorIds().get(0), e.type(), e.positionId(), e.at()));
                    if (notice != null) extra.put("noticeReason", notice);
                }
                add(e.at(), "event", String.join(",", e.actorIds()), String.join("、", names), e.text(), extra);
            }
        }
    }

    private static final Set<String> SEAT_EVENT_TYPES = Set.of("took_spot", "left_spot");

    /** Registers this collector as the given world's one seat-transition listener (see
     * {@link TownPlaces#setSeatTransitionListener}), once per world id - repeated {@link #capture}
     * calls across many ticks of the same run must not keep re-registering the same lambda. This is
     * how the complete, gap-free seat record (see {@link #onSeatTransition}) gets produced at all:
     * nothing else in this file drives it, and a caller that never calls {@link #capture} simply never
     * gets one - which is exactly the legacy-export case {@code NormDetector} is built to decline on. */
    private void attachSeatListener(CompanionWorld w) {
        if (w.id != null && w.id.equals(seatListenerWorldId)) return;
        TownPlaces.setSeatTransitionListener(w.id, this::onSeatTransition);
        seatListenerWorldId = w.id;
    }

    /** The complete half of docs/06-society.md's "座位事件拆成两股": one row per seat change, whole,
     * with no cooldown and no eviction - {@code kind: "seat_state"}, deliberately not {@code "event"},
     * so it never competes with the narrative stream for a place in timeline.md/highlights.md or the
     * norm quiz (see {@code TimelineExporter}'s handling of that kind) and is only ever read back by
     * {@code NormDetector}, which needs an exact "from" for every landing to replay occupancy without
     * the gaps the narrative stream's throttle leaves behind. */
    private void onSeatTransition(TownPlaces.SeatTransitionNote note) {
        boolean landed = note.newPositionId() != null;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", landed ? "took_spot" : "left_spot");
        extra.put("positionId", note.newPositionId());
        extra.put("from", note.previousPositionId());
        if (note.noticeReason() != null) extra.put("noticeReason", note.noticeReason());
        String text = nameOf(note.residentId()) + "：" + (note.previousPositionId() == null ? "（无处）" : note.previousPositionId())
            + " → " + (note.newPositionId() == null ? "（等待）" : note.newPositionId());
        add(note.at(), "seat_state", note.residentId(), nameOf(note.residentId()), text, extra);

        if (note.noticeReason() != null) {
            String key = landed
                ? seatNoticeKey(note.residentId(), "took_spot", note.newPositionId(), note.at())
                : seatNoticeKey(note.residentId(), "left_spot", note.previousPositionId(), note.at());
            pendingSeatNotices.put(key, note.noticeReason());
        }
    }

    private void captureMemories(CompanionWorld w) {
        for (Memory m : w.memories) {
            if (seenMemories.add(m.id())) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("sourceType", m.sourceType());
                extra.put("sourceId", m.sourceId());
                if (m.topicId() != null) extra.put("topicId", m.topicId());
                add(m.at(), "memory", m.ownerId(), nameOf(m.ownerId()), m.text(), extra);
            }
        }
    }

    private void captureDialogue(CompanionWorld w) {
        for (Conversation c : w.conversations) {
            int already = seenTurnCount.getOrDefault(c.id, 0);
            List<Turn> turns = c.turns;
            for (int i = already; i < turns.size(); i++) {
                Turn t = turns.get(i);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("conversationId", c.id);
                extra.put("place", c.place);
                extra.put("source", t.source());
                if (t.emoji() != null) extra.put("emoji", t.emoji());
                add(t.at(), "dialogue", t.speakerId(), nameOf(t.speakerId()), t.text(), extra);
            }
            seenTurnCount.put(c.id, turns.size());
        }
    }

    private void captureRelationshipChanges(CompanionWorld w) {
        for (ResidentState r : w.residentStates) {
            if (r.id.equals("self")) continue; // avatar's own "relationships" map is driven by the user, not a resident's private affection
            Map<String, Integer> previous = lastRelationships.get(r.id);
            if (previous != null) {
                for (var e : r.relationships.entrySet()) {
                    Integer before = previous.get(e.getKey());
                    if (before != null && !before.equals(e.getValue())) {
                        Map<String, Object> extra = new LinkedHashMap<>();
                        extra.put("towards", e.getKey());
                        extra.put("before", before);
                        extra.put("after", e.getValue());
                        add(w.updatedAt, "relationship", r.id, nameOf(r.id),
                            nameOf(r.id) + "对" + nameOf(e.getKey()) + "的好感从 " + before + " 变为 " + e.getValue(),
                            extra);
                    }
                }
            }
            lastRelationships.put(r.id, new HashMap<>(r.relationships));
        }
    }

    private void captureServiceRequests(CompanionWorld w) {
        for (ServiceRequest r : w.serviceRequests) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id);
            row.put("requesterId", r.requesterId);
            row.put("requesterName", nameOf(r.requesterId));
            row.put("kind", r.kind);
            row.put("place", r.place);
            row.put("status", r.status);
            row.put("requestedAt", r.requestedAt == null ? null : r.requestedAt.toString());
            row.put("preparingAt", r.preparingAt == null ? null : r.preparingAt.toString());
            row.put("deliveredAt", r.deliveredAt == null ? null : r.deliveredAt.toString());
            row.put("resolvedAt", r.resolvedAt == null ? null : r.resolvedAt.toString());
            serviceRequestsById.put(r.id, row); // overwrite: keep only the latest status for this id
        }
    }

    private void captureCoLocation(CompanionWorld w) {
        Map<String, Integer> byPlace = new HashMap<>();
        for (Actor a : w.residents) {
            if (a.activity().equals("walk")) continue; // mid-travel, not "at" a place
            byPlace.merge(a.place(), 1, Integer::sum);
        }
        for (int count : byPlace.values()) coLocationHistogram.merge(count, 1L, Long::sum);
    }

    /** Call roughly once per simulated day: a snapshot of every resident's own writable personality
     * dial, so a slow drift (or the lack of one) is visible across the run without wading through
     * every tick's raw numbers. */
    public void capturePersonalitySnapshot(CompanionWorld w) {
        for (ResidentState r : w.residentStates) {
            if (r.id.equals("self")) continue;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("extroversion", r.extroversion);
            extra.put("conscientiousness", r.conscientiousness);
            extra.put("sensitivity", r.sensitivity);
            extra.put("volatility", r.volatility);
            add(w.updatedAt, "personality_snapshot", r.id, nameOf(r.id), nameOf(r.id) + "的性格快照", extra);
        }
    }

    private void add(Instant at, String kind, String actorId, String actorName, String text, Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", at.toString());
        row.put("kind", kind);
        row.put("actorId", actorId);
        row.put("actorName", actorName);
        row.put("text", text);
        if (!extra.isEmpty()) row.put("extra", extra);
        entries.add(row);
    }

    private String nameOf(String id) {
        if (id.equals("self")) return avatarName;
        return residentNames.getOrDefault(id, id);
    }
}
