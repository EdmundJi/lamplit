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
    /** Per-pair cooldown for a rule-detected face-to-face encounter (see ResidentSimulation's
     * "maybeEncounter"): the last instant this pair was pulled into a conversation this way, keyed by
     * the same sorted "a:b" pair key already used for invitation cooldowns. Purely simulation-internal
     * bookkeeping - a timestamp, never sent to any model - self-healing on an old save via the empty
     * map default, exactly like {@link #serviceRequests} above. */
    public Map<String,Instant> encounterCooldowns = new LinkedHashMap<>();
    /** Face-to-face facts waiting for the resident to decide what, if anything, to do about them.
     * The rules put two people in front of each other and stop there (docs/04's 相遇是外部事实); this
     * queue is that fact, not a decision. Generative Agents asks the same question of every
     * observation - "should X react to this, and if so how" - and lets the model answer; the earlier
     * version of this file skipped the question and started the conversation itself, which is the one
     * place this town was more forceful than the paper it is modelled on. Bounded, and cleared
     * whenever the two are no longer standing together. */
    public List<PendingEncounter> pendingEncounters = new ArrayList<>();
    /** One resident noticing one other person, at one moment. {@code residentId} is whose decision it
     * is; {@code otherId} is who they are looking at. */
    public static class PendingEncounter {
        public String id, residentId, otherId, place;
        public Instant at;
        public long residentRevision;
    }
    /** Bounded diagnostic history of why a resident decision was actually dispatched to the model -
     * plan ended, an interruption, a rule-detected encounter, a perceivable environment change, a body
     * signal, a time anchor, or low-frequency, unexplained drift. Never read by any model; it exists so
     * a later change (see MetricsExporter, not owned by this batch) can answer "is 90% of the town's
     * thinking just a timer running out?" instead of guessing. */
    public List<DecisionTrigger> decisionTriggers = new ArrayList<>();
    public record DecisionTrigger(String id,String residentId,String trigger,Instant at) {}
    /** Bounded diagnostic trail for every time a real, already-recorded experience nudged a
     * resident's own personality a small, bounded step (see ResidentSimulation's "driftPersonality"
     * family, item 2). {@code cause} is one of a fixed, rule-authored vocabulary ("agreement",
     * "declined", "project_complete", "interrupted", "solitude", "celebration", "noticed_detail",
     * "missed_detail") naming which real event moved the dimension - never a model-authored
     * explanation, and never read by any model. This is what lets a drift always be traced back to
     * the one thing that caused it without the rules ever writing a resident's own account of why
     * they changed - that account, if any, stays the resident's own, through the ordinary
     * reflection/belief machinery. */
    public List<PersonalityDrift> personalityDrifts = new ArrayList<>();
    public record PersonalityDrift(String id,String residentId,String dimension,double delta,String cause,Instant at) {}
    /** Off by default, and deliberately not wired into the ordinary join()/advance() path this batch:
     * turning it on lets the avatar ("self") become a genuine decision candidate in ResidentDirector,
     * on the same terms as the four NPCs, whenever it is not currently under the user's own explicit
     * control (see ResidentSimulation.selfIsFree). See ResidentDirector's report/javadoc for exactly
     * why this stays an explicit opt-in rather than an always-on change for this batch. */
    public boolean avatarAutonomyEnabled;
    /** The resident currently responsible for deciding whether the cafe opens.  It starts with the
     * original owner, but is deliberately world state rather than an id baked into the rules: a
     * signed handover can change it and an old save simply heals back to "owner". */
    public String cafeOperatorId = "owner";
    /** An operator may decide they are done before anyone takes over.  A closed counter is a valid
     * social outcome, not an error that silently revives the old proprietor. */
    public boolean cafeOperating = true;
    /** A resident-configurable daily custom expressed in local minutes.  These are schedule cues for
     * the operator's model, never a timer that silently opens or closes the shop on their behalf. */
    public int cafeOpenMinute = 9 * 60;
    public int cafeCloseMinute = 21 * 60;
    /** Physical state, changed only by an operator action (or inferred once while repairing an old
     * save). `closing` means an actual closing announcement happened and no new orders are accepted. */
    public String cafeStatus;
    public Instant cafeStatusChangedAt;
    /** Small, human-scale agreements around the counter.  They are not contracts or an economy:
     * their only job is to distinguish an offer from both people actually agreeing to a responsibility. */
    public List<WorkArrangement> workArrangements = new ArrayList<>();
    /** One resident's ask for a drink from the owner and everything that happened to it. `status`
     * moves waiting -> preparing -> delivered -> consumed, or off the happy path to abandoned (gave up
     * or left before being served) or cold (delivered but never picked up) - both broken-link outcomes
     * are written only into memory, never announced as a WorldEvent. A drink only ever exists because
     * the requester chose request_drink; the rules never invent one on the operator's behalf. */
    public static class ServiceRequest {
        public String id, requesterId, kind, place, status;
        public Instant requestedAt, preparingAt, deliveredAt, resolvedAt;
    }
    public static class ResidentState {
        public String id, mood, goal, thought, desiredAction, positionId;
        /** Things this resident has already done that no model chose and nobody has yet accounted
         * for. The architecture behind it: rules are the reflex, the model is the explanation, and
         * people mostly act first and explain afterwards - Gazzaniga's interpreter, Libet's readiness
         * potential, Nisbett &amp; Wilson's "Telling More Than We Can Know". A habit fires from the
         * rules layer, lands here, and is later accounted for in the resident's own words (see
         * {@link ResidentSimulation#applyExplanation}).
         * <p>Two things this is deliberately NOT. It is not a log: an explained deed leaves the queue
         * and survives only as whatever memory the resident wrote about it, which is exactly how
         * autobiographical memory works - what is kept is the account, not the event. And the
         * explanation is not decoration: it becomes a memory, memories become beliefs, and beliefs
         * are allowed to change what the reflex does next time. Without that last step the model is
         * an expensive narrator. */
        public List<Deed> unexplainedDeeds = new ArrayList<>();
        /** Reserved for an explicitly remembered livelihood concern.  There is deliberately no
         * clock-driven work quota or automatic "unemployed" penalty behind this value. */
        public double livelihoodPressure;
        /** Their current self-description of how they keep a place in the town.  It is allowed to
         * change; no map building or income ledger is implied by the text. */
        public String occupation;
        /** The resident's coarse, self-formed through-line. It deliberately outlives the short
         * {@link #plan}: walking to the cafe, stopping to make a drink, and returning to the work
         * all belong to one intention rather than three unrelated choices. Old saves leave this null
         * and ResidentSimulation fills it from the existing goal on their next advance. */
        public LifeIntent lifeIntent;
        /** Long-running livelihood direction.  Unlike lifeIntent it is never overwritten merely
         * because a neighbour's project becomes the next short action. */
        public LifeIntent careerIntent;
        /** A real action that was set aside by a world reaction. The action itself is kept here,
         * including its remaining duration, rather than regenerated after the interruption. */
        public SuspendedAction suspendedAction;
        /** Destination-action metadata while the current plan is travel. The action plan remains the
         * only physical authority; these fields simply preserve what should begin on arrival. */
        public int desiredDurationSeconds;
        public double energy, social, curiosity;
        /** Last instant included in elapsed-time energy integration. Old saves leave it null and the
         * next simulation step starts the clock without inventing a missing night's physiology. */
        public Instant energyUpdatedAt;
        /** Hand-authored daily custom. It becomes a model-visible time cue, never an automatic sleep
         * command; the seeded flag allows midnight (0) to remain a valid configured minute. */
        public boolean sleepScheduleSeeded;
        public int usualSleepMinute, usualWakeMinute;
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
        /** Last simulated instant each of this resident's own four personality dimensions actually
         * drifted (see ResidentSimulation's "driftPersonality"), keyed by dimension name. The
         * frequency backstop behind item 2's "slow, not mood": the same dimension cannot move again
         * before its own cooldown has passed, however many qualifying events happen in between. Old
         * saves self-heal via the empty map default, same shape as {@code lastHabitAt} above. */
        public Map<String,Instant> lastPersonalityDriftAt = new LinkedHashMap<>();
        /** Per-resident decision cooldown (see ResidentDirector), replacing the old world-global
         * {@link #modelRequestedAt} gate: each resident now thinks on their own clock instead of the
         * whole town taking turns round-robin on one shared timer. Null until this resident's first
         * decision is ever dispatched. */
        public Instant lastDecisionRequestedAt;
        /** The world's own day-part (morning/afternoon/evening/night, see CompanionRules.environment)
         * as of this resident's last applied decision - a broad, general "time anchor" (item 5),
         * distinct from the personal sleep-window routine cue. Compared, never sent to any model. */
        public String periodAtLastDecision;
        /** Self-only bookkeeping (see ResidentSimulation.selfIsFree/step()): the place/activity/until
         * signature ResidentSimulation itself last wrote for the avatar. If the live Actor no longer
         * matches it, something outside ResidentSimulation (an explicit user intent, handled entirely
         * in CompanionRules) has taken the avatar over since, and any plan recorded here is stale and
         * must be silently abandoned rather than completed against a reality it no longer describes.
         * Unused for the four NPCs, whose Actor only ResidentSimulation ever writes. */
        public String selfActivitySignature;
        /** This resident's own coarse plan for today (see ResidentSimulation.applyDayPlan): three or
         * four qualitative segments, not a schedule. Left null until their first morning decision. */
        public DayPlan dayPlan;
        /** Last simulated instant each of this resident's own habitual reflexes (see
         * ResidentSimulation's "maybeHabit" family) actually fired, keyed by that habit's own short
         * id ("tidy", "quiet", ...). Purely a frequency backstop - never read by any model - so the
         * same habit cannot fire again before its own cooldown has passed. Old saves deserialize with
         * the empty map default, the same self-healing shape as {@code relationships} above. */
        public Map<String,Instant> lastHabitAt = new LinkedHashMap<>();
    }
    /** A resident's own coarse, interruptible day plan - see {@link ResidentState#dayPlan}. A segment
     * is deliberately just a short label and a status: nothing here forces it to happen, and nothing
     * marks it "active" or "done" on the resident's behalf - the model is free to defer or abandon
     * it, and a segment still "pending" when the day rolls over becomes exactly one reflection memory
     * ("today I did not get to X"), never a silent success. */
    public static class DayPlan {
        public String id, day;
        public Instant formedAt;
        public List<DaySegment> segments = new ArrayList<>();
    }
    public static class DaySegment {
        public String label;
        public String status = "pending";
    }
    /** A sparse resident-owned purpose, separate from the timer-backed action currently underway. */
    public static class LifeIntent {
        public String id, goalId, purpose, status;
        public Instant formedAt, updatedAt, lastActedAt;
    }
    /** A paused action keeps both its remaining timer and travel's deferred destination action. */
    public static class SuspendedAction {
        public Plan plan;
        public String desiredAction;
        public int desiredDurationSeconds;
        public Instant pausedAt;
    }
    public static class WorkArrangement {
        public String id, kind, place, proposerId, workerId, status, note;
        public Instant proposedAt, acceptedAt, endedAt;
        public List<String> proposerEvidenceIds = new ArrayList<>();
        public List<String> workerEvidenceIds = new ArrayList<>();
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
    /** One thing a resident did without deciding to. {@code note} is the rules' plain account of what
     * happened - never a motive, because the rules do not know one and must not invent one. The
     * motive is the resident's own, and arrives later. */
    public static class Deed {
        public String id, action, place, note;
        public Instant at;
    }
    public record Focus(String taskId, Instant startedAt, Instant endsAt) {}
    public record Entry(String id, Instant at, String text) {}
    /** A resident's own remembered thing. {@code sourceType} is one of three layers, from most
     * disposable to most durable - see {@link CompanionRecall#tier}:
     * <ul>
     *   <li>raw - {@code seed}/{@code observed}/{@code heard}: something that happened, in this
     *   resident's own words. Cheapest to write, first to be evicted once memory fills up.</li>
     *   <li>{@code reflection} - a one-off synthesis this resident made over some raw memories.
     *   Never invented by a rule; only ever written through {@link ResidentSimulation#applyReflection}
     *   once a real reflection (a model call, outside this module) has happened.</li>
     *   <li>{@code belief} - a standing generalization this resident holds about someone or
     *   something ("x likes the window seat"), also only ever written through
     *   {@code applyReflection}. Most durable, and the layer {@link CompanionRecall} weighs most
     *   heavily - this is deliberately the opposite of a rule counting occurrences and declaring a
     *   pattern; the model decides a belief is warranted, the rules only store and rank it.</li>
     * </ul>
     * {@code supersedesKey} is null for a memory that does not stand in for an earlier conclusion.
     * When non-null, landing a new {@code reflection}/{@code belief} with the same owner and key
     * flips every earlier memory sharing that key to {@code superseded=true} (see
     * {@link ResidentSimulation#applyReflection}) - the old memory is never deleted, only excluded
     * from {@link CompanionRecall#retrieve}, so the town can always answer "what did they used to
     * think" even after they have changed their mind. */
    public record Memory(String id, String ownerId, String sourceId, String sourceType, Instant at, String text, String topicId, List<String> evidenceIds, int importance, String supersedesKey, boolean superseded) {
        /** Shape used everywhere before supersession existed: a fresh, non-superseded memory that
         * does not stand in for (or get superseded by) anything else. */
        public Memory(String id,String ownerId,String sourceId,String sourceType,Instant at,String text,String topicId,List<String> evidenceIds,int importance){
            this(id,ownerId,sourceId,sourceType,at,text,topicId,evidenceIds,importance,null,false);
        }
        public Memory(String id,String ownerId,String sourceId,String sourceType,Instant at,String text,String topicId){
            this(id,ownerId,sourceId,sourceType,at,text,topicId,List.of(),sourceType.equals("seed")?6:5);
        }
    }
}
