package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Serializable state; domain rules receive time explicitly and have no I/O. */
public class CompanionWorld {
    public String id, name, timezone, weather, period, offlineSummary;
    public Instant joinedAt, updatedAt;
    public long revision, lastEncounterSlot;
    public Actor avatar;
    public List<Actor> residents = new ArrayList<>();
    public List<Intent> intents = new ArrayList<>();
    public List<Entry> diary = new ArrayList<>();
    public List<Memory> memories = new ArrayList<>();
    public Focus focus;
    public int simulationVersion;
    public boolean modelConversationsEnabled;
    public long intentRevision, eventSequence, modelSequence;
    public Instant simulatedAt, modelRequestedAt;
    public String modelBudgetDay;
    public int modelCallsToday, modelFailuresToday, modelConsecutiveFailures;
    public Instant modelRetryAfter;
    public String modelStatus = "规则生活正在继续";
    public List<ResidentState> residentStates = new ArrayList<>();
    public List<Project> projects = new ArrayList<>();
    public List<Conversation> conversations = new ArrayList<>();
    public List<WorldEvent> events = new ArrayList<>();
    public List<WorldObject> objects = new ArrayList<>();
    /** The two-layer place model: locations (the street, the cafe, the garden, each resident's own
     * home) contain positions (a seat, a bed, a table) that have an optional owner, a capacity and
     * current occupants. Structure and ownership only - no pixel coordinates; the frontend maps ids
     * to art on its own. See TownPlaces for the rules that read and write this state. */
    public List<Location> locations = new ArrayList<>();
    public List<Position> positions = new ArrayList<>();
    /** The coffee/water service chain (see {@link CafeService}): every request a resident has ever
     * made of the owner, in real time, allowed to break at any link. Self-healing on an old save via
     * the empty-list default - nobody had made a request yet. */
    public List<ServiceRequest> serviceRequests = new ArrayList<>();
    /** One resident's ask for a drink from the owner and everything that happened to it. `status`
     * moves waiting -> preparing -> delivered -> consumed, or off the happy path to abandoned (gave up
     * or left before being served) or cold (delivered but never picked up) - both broken-link outcomes
     * are written only into memory, never announced as a WorldEvent. `proactive` marks a pour the
     * owner offered before being asked, from a learned expectation that can itself be wrong. */
    public static class ServiceRequest {
        public String id, requesterId, kind, place, status;
        public boolean proactive;
        public Instant requestedAt, preparingAt, deliveredAt, resolvedAt;
    }
    public static class ResidentState {
        public String id, mood, goal, thought, desiredAction, positionId;
        public double energy, social, curiosity;
        /** This resident's own personality: writable, per-instance state, exactly like energy/social/
         * curiosity above - not a lookup by id. {@code personalitySeeded} is an explicit flag, not a
         * sentinel value, precisely so a legitimately low score (e.g. the artist's low
         * conscientiousness) is never mistaken for "not yet initialized" and overwritten. A future
         * batch is expected to nudge these four fields slowly and boundedly out of reflect(); this
         * batch only gives them a place to live. See {@link Personality#of} for the self-heal that
         * fills them in once, from the initial-value table, the first time this flag is false - the
         * same pattern {@code TownPlaces.seed()}/{@code reconcileLegacyPlaces()} use elsewhere. */
        public double extroversion, conscientiousness, sensitivity, volatility;
        public boolean personalitySeeded;
        public long revision;
        public Instant lastSocialAt, lastReflectionAt;
        public Plan plan;
        public Map<String,Integer> relationships = new LinkedHashMap<>();
        /** Whether THIS resident has ever let their own private fondness for another show in
         * something they actually said out loud - a private, one-way flag. Never assumed to be known
         * by the other person, and never sent to the model as part of anyone else's nearby/perception
         * context (see ResidentDirector.perspective(), which only ever exposes a resident's own
         * relationships/affectionExpressed to that same resident). An old save without this field
         * deserializes with the empty map below, same self-healing shape as `relationships`. */
        public Map<String,Boolean> affectionExpressed = new LinkedHashMap<>();
        public Map<String,ProjectKnowledge> knownProjects = new LinkedHashMap<>();
        /** The owner's own accumulating sense of responsibility for the counter, read and written by
         * {@link CafeService}. Zero is a genuinely correct starting value (nobody has been kept
         * waiting yet), not a sentinel - unlike personality, this field needs no seeded flag. Present
         * on every resident for simplicity, but only ever written for "owner". */
        public double dutyPressure;
        /** Bounded evidence for {@link CafeService#reflectOnDuty}: how many times since the last duty
         * reflection someone waited too long and said so, and how many times duty pre-empted the
         * owner's own unfinished project - the two opposite pieces of evidence that let
         * conscientiousness drift up or down. Reset to zero each time reflectOnDuty consumes them. */
        public int complaintsSinceDutyReflection, interruptionsSinceDutyReflection;
        public Instant lastDutyReflectionAt;
        public List<String> dutyComplaintEvidenceIds = new ArrayList<>();
        public List<String> dutyInterruptionEvidenceIds = new ArrayList<>();
        /** The owner's own learned-expectation bookkeeping (see {@link CafeService}): how many times in
         * a row a customer has come back for another drink soon after the last one, whether that has
         * crossed the (deliberately low, two-coincidences) threshold into a standing expectation the
         * owner now acts on unprompted, and when each customer was last actually served or proactively
         * poured for - all self-healing on an old save via the empty-map default, same as
         * {@code relationships} above. */
        public Map<String,Integer> repeatVisitStreak = new LinkedHashMap<>();
        public Map<String,Boolean> anticipatesRefill = new LinkedHashMap<>();
        public Map<String,Instant> lastServedAt = new LinkedHashMap<>();
        public Map<String,Instant> lastProactiveAt = new LinkedHashMap<>();
    }
    /** A place a resident can be: the three shared places, or one resident's own home. `ownerId` is
     * null for a shared place. */
    public record Location(String id, String kind, String ownerId) {}
    /** A specific spot inside a place - a bed, a window seat, a shared table. `ownerId` null means
     * anyone can sit; capacity limits how many occupants fit at once. */
    public static class Position {
        public String id, place, kind, ownerId;
        public int capacity;
        public List<String> occupantIds = new ArrayList<>();
    }
    public record ProjectKnowledge(String id,String place,String status,int progress,Instant at,String sourceId) {}
    public record Plan(String id,String action,String place,String targetId,String reason,Instant startedAt,Instant endsAt) {}
    public static class Project {
        public String id,title,kind,place,ownerId,status,objectKind,description;
        public int progress,needed;
        public List<String> contributors = new ArrayList<>();
        public List<String> members = new ArrayList<>();
        public Map<String,Instant> invitationHistory = new LinkedHashMap<>();
        public Instant completedAt;
    }
    public static class Conversation {
        public String id,place,topicId,status;
        public String mode="rules",nextSpeakerId,pendingOperationId,pendingSpeakerId,endReason;
        public long turnVersion,pendingIntentRevision;
        public Instant operationStartedAt,endedAt;
        public String summaryOperationId,summarySpeakerId;
        public Instant summaryStartedAt;
        public List<String> summarizedParticipants=new ArrayList<>();
        public Map<String,List<String>> turnMemoryIds=new LinkedHashMap<>();
        public Map<String,String> feelings=new LinkedHashMap<>();
        public Map<String,String> recollectionSources=new LinkedHashMap<>();
        public List<String> participantIds = new ArrayList<>();
        public Instant startedAt,updatedAt;
        public List<Turn> turns = new ArrayList<>();
        public boolean accepted;
        public int stage = 1;
    }
    public record Turn(String speakerId,String text,Instant at,String source,String emoji) {
        public Turn(String speakerId,String text,Instant at){this(speakerId,text,at,"rules",null);}
        public Turn(String speakerId,String text,Instant at,String source){this(speakerId,text,at,source,null);}
    }
    public record WorldEvent(String id,Instant at,String type,String place,List<String> actorIds,String text,String projectId) {}
    public record WorldObject(String id,String kind,String place,String label,String state,String projectId) {}
    public record Actor(String id, String name, String role, String place, String activity, String label,
                        double x, double y, Instant until) {}
    public static class Intent {
        public String id, kind, priority, status, feedback, taskId, text, resolvedKind;
        public int durationMinutes;
        public Instant createdAt;
        public Intent() {}
        public Intent(String id, String kind, String priority, String taskId, int durationMinutes, Instant now) {
            this.id=id; this.kind=kind; this.priority=priority; this.taskId=taskId;
            this.durationMinutes=durationMinutes; this.createdAt=now; this.status="pending";
            this.feedback="记住了，等手上的安排告一段落。";
        }
    }
    public record Focus(String taskId, Instant startedAt, Instant endsAt) {}
    public record Entry(String id, Instant at, String text) {}
    public record Memory(String id, String ownerId, String sourceId, String sourceType, Instant at, String text, String topicId, List<String> evidenceIds, int importance) {
        public Memory(String id,String ownerId,String sourceId,String sourceType,Instant at,String text,String topicId){
            this(id,ownerId,sourceId,sourceType,at,text,topicId,List.of(),sourceType.equals("seed")?6:5);
        }
    }
}
