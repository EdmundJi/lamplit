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
    /** Rule timestamps for free-text object states that also evolve with elapsed world time. Kept
     * beside immutable WorldObject records so their display text stays simple and old saves heal. */
    public Map<String,Instant> objectStateChangedAt = new LinkedHashMap<>();
    /** The town's first stateful physical object with real consequences (see docs/05-notes.md "物件
     * 要有自己的类"): a door, currently only at the cafe. A flat, concrete class rather than a
     * polymorphic Doorable/Lockable hierarchy on purpose - {@code CompanionWorld} is serialized whole
     * as one JSON document, and a class hierarchy that needs Jackson's polymorphic type handling
     * (@JsonTypeInfo and friends) is a trap laid directly in the save format: rename a subtype, drop
     * one, or reorder a type id and every existing save silently fails to round-trip. The next
     * stateful object (the coffee machine) gets its own list beside this one, not a shared supertype -
     * less "correct" object orientation, never a serialization landmine. Self-healing on an old save
     * via the empty-list default, same shape as {@link #serviceRequests}. */
    public List<Door> doors = new ArrayList<>();
    /** {@code lockedBy} is the entire point of this class - see docs/05-notes.md: {@code cafeStatus}
     * already blocks entry while the shop is closed, but it carries no "who", and a barrier nobody can
     * be blamed for is only weather. Whether it is locked and who locked it are the only two facts
     * this class knows; everything else (whether locking was reasonable, who is upset about it, what
     * anyone should do about being shut out) is a resident's own reaction, never written here - see
     * {@code DoorService}/{@code ResidentSimulation.schedule}. */
    public static class Door {
        public String id, place;
        public boolean locked;
        /** Never sent to a resident who was not standing right there when the door was locked - see
         * {@code DoorService.lock}'s witness pass and {@code ResidentSimulation.schedule}'s arrival
         * check, which perceives only the fact "locked", never this field. */
        public String lockedBy;
        public Instant lockedAt;
    }
    /** The place model: locations (buildings - the street, the cafe, the garden, each resident's own
     * home) contain positions (a seat, a bed, a table) that have an optional owner, a capacity and
     * current occupants. Structure and ownership only - no pixel coordinates; the frontend maps ids
     * to art on its own. See TownPlaces for the rules that read and write this state. */
    public List<Location> locations = new ArrayList<>();
    public List<Position> positions = new ArrayList<>();
    /** The "房间" layer - see {@link Room}'s own doc comment for why it is a new list rather than a
     * change to either list above. Self-healing on an old save via the empty-list default. */
    public List<Room> rooms = new ArrayList<>();
    /** The coffee/water service chain (see {@link CafeService}): every request a resident has ever
     * made of the owner, in real time, allowed to break at any link. Self-healing on an old save via
     * the empty-list default - nobody had made a request yet. */
    public List<ServiceRequest> serviceRequests = new ArrayList<>();
    /** Every lend or gift ever made of an owned {@link WorldObject} (docs/01-requirements.md 第二版
     * 「世界」「有所有权，可借可赠，不引入货币」): what, from whom, to whom, when, and whether it has
     * come back - see {@link Loan}'s own doc comment for why that list and no more. Self-healing on
     * an old save via the empty-list default - nobody had lent anything yet. */
    public List<Loan> loans = new ArrayList<>();
    /** Per-pair cooldown for a rule-detected face-to-face encounter (see ResidentSimulation's
     * "maybeEncounter"): the last instant this pair was pulled into a conversation this way, keyed by
     * the same sorted "a:b" pair key already used for invitation cooldowns. Purely simulation-internal
     * bookkeeping - a timestamp, never sent to any model - self-healing on an old save via the empty
     * map default, exactly like {@link #serviceRequests} above. */
    /** The local date on which the operator actually decided to shut for the day (close_cafe /
     * closeForDay), so scheduled opening can tell that apart from every other reason the shop happens
     * to be closed - not opening time yet, the world having only just been created, a handover. Using
     * "the status changed today" instead was wrong in exactly the way that matters: a world is born
     * with its shop closed, so day one read as "already closed for today" and the town never got a
     * cafe on the day it was made. Self-healing on an old save via the null default. */
    public String cafeClosedForDayOn;
    public Map<String,Instant> encounterCooldowns = new LinkedHashMap<>();
    /** A future spoken out loud (see ResidentSimulation.promise/settlePromises): the one thing every
     * other mechanism here was missing, since committing together and delegating to the counter both
     * happen in the moment and leave nothing later to compare reality against. The rules only ever
     * record two facts about a promise - that it was made, and later whether the person who made it
     * was where they said they would be - and deliberately go no further than that: whether missing
     * it counts as letting someone down is for whoever remembers it to decide, in their own memory,
     * and different people are allowed to decide that differently. That is why nothing on this class
     * is named "broken"/"betrayed"/"trust" - those are readings of the fact, not the fact itself. */
    public List<Promise> promises = new ArrayList<>();
    /** See {@link #promises} above. */
    public static class Promise {
        public String id;
        /** Who spoke the promise - the person {@link #place}/{@link #dueAt} will later be checked
         * against, never anyone else. */
        public String byId;
        /** Who it was promised to. Not the only one who may remember it being made - see
         * {@link #witnessIds} - but the one the promise was actually addressed to. */
        public String toId;
        /** What was promised, in the promiser's own single spoken line. Bounded short on purpose: a
         * promise is one sentence someone said out loud, not a plan a model could pad with anything
         * else it wanted remembered. */
        public String what;
        /** Where {@link #byId} is expected to be found at {@link #dueAt}. Must already be a place the
         * town recognises (see TownPlaces) - "did they come" only means something against a real
         * location, never an arbitrary string a model invented. */
        public String place;
        /** When the promise falls due. Always after {@link #madeAt} and never far enough out that
         * nobody could reasonably be expected to still remember making it - see
         * ResidentSimulation.promise for the actual bound. */
        public Instant dueAt;
        /** Who else was standing right there, face to face, when the promise was made. They are not
         * party to it - only {@link #byId} owes anything to {@link #toId} - but they heard it happen
         * and get to carry their own memory of that, independent of what either principal later
         * remembers or claims. */
        public List<String> witnessIds = new ArrayList<>();
        public Instant madeAt;
        /** Null until {@link #dueAt} has actually been checked against where {@link #byId} was - see
         * ResidentSimulation.settlePromises. Non-null means this promise has already been resolved
         * once and must never be resolved again, however many more simulation steps pass over it. */
        public Instant settledAt;
        /** Exactly one of two words - "came" or "did_not_come" - and nothing else. Both are plain
         * facts about where {@link #byId} was at the moment of settlement; neither is a verdict, which
         * is precisely why a value like "broken" or "kept" must never appear here (see this class's
         * own doc comment above). */
        public String outcome;
        /** Who has already been asked "what do you make of this" about this settled promise (see
         * ResidentSimulation.promisesAwaitingThought/markPromiseThoughtAsked) - the toId, byId, or a
         * witness. Only ever records THAT someone was asked, never their answer: the answer is that
         * person's own, and lands in their own memory through the ordinary reflection machinery, not
         * here. Exists purely so the same person is not asked again every subsequent tick. */
        public List<String> thoughtAskedIds = new ArrayList<>();
    }
    /** "上次看到他时的样子" - for a pair where one of them has already decided NOT to approach the
     * other, what the scene looked like at the moment of that decision, keyed by the same sorted
     * "a:b" pair key. While the scene still looks like this, the rules do not put the question again:
     * having decided to leave someone alone, you do not reconsider every N minutes, you reconsider
     * when something about them changes. Dropped the moment the two are no longer standing in the
     * same place, so walking out and coming back is a fresh sight rather than the same one.
     * Simulation-internal, never sent to any model, self-healing on an old save via the empty map
     * default. See ResidentSimulation's "encounterFingerprint". */
    public Map<String,String> declinedEncounters = new LinkedHashMap<>();
    /** Face-to-face facts waiting for the resident to decide what, if anything, to do about them.
     * The rules put two people in front of each other and stop there (docs/04's 相遇是外部事实); this
     * queue is that fact, not a decision. Generative Agents asks the same question of every
     * observation - "should X react to this, and if so how" - and lets the model answer; the earlier
     * version of this file skipped the question and started the conversation itself, which is the one
     * place this town was more forceful than the paper it is modelled on. Bounded, and cleared
     * whenever the two are no longer standing together. */
    /** docs/01-requirements.md 第二版「世界」「进别人家由所有权和门决定……被邀请或门没锁就进得去」 -
     * a bounded, single-use pass into one specific home for one specific guest. Structural fact only,
     * same red line as {@link Loan}: who, whose home, until when - never a reason. The invitation
     * itself carries no judgement about whether the visit is a good idea; that stays the guest's own
     * decision (see {@code ResidentSimulation}'s {@code visit_home}), and using it up or letting it
     * lapse are equally ordinary outcomes. See {@code DoorService} for how this and the door's own
     * locked bit combine ("OR", never "AND" - either is enough to get in). */
    public List<HomeInvitation> homeInvitations = new ArrayList<>();
    public static class HomeInvitation {
        public String id, homeId, hostId, guestId;
        public Instant at, expiresAt;
    }
    public List<PendingEncounter> pendingEncounters = new ArrayList<>();
    /** One resident noticing one other person, at one moment. {@code residentId} is whose decision it
     * is; {@code otherId} is who they are looking at. */
    public static class PendingEncounter {
        public String id, residentId, otherId, place;
        public Instant at;
        public long residentRevision;
    }
    /** Moments the rules recognised as the one moment a particular action makes sense in, waiting
     * for the resident to say whether they want to do anything about them - see {@link Occasions},
     * which is also the only thing that ever puts anything here. Same shape and same bound as
     * {@link #pendingEncounters}, for the same reason: a moment that goes unanswered has to pass,
     * not queue up. */
    public List<PendingOccasion> pendingOccasions = new ArrayList<>();
    /** "This one has already been put to them", keyed {@code residentId|occasionKey} and valued by
     * the scene as it stood when it was asked. Written when the question is <b>raised</b>, not when
     * it is answered, and that distinction is the whole mechanism: an occasion nobody got round to
     * answering has to pass like any other moment. A first draft recorded it only on refusal, so an
     * unanswered question was re-raised the instant its TTL ran out - a probe over two simulated days
     * measured 407 asks for one occasion that comes round once a day. That is the menu problem
     * rebuilt inside the thing that was supposed to fix it. */
    public Map<String,String> askedOccasions = new LinkedHashMap<>();
    /** One resident, one moment, one question. {@code fact} is the bystander-view sentence the
     * resident is shown; {@code situation} is bookkeeping and never reaches a model. */
    public static class PendingOccasion {
        public String id, residentId, key, place, target, fact, situation;
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
        /** Current room inside Actor.place. Null only while travelling/away or in a legacy save before
         * the next repair pass. Kept on resident state so Actor's stable wire shape need not change. */
        public String roomId, desiredRoomId;
        /** Where the current travel plan set out from, or null when that is not a named place (a trip
         * re-routed mid-walk, a legacy save). Read only while walking - see ResidentSimulation.sameRoom. */
        public String travelFrom;
        /** Structural knowledge, kept separate from global map existence: a resident may name every
         * building from the street without knowing which rooms or scarce things are inside it. */
        public List<String> knownRoomIds = new ArrayList<>(), knownPositionIds = new ArrayList<>();
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
        /** The usual shape of this resident's working day, planted once from {@link ResidentDuties}
         * (Smallville's seed description, in structured form so a day plan can name real places).
         * What they believe their days tend to look like - never a quota, never enforced by a rule;
         * the morning day plan reads it and may follow, bend or ignore it. Cleared when the resident
         * changes occupation, because the old routine no longer describes the life they chose. */
        public List<Duty> duties = new ArrayList<>();
        public boolean dutiesSeeded;
        public int dutiesVersion;
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
        /** When this resident was last asked whether they want something they cannot do alone. Kept
         * separate from every other cadence because the question is only worth asking when the town
         * has actually run dry - see ResidentSimulation.needsVenture. */
        public Instant lastVentureAt;
        /** When this resident was last asked whether they want to make a promise to whoever they are
         * standing with right now. A pure frequency gate, kept separate from every other cadence for
         * the same reason {@link #lastVentureAt} is: a person who was just asked and said nothing
         * does not become a different person a minute later, and pestering them about it defeats the
         * point of asking at all. Never read by anything but the gate itself - see
         * ResidentSimulation.needsPromiseAsk/markPromiseAsked - and never sent to any model. */
        public Instant lastPromiseAskedAt;
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
        /** The unified "when was this resident last asked anything at all" marker - decision, turn,
         * dayplan, explain, reflect, venture or promise_settled, every one of them, stamped
         * unconditionally in {@code ResidentDirector.reserved()} (the one choke point all of them pass
         * through) rather than in a per-kind branch the way {@link #lastDecisionRequestedAt} is.
         * <p>Exists to answer a question progress.md's "信念为什么是 0" named by pointing at its
         * absence: {@code lastDecisionRequestedAt} only moves for a decision, {@link #lastReflectionAt}
         * only for a landed reflection, {@link #lastSocialAt} only at conversation end - nothing
         * recorded "this person was asked", full stop. {@code ResidentDirector.perspective()} reads the
         * OLD value of this field to bound the "小工作集" (docs/01 「心智」) it hands the model - this
         * resident's own raw experience since that instant, capped by {@code
         * CompanionRecall.WORKING_SET_CAPACITY} - before this field is moved forward to the current
         * instant for the next ask. That makes the window's width a fact about how long it has actually
         * been since somebody had a reason to ask this resident anything (itself decided by rule-level
         * triggers - an encounter, a plan ending, a perceivable change - never a clock), which is the
         * "cut by change, not a timer" docs/04 asks for, in the same way the 30-minute seat cooldown
         * and the 12-minute encounter cooldown both had to learn not to be. Null until this resident's
         * first ever dispatch, which {@link CompanionRecall#workingSet} treats as "no floor" - the
         * correct behaviour for a fresh or pre-existing save that predates this field: the first working
         * set is simply the most recent raw memories on record, exactly as if they had never been asked
         * before, which is true. */
        /** Moved for every dispatch kind EXCEPT a conversation turn and its recollection: a turn reads
         * the live transcript rather than this window, and every turn writes raw memories that have to
         * survive to be seen at the next real decision. See {@code ResidentDirector.reserved()} for why
         * stamping on turns silently erased whole conversations from the prompt that followed them. */
        public Instant lastAskedAt;
        /** How many decisions in a row this resident has had refused, and until when to stop asking.
         * A refused decision leaves them with nothing decided, which is itself the condition for
         * asking again - so a decision that can never apply is an unbounded loop, and one really
         * happened: an operator standing inside his own closing shop chose to sit down 476 times and
         * was refused 408 of them, taking 67% of the whole town's thinking for a day. Both reset on
         * any decision that lands. Self-healing on an old save via the 0/null defaults. */
        public int consecutiveDecisionRejections;
        public Instant decisionRetryAfter;
        /** What this resident has actually spent today doing, in their own bystander-visible terms -
         * one entry per stretch of work that ran to its end, the day it belongs to, and nothing else.
         * Reset when the local date turns over; bounded, oldest dropped first.
         *
         * <p>It exists because a resident could not see their own afternoon. A six-hour run took 243
         * decisions and 78 of them (32.1%) repeated that resident's previous decision word for word -
         * 「刚搬来，先在家里歇会儿，整理一下心情和住处。」 came back verbatim dozens of times. A person
         * who had said that three times running would know they had; ours could not. The reflex layer
         * stops us <i>asking</i> the same question into an unchanged room, and this is the other half:
         * when they are asked, they can see what they have already done with the day.
         *
         * <p>Deliberately the bystander's view - action, place, how long - never a motive, exactly
         * like {@link Deed}. Why they did it is theirs to say, not ours to record. */
        public List<Doing> todaysDoings = new ArrayList<>();
        public String doingsDay;
        /** The reflex layer's bookkeeping (see ResidentSimulation's "extendByReflex"). {@code
         * planFromDecision} says the plan currently running is one this resident themselves chose,
         * not one the rules arranged - only a decision of their own may be quietly carried on.
         * {@code situationAtDecision} is what the room looked like when they chose it, and {@code
         * reflexExtensions} counts how many times it has been carried on since, so nobody can be
         * carried along forever without ever being asked again.
         *
         * <p>Measured reason this exists: 243 decisions in a six-hour run, 78 of them (32.1%) a
         * byte-identical repeat of that same resident's previous decision - the same sentence, in the
         * same unchanged room, ten minutes later. Roughly 440K of that run's 1.37M input tokens
         * bought nothing. Never sent to any model; this is simulation bookkeeping, like every other
         * fingerprint in this file. Self-healing on an old save via the false/null/0 defaults. */
        public boolean planFromDecision;
        public String situationAtDecision;
        public int reflexExtensions;
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
        /** This resident's own plan for today (see ResidentSimulation.applyDayPlan): a few stretches
         * with a rough time and place, formed by their own model soon after waking. */
        public DayPlan dayPlan;
        /** Last simulated instant each of this resident's own habitual reflexes (see
         * ResidentSimulation's "maybeHabit" family) actually fired, keyed by that habit's own short
         * id ("tidy", "quiet", ...). Purely a frequency backstop - never read by any model - so the
         * same habit cannot fire again before its own cooldown has passed. Old saves deserialize with
         * the empty map default, the same self-healing shape as {@code relationships} above. */
        public Map<String,Instant> lastHabitAt = new LinkedHashMap<>();
        /** The last thing this resident actually wrote down about each other resident they shared a
         * room with - place, what that person was doing, which seat - keyed by that person's id. It
         * exists so the same sighting is not recorded twice while nothing about it has changed, and
         * it is <b>cleared for anyone who is no longer here</b>: seeing 小川 at the window seat again
         * tomorrow is the whole point, and it can only be a second sighting if the first was let go
         * of when he left. Never read by any model - only ResidentSimulation.perceive writes memories
         * from it. Old saves deserialize with the empty map default, same shape as the maps above. */
        public Map<String,String> lastSeenOfOthers = new LinkedHashMap<>();
        /** When this resident last wrote anything down about each other resident, kept across their
         * comings and goings so the floor between two sightings of the same person holds even for
         * someone who keeps stepping in and out. */
        public Map<String,Instant> lastWitnessOfOthersAt = new LinkedHashMap<>();
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
        /** Local minutes of day and the place the resident meant to be in; null on a legacy plan. */
        public Integer startMinute, endMinute;
        public String place, action;
    }
    /** One usual stretch of a resident's day: roughly when, where, doing what, and for whom. */
    public static class Duty {
        public int startMinute, endMinute;
        public String place, action, what, toId;
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
    /** A place a resident can be: the shared public buildings (cafe, garden, street, and - see
     * docs/01-requirements.md's 第二版「世界」 - academy/gym/board/shop), or one resident's own home.
     * `ownerId` is null for a shared place. This is the "建筑" layer of the four-layer address
     * `世界:建筑:房间:物件` the second version calls for; {@link Room} is the new "房间" layer between
     * this and {@link Position}/{@link WorldObject} - see that record's own doc comment for why it is
     * additive rather than a rename of this one. */
    public record Location(String id, String kind, String ownerId) {}
    /** The "房间" layer inside one {@link Location}: a bedroom, a shared kitchen, the cafe's back
     * room. Deliberately its own new list rather than a field folded onto {@code Location} or
     * {@code Position} - docs/02-modules.md's own layering note ("先用清晰的类和子目录组织") and the
     * worked example in this batch's own brief both point the same way: {@code Location} keeps
     * meaning exactly what it always has (a building), {@code Position} keeps meaning exactly what it
     * always has (a claimable spot, with {@code place} still resolving to the building id so every
     * existing {@code "cafe".equals(position.place)} call site in this file keeps working untouched),
     * and a room is a new, third thing between them. `residentIds` is who this room actually belongs
     * to - empty for a shared common room (a flat-mates' kitchen), one id for an ordinary bedroom, two
     * for an older couple's shared room (docs/01 「住所按人生阶段分」) - never a single nullable
     * `ownerId` the way {@link Location} and {@link Position} use, because a shared bedroom is not an
     * edge case here the way an unowned bench is for a Position; it is one of the two shapes this
     * version explicitly asks for. Self-healing on an old save via the empty-list default, same shape
     * as {@link #doors}: an old world simply has none yet, and {@code TownPlaces.seed} backfills them
     * the same way it already backfills beds and desks. */
    public record Room(String id, String buildingId, String kind, List<String> residentIds) {}
    /** A specific spot inside a place - a bed, a window seat, a shared table. `ownerId` null means
     * anyone can sit; capacity limits how many occupants fit at once. */
    public static class Position {
        public String id, place, kind, ownerId;
        /** Which {@link Room} this position sits inside, or {@code null} for a position at a place
         * that has not been given room subdivision yet (every public building today, and any save
         * from before {@link Room} existed) - {@code place} alone still fully identifies where a
         * resident is for every existing purpose (occupancy, distance, the frontend's own layout);
         * this is additional address precision, never a replacement for it. Left unpopulated rather
         * than guessed for an old save - see {@code TownPlaces.seed}'s repair loop, which sets this
         * going forward but never invents a room for furniture nobody described one for. */
        public String roomId;
        public int capacity;
        public List<String> occupantIds = new ArrayList<>();
        /** Operational state for genuinely scarce things. "usable" and "broken" are rule-owned;
         * ordinary seats keep the same default and never wear out. Old saves heal this field in
         * TownPlaces.seed rather than making null a third, accidental state. */
        public String condition = "usable";
        /** People who chose this exact scarce thing while it was occupied, in FIFO order. This is a
         * physical queue, not a judgement about who deserves it; the resident already chose the
         * action before the rules put their id here. */
        public List<String> waitingIds = new ArrayList<>();
        public int usesSinceRepair, maintenanceEveryUses;
        public Instant conditionChangedAt;
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
    /**
     * @param positionId which named position (see {@code TownPlaces.Position.id}) the event's
     * actor actually took or left, for the "took_spot"/"left_spot" events TownPlaces records when
     * occupancy of a claimable spot genuinely changes hands - null for every other event type.
     * Deliberately its own field rather than reusing {@code projectId}: a position id and a project
     * id are two unrelated kinds of reference that can each be non-null independently of the other,
     * and folding one into the other's field would leave every downstream reader guessing which
     * kind of id a given event's last slot actually holds.
     */
    public record WorldEvent(String id,Instant at,String type,String place,List<String> actorIds,String text,String projectId,String positionId) {
        /** Back-compat for call sites written before {@code positionId} existed (kept out of this
         * batch's edit scope) - defaults it to null, exactly what every such event already means. */
        public WorldEvent(String id,Instant at,String type,String place,List<String> actorIds,String text,String projectId){
            this(id,at,type,place,actorIds,text,projectId,null);
        }
    }
    /**
     * @param ownerId who this decorative object currently belongs to, or {@code null} for a communal
     * one nobody owns (the worktable, the noticeboard) and therefore not lend/gift-eligible - see
     * {@code Lending}, docs/01-requirements.md 第二版「世界」「物件按会不会被争分两类」. This is the
     * "纯装饰的走自由文本" half of that split: {@code state} stays free text precisely because nothing
     * about who currently holds an ownable object needs occupancy, a timer or a queue the way a
     * contested {@link Position} does - the mutex that matters here is a much smaller one
     * ({@code Lending.isOnLoan}: an item already out cannot be lent again), not a capacity count.
     * Changes hands by replacement (this is a record, like every other {@code w.objects} entry
     * already is) - a gift moves it permanently, a loan only moves {@code place} to wherever the
     * borrower is while {@code ownerId} stays with the lender, exactly like a real object does not
     * stop belonging to you just because a neighbour is holding it.
     */
    public record WorldObject(String id,String kind,String place,String roomId,String label,String state,String projectId,String ownerId,String holderId) {
        /** Back-compat for every call site written before {@code ownerId} existed (kept out of this
         * batch's edit scope) - defaults it to null, i.e. "nobody in particular owns this", exactly
         * what every such object already meant. */
        public WorldObject(String id,String kind,String place,String label,String state,String projectId){
            this(id,kind,place,null,label,state,projectId,null,null);
        }
        /** Back-compat for the first ownership-aware shape. A newly authored owned object starts in
         * its owner's hands; migration repairs older serialized null holders from open loans. */
        public WorldObject(String id,String kind,String place,String label,String state,String projectId,String ownerId){
            this(id,kind,place,null,label,state,projectId,ownerId,ownerId);
        }
        public WorldObject(String id,String kind,String place,String roomId,String label,String state,String projectId,String ownerId){
            this(id,kind,place,roomId,label,state,projectId,ownerId,ownerId);
        }
    }
    /**
     * One lend or one gift of an owned {@link WorldObject}, whole - who lent/gave what to whom, when,
     * and whether it has come back. This is the entire "欠" boundary docs/01-requirements.md 第二版
     * 「世界」 calls the most important edge of this batch: <b>no reason field, ever</b> - the same red
     * line {@link Deed} already draws for a habitual action, and for the same argument. A bystander
     * standing right there could see this exact tuple happen; nobody standing there could see WHY, so
     * this record never carries one, and nothing in this package ever writes "X owes Y" into it. If a
     * resident ever comes to believe they owe somebody a turn, that belief is theirs to write, in
     * their own self-authored memory text - never a field the rules populate for them. That is the
     * whole measurement this batch is betting on: whether anyone ever writes down a debt the rules
     * never told them about.
     * @param gift true for an outright gift (nothing is ever expected back - {@code returnedAt} is
     * always null and irrelevant, not merely "not yet"); false for a real loan, where a null
     * {@code returnedAt} means it is still out.
     */
    public record Loan(String id,String itemId,String lenderId,String borrowerId,Instant lentAt,Instant returnedAt,boolean gift) {}
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
    /** One finished stretch of a resident's own day: what they did, where, and for how long. */
    public static class Doing {
        public String action, place;
        public Instant at;
        public int seconds;
    }
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
