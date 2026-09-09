package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;

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

    public void capture(CompanionWorld w) {
        if (w == null) return;
        avatarName = w.name;
        for (Actor actor : w.residents) residentNames.put(actor.id(), actor.name());
        captureDiary(w);
        captureEvents(w);
        captureMemories(w);
        captureDialogue(w);
        captureRelationshipChanges(w);
        captureServiceRequests(w);
        captureCoLocation(w);
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
                add(e.at(), "event", String.join(",", e.actorIds()), String.join("、", names), e.text(), extra);
            }
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
