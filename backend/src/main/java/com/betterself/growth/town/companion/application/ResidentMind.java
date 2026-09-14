package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import java.time.Instant;
import java.util.List;

/** The model sees one resident's perspective, never the world aggregate or the user's intents/Todos. */
public interface ResidentMind {
    boolean enabled();
    Decision decide(Context context);
    default com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance generateTurn(DialogueRequest request){throw new UnsupportedOperationException("Dialogue generation unavailable");}
    default com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){throw new UnsupportedOperationException("Conversation recollection unavailable");}
    /** One coarse, three-or-four-segment plan for this resident's day (see docs/01's "recursive day
     * plan" - a coarse morning outline, not a schedule the rest of the day is checked against). Budget
     * is one call per resident per morning; see ResidentDirector's own gating for exactly when this is
     * offered. Additive default like the two methods above: any existing ResidentMind that predates
     * day planning keeps compiling and simply has no opinion until it opts in. */
    /** Whether this mind plans days. Asked before a day plan is reserved, because a day plan now
     * comes ahead of every ordinary decision: a mind that cannot plan must not spend each resident's
     * first reservation of the day finding that out. Decorators forward it. */
    default boolean plansDays(){return false;}
    default DayPlanDraft planDay(DayPlanRequest request){throw new UnsupportedOperationException("Day planning unavailable");}
    default Result<DayPlanDraft> planDayMetered(DayPlanRequest request){return new Result<>(planDay(request),null);}
    /** "Someone is standing in front of you - do you do anything about it?" Generative Agents asks
     * exactly this of every observation (§4.3.1: <i>Should John react to the observation, and if so,
     * what would be an appropriate reaction?</i>) rather than offering social contact as one entry in
     * a long menu of actions. Our own menu measured 96 chances to invite somebody and zero taken, with
     * a person standing right there in 168 of 357 decisions - a question that is never asked directly
     * does not get answered. This is that question, asked on its own. */
    default ReactDraft react(ReactRequest request){throw new UnsupportedOperationException("Reaction unavailable");}
    default Result<ReactDraft> reactMetered(ReactRequest request){return new Result<>(react(request),null);}

    /** "This just happened. Do you want to do anything about it?" - asked once, at the moment, about
     * one thing, with a free "no".
     *
     * <p>The same shape as {@link #react}, generalized, and it exists because the lesson react taught
     * had to be learned four separate times before anybody wrote it down. An action that only makes
     * sense at one kind of moment does not belong in a menu offered at every moment: {@code
     * change_work} was offered 839 times in two simulated days and taken 0, {@code invite} 555/0,
     * {@code lock_door} 470/0, {@code close_cafe} 142/0, while {@code greet} - the one thing this town
     * asks about as it happens - ran at 80.6%.
     *
     * <p>{@code lock_door} is the one that showed what was really wrong. The refusals were not the
     * model failing to see the point of locking a door; the real occasion for it (last one in the
     * shop, on the way out) came round 7 times in those two days, and the other 463 asks were put to
     * somebody standing in a busy open cafe at two in the afternoon. Every one of those refusals was
     * correct. We had built an instrument that measured our own timing and read it as the residents'
     * indifference.
     *
     * <p>The rules decide only whether this is the moment. They never decide the answer, and they
     * never treat silence as consent - an unanswered occasion expires and nothing happens. */
    default ConsiderDraft consider(ConsiderRequest request){throw new UnsupportedOperationException("Consideration unavailable");}
    default Result<ConsiderDraft> considerMetered(ConsiderRequest request){return new Result<>(consider(request),null);}

    /**
     * Rules are the reflex; the model is the explanation. Most of what a person does, they do first
     * and account for afterwards - and the account is frequently not the real cause (Gazzaniga's
     * interpreter, Libet's readiness potential, Nisbett &amp; Wilson's <i>Telling More Than We Can
     * Know</i>). {@code deeds} are the bystander-view facts {@link
     * com.betterself.growth.town.companion.domain.ResidentSimulation#recordDeed} queued up - what an
     * observer would have seen, never a motive, because the rules never know one. This call asks the
     * resident to make sense of their own recent behaviour, in their own words, coloured by whatever
     * they will and will not admit to themselves - never a neutral restatement of the facts above.
     */
    default ExplainDraft explain(ExplainRequest request){throw new UnsupportedOperationException("Explanation unavailable");}
    default Result<ExplainDraft> explainMetered(ExplainRequest request){return new Result<>(explain(request),null);}

    /**
     * The rules never count occurrences and declare a pattern - that would be the opposite of what a
     * mind actually does. This call hands the resident an open, unguided browse of their own memories
     * ({@link com.betterself.growth.town.companion.domain.ResidentSimulation#reflectionSource}, no
     * particular question in mind) and asks them to look back over it. Most of the time nothing in
     * particular comes of it. Occasionally something repeats often enough across the material that the
     * resident themselves notices the shape of it - that, and only that, is what may become a standing
     * belief (see {@link ReflectDraft#supersedesKey}); the rules supply the material and store the
     * conclusion, never the generalization itself.
     */
    default ReflectDraft reflect(ReflectRequest request){throw new UnsupportedOperationException("Reflection unavailable");}
    default Result<ReflectDraft> reflectMetered(ReflectRequest request){return new Result<>(reflect(request),null);}

    /**
     * "There is nothing left in this town that you and anyone else could be doing together. Is there
     * something you want that you could not do on your own?"
     *
     * <p>This exists because of one measurement, repeated across several runs and unmoved by rewriting
     * ten separate lines of prompt: {@code propose} was offered to residents 472 times in a two-day run
     * and chosen zero, as were {@code invite} (285), {@code join} (285), {@code help} (173) and
     * {@code celebrate} (261) - about 1800 chances, two takers. Over the same two days the same model,
     * asked whether to say something to a person standing in front of it, said yes 67 times out of 127.
     *
     * <p>The difference is not the wording, it is the shape of the question. An ordinary decision is a
     * flat menu of some twenty actions, and at any given instant "carry on reading" is a locally
     * sensible answer while "start something that needs other people" is a discretionary extra sitting
     * twentieth on the list - so it loses every comparison it is ever in. Asked on its own, about a
     * situation that is actually in front of the resident, it wins about half. Generative Agents makes
     * the same split for the same reason: reactions are asked of an observation, not chosen off a list
     * of everything a person could possibly do.
     *
     * <p>The rules decide only WHEN this is worth asking - when every shared thing this resident knows
     * of has finished or stalled beyond their reach - and never what anybody wants. A resident is
     * free to want nothing; most of the time that is the right answer and an empty draft says so.
     */
    /**
     * A promise came due and the rules wrote down the one fact they are allowed to write down: at the
     * hour that was named, the person who made it was there, or was not. This asks the other party -
     * and anyone who was standing there when it was made - what they make of that.
     *
     * <p>It exists because of the most expensive thing this project has measured (docs/05-notes.md
     * 四): {@code celebrate} was offered 1658 times and chosen zero times, {@code create} 342/0,
     * {@code invite} 285/0, while the same model asked {@code react} as its own separate question said
     * yes 53% of the time. <b>An obligation nobody is ever asked about is never honoured.</b> Adding a
     * promise object without adding this question would build a second celebrate: a perfect mechanism
     * with a thousand chances and no uses.
     *
     * <p>docs names this question 「他没来，你怎么想」, and it is asked on the {@code did_not_come}
     * case for exactly the reason above. It is asked on {@code came} too, and that is deliberate: a
     * town where only the failures are ever worth a thought is one we shaped to produce grievances.
     * Reputation is supposed to grow out of both halves.
     *
     * <p>The rules have no view on any of this. They never call it a betrayal, and they do not call it
     * loyalty either - see ResidentSimulation's promise settlement, which writes only where the person
     * was. What it meant is the resident's, the two of them may well disagree, and that disagreement is
     * the point. An answer of "nothing in particular" is a real answer and must stay easy to give.
     */
    /**
     * "There is someone standing in front of you. Is there anything you want to fix a time for?"
     *
     * <p>docs/01-requirements.md 的「社会怎么长出来」: a contract is a claim on the future, and until this existed the
     * residents could only ever express what they were doing right now. The decision prompt even said
     * so outright - 若只是想明天、改天或等有空再做，stance=consider - which was written to stop empty
     * promises and cancelled the whole idea of a promise along with them.
     *
     * <p>Asked on its own rather than added to the action menu, for the reason the menu keeps proving:
     * create 342 offers / 0 taken, invite 285/0, celebrate 1658/0, against react's 53% when the same
     * model is asked the same thing as its own question.
     *
     * <p>The rules decide only whether the moment is worth a question - somebody is here, something in
     * town still needs more than one pair of hands, this resident is not already carrying an unsettled
     * promise. They have no view on whether a promise should be made, to whom, or about what. "Nothing
     * I want to fix a time for" has to stay as easy an answer as any other, or this becomes a machine
     * for generating obligations nobody meant.
     */
    default PromiseOfferDraft promiseOffer(PromiseOfferRequest request){throw new UnsupportedOperationException("Promise offer unavailable");}
    default Result<PromiseOfferDraft> promiseOfferMetered(PromiseOfferRequest request){return new Result<>(promiseOffer(request),null);}

    default PromiseThought promiseSettled(PromiseSettledRequest request){throw new UnsupportedOperationException("Promise reaction unavailable");}
    default Result<PromiseThought> promiseSettledMetered(PromiseSettledRequest request){return new Result<>(promiseSettled(request),null);}

    default VentureDraft venture(VentureRequest request){throw new UnsupportedOperationException("Venture unavailable");}
    default Result<VentureDraft> ventureMetered(VentureRequest request){return new Result<>(venture(request),null);}

    /**
     * Token-metered variants of the three calls above. Additive on purpose: implementations that only
     * override the plain methods (every existing fake/mock ResidentMind, including test doubles) keep
     * compiling unchanged and simply report no usage, which callers must treat as "nothing to record" -
     * not as zero cost.
     */
    default Result<Decision> decideMetered(Context context){return new Result<>(decide(context),null);}
    default Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance> generateTurnMetered(DialogueRequest request){return new Result<>(generateTurn(request),null);}
    default Result<com.betterself.growth.town.companion.domain.ConversationLifecycle.Recollection> summarizeConversationMetered(SummaryRequest request){return new Result<>(summarizeConversation(request),null);}

    /**
     * Prompt/completion token counts for one model call. Null usage upstream means "not measured",
     * never zero cost. {@code provider} names which backend actually served the call (e.g. "qwen",
     * "deepseek") so usage can be broken down per supplier, not just per call type - it is additive and
     * may be null for any caller that predates multi-provider routing (test doubles included).
     */
    record Usage(int inputTokens,int outputTokens,String provider,String model,boolean reasoningContentPresent,int reasoningTokens) {
        public Usage(int inputTokens,int outputTokens){this(inputTokens,outputTokens,null,null,false,0);}
        public Usage(int inputTokens,int outputTokens,String provider){this(inputTokens,outputTokens,provider,null,false,0);}
    }
    record Result<T>(T value,Usage usage) {}

    record DialogueRequest(Context perspective,String conversationId,long turnVersion,String operationId,String partnerName,String topicTitle) {}
    record SummaryRequest(Context perspective,String conversationId,String partnerName,List<Turn> transcript,List<MemoryView> conversationMemories) {}
    /** {@code duties} is what this resident believes their usual day looks like (ResidentDuties) -
     * text for the model to plan from, never a quota. */
    record DayPlanRequest(Context perspective,List<DutyView> duties,String usualWake,String usualSleep) {
        public DayPlanRequest(Context perspective){this(perspective,List.of(),null,null);}
    }
    record DutyView(String from,String to,String place,String action,String what,String handTo) {}
    /** One face-to-face fact put to the resident it happened to. {@code reactions} is what the rules
     * can see is actually possible at this instant, and it is data rather than a constant because of
     * {@code invite}: asking somebody to come and do a thing with you is only a question worth asking
     * while they are standing in front of you and you have a thing that needs hands, which is exactly
     * this moment - so it is offered here, when both are true, instead of sitting in the ordinary
     * action menu where it was offered 555 times and taken none (see
     * {@link com.betterself.growth.town.companion.domain.Occasions}). {@code sharedThing} names what
     * that is, and is null whenever invite is not among the choices. */
    record ReactRequest(Context perspective,String pendingId,String otherId,String otherName,String otherActivity,String place,
                        List<String> reactions,String sharedThing) {}
    /** {@code reaction} is one of greet / join / invite / none - walk up and say something, sit down
     * near them without speaking, ask them to come and do the thing you are in the middle of, or
     * leave them be. Declining is a first-class answer, not a failure. */
    record ReactDraft(String reaction,String reason,List<String> evidenceIds) {}
    /** A moment the rules recognised as the one moment some particular action makes sense in, put to
     * the resident as its own question - see {@link
     * com.betterself.growth.town.companion.domain.Occasions} for why this exists at all and what the
     * four numbers were that forced it. {@code fact} is what an onlooker would have seen, {@code
     * question} the one thing being asked, {@code yes}/{@code no} what each answer means in plain
     * words. There are always exactly two answers and the second one is free. */
    record ConsiderRequest(Context perspective,String pendingId,String key,String fact,String question,
                           String yes,String no,String place) {}
    /** {@code choice} is either the occasion's own key or {@code none}. {@code none} needs no reason
     * and is the commonest answer; {@code speech} is what the resident actually says out loud if
     * doing the thing involves saying something, and is null otherwise. */
    record ConsiderDraft(String choice,String reason,String speech,List<String> evidenceIds) {}
    /** A resident's own recent unaccounted-for behaviour, described the way a bystander would - see
     * {@link com.betterself.growth.town.companion.domain.CompanionWorld.Deed}'s own doc comment. Never
     * carries a motive; supplying one is the entire point of {@link #explain}. */
    record DeedView(String id,String action,String place,String note,String at) {}
    record ExplainRequest(Context perspective,List<DeedView> deeds) {}
    /** {@code deedIds} names which of the offered deeds this account actually covers - not every deed
     * need be mentioned, and the ones left out are simply not part of the account. {@code text} is
     * this resident's own explanation for themselves, which may be wrong, self-serving, vague, or one
     * they only half believe - see {@link #explain}'s own doc comment; it is never required to be a
     * complete or accurate account of {@code deedIds}. */
    record ExplainDraft(List<String> deedIds,String text,List<String> evidenceIds) {}
    /** One of this resident's own default reflexes, and the exact key a belief must carry to stand
     * over it (see ResidentSimulation's "habitTraits"). Offered, never required: most reflections are
     * about other people or about one particular day, and only a resident who has actually noticed
     * one of these in themselves should reach for it. */
    record HabitTraitView(String key,String description) {}
    /** A standing view this resident already holds, and the key it is filed under. Handed back to them
     * so a later reflection about the same thing can REPLACE it instead of minting a second label for
     * one topic - {@code supersedesKey} only ever supersedes on an exact string match
     * ({@code ResidentSimulation.supersedePrevious}), so a resident who invents a fresh code name every
     * time accumulates parallel opinions that never meet.
     *
     * <p>Every surveyed system that actually revises a belief does this and none ask the model to
     * invent an identifier: Zep/Graphiti hands the model integer indices into a candidate list
     * ({@code contradicted_facts: list[int]}), Mem0 masks its uuids as "0".."9" and instructs "do not
     * generate any new ID", Affordable Generative Agents prints the current relationship and asks
     * whether it needs updating. Ours asked for a made-up label from nothing, and measured 0 beliefs.
     *
     * <p>Text and key are both the resident's own words from an earlier turn - the rules never author
     * either, and offering them back is not a suggestion about what to think now. Which way a view
     * should move, or whether it should move at all, stays entirely the model's. */
    record StandingBeliefView(String key,String text) {}
    record ReflectRequest(Context perspective,List<MemoryView> source,List<HabitTraitView> habits,List<StandingBeliefView> standingBeliefs) {
        /** Shape used before standing beliefs were offered back; keeps hand-built test fixtures compiling. */
        public ReflectRequest(Context perspective,List<MemoryView> source,List<HabitTraitView> habits){
            this(perspective,source,habits,List.of());
        }
    }
    /** {@code sharedThingsLeft} is what this resident still knows of that anybody could put a hand on -
     * empty is the whole reason this call is being made, and it is handed over rather than described
     * so the resident can see for themselves that there is nothing rather than being told so. */
    record VentureRequest(Context perspective,List<KnownProject> sharedThingsLeft) {}
    /** A null or blank {@code title} means "nothing I want badly enough right now", which is a real
     * and common answer and is not a failure. Otherwise the same four things a proposal has always
     * needed, validated by exactly the same rules any other proposal goes through. */
    record VentureDraft(String title,String place,String objectKind,String reason,List<String> evidenceIds) {}
    /** One promise as the resident being asked knows it. {@code outcome} is only ever "came" or
     * "did_not_come" - the fact, with no reading attached. {@code role} says which side of it this
     * resident was on ("promised_to" / "witnessed" / "made_it"), because being the person who was
     * waiting and being the person who happened to overhear it are not the same position. */
    record PromiseView(String promiseId,String byId,String byName,String what,String place,String dueAt,String outcome,String role) {}
    record PromiseSettledRequest(Context perspective,PromiseView promise,List<MemoryView> aboutThem) {}
    /** Same shape as {@link ReflectDraft} and lands the same way, through applyReflection: a passing
     * thought stores as one reflection, and a {@code supersedesKey} makes it the standing view this
     * resident now holds about that person - which is how "某人说话不算数" becomes something they hold
     * rather than something we computed. Empty text is a legitimate answer: nothing in particular. */
    record PromiseThought(String text,String supersedesKey,List<String> evidenceIds) {}
    /** {@code peopleHere} is who is actually standing there to say it to; {@code thingsNeedingHands} is
     * what in town still takes more than one person, handed over rather than described so the resident
     * can see for themselves. Neither is a list of things they ought to promise. */
    record PromiseOfferRequest(Context perspective,List<ActorView> peopleHere,List<KnownProject> thingsNeedingHands) {}
    /** A null or blank {@code what} means "nothing I want to fix a time for", which is a real answer and
     * the commonest one. {@code inHours} is how far ahead they mean, so the resident says "tonight" or
     * "tomorrow morning" in their own terms rather than being handed a clock; the rules turn it into an
     * instant and refuse anything past a day. */
    record PromiseOfferDraft(String toId,String what,String place,Double inHours,List<String> evidenceIds) {}

    /** {@code supersedesKey} non-null means this resident has genuinely noticed something recurring
     * across {@code source} and is naming it as a standing belief about someone or something, replacing
     * whatever they previously believed under the same key; null means an ordinary one-off reflection
     * that stands in for nothing. The key is the resident's own short label, never assigned by a rule -
     * with one exception, {@link HabitTraitView#key}, which is a fixed key the rules recognise so that
     * a belief a resident forms about one of their own reflexes can actually reach that reflex. Even
     * there the rules only supply the label; whether to use it at all, and what to conclude under it,
     * is entirely the resident's. */
    record ReflectDraft(String text,List<String> evidenceIds,String supersedesKey) {}
    /** 3-4 short qualitative segments for the day ahead (see {@link #planDay}), plus 0-3 of this
     * resident's own memory ids the plan is grounded in - the same evidence discipline every other
     * model output already follows. Never a time-slotted schedule: ResidentSimulation never checks
     * elapsed real time against these segments, only whether one is still "pending" when the day rolls
     * over. */
    record DayPlanDraft(List<SegmentDraft> segments,List<String> evidenceIds) {}
    /** start/end are local "HH:mm"; place is a knownPlaces id ("home" for one's own). */
    record SegmentDraft(String start,String end,String place,String action,String label) {}
    record Context(String residentId,String localTime,String weather,ActorView self,String goal,
                   List<String> salientPerceptions,List<String> routineCues,List<MemoryView> memories,
                   List<com.betterself.growth.town.companion.domain.ResidentSimulation.TodayDoing> todaySoFar,List<ActorView> nearby,
                   List<WorldObjectView> visibleObjects,List<PersonHereView> peopleHere,List<KnownPlaceView> knownPlaces,List<KnownProject> knownProjects,List<TurnView> conversation,
                   LifeIntentView lifeIntent,LifeIntentView careerIntent,PlanView currentPlan,List<WorkArrangementView> workArrangements,
                   String occupation,PersonaView persona,List<String> availableActions,List<DecisionOptionView> decisionOptions,String cafeOperatorId,List<String> cafeRoleFacts,boolean canTend,List<ServiceRequestView> visibleServiceRequests,
                   String cafeStatus,String cafeScheduleCue,String cafeNotice,PausedActionView pausedAction,PortableActionView portableAction,String currentRoomId,
                   List<com.betterself.growth.town.companion.domain.ResidentSimulation.PositionUseView> positionUses) {
        /** Compatibility shape for hand-built contexts that predate exact four-level decision
         * options. Production contexts always use the canonical constructor above. */
        public Context(String residentId,String localTime,String weather,ActorView self,String goal,
                       List<String> salientPerceptions,List<String> routineCues,List<MemoryView> memories,
                       List<com.betterself.growth.town.companion.domain.ResidentSimulation.TodayDoing> todaySoFar,List<ActorView> nearby,
                       List<WorldObjectView> visibleObjects,List<PersonHereView> peopleHere,List<KnownPlaceView> knownPlaces,List<KnownProject> knownProjects,List<TurnView> conversation,
                       LifeIntentView lifeIntent,LifeIntentView careerIntent,PlanView currentPlan,List<WorkArrangementView> workArrangements,
                       String occupation,PersonaView persona,List<String> availableActions,String cafeOperatorId,List<String> cafeRoleFacts,boolean canTend,List<ServiceRequestView> visibleServiceRequests,
                       String cafeStatus,String cafeScheduleCue,String cafeNotice,PausedActionView pausedAction,PortableActionView portableAction){
            this(residentId,localTime,weather,self,goal,salientPerceptions,routineCues,memories,todaySoFar,nearby,
                visibleObjects,peopleHere,knownPlaces,knownProjects,conversation,lifeIntent,careerIntent,currentPlan,
                workArrangements,occupation,persona,availableActions,List.of(),cafeOperatorId,cafeRoleFacts,canTend,
                visibleServiceRequests,cafeStatus,cafeScheduleCue,cafeNotice,pausedAction,portableAction,null,List.of());
        }
        /** Source-compatible constructor for existing model fixtures.  New runtime contexts always
         * use the qualitative canonical shape above; legacy numeric arguments are intentionally
         * ignored so they cannot reappear in serialized model input. */
        public Context(String worldId,String residentId,long revision,long intentRevision,Instant at,String localTime,
                       String weather,Actor self,String goal,String mood,String thought,double energy,double social,
                       java.util.Map<String,Integer> relationships,List<Memory> memories,List<Actor> nearby,
                       List<WorldObject> visibleObjects,List<KnownProject> knownProjects,List<Turn> conversation){
            this(residentId,localTime,weather,actorView(self),goal,List.of(),List.of(),memoryViews(memories),List.of(),actorViews(nearby),
                objectViews(visibleObjects),List.of(),List.of(),knownProjects,turnViews(conversation),null,null,null,List.of(),null,null,
                List.of("none","observe","rest","study","work","read","make","sleep","propose"),List.of(),null,List.of(),false,List.of(),null,null,null,null,null,null,List.of());
        }
    }
    /**
     * The three-layer personality text (see {@code ResidentSeed.PersonalityNarrative}), copied
     * verbatim for whichever six residents have it authored - {@code null} for the avatar ("self")
     * and for any resident this batch never wrote text for, never guessed. Text only, never a score:
     * nothing here may gate {@code availableActions} or any other capability, and it never carries a
     * single character of user input (the avatar's context always sends {@code null}). {@code
     * wantSelf}/{@code oughtSelf} explain a resident's own motive to the model, never as an order;
     * {@code actingSelf} shapes how {@code reason}/{@code speech} are said, not what they contain;
     * {@code memoryBias} is what this resident tends to write into a memory of the same event; {@code
     * looseningNote} is the repeated real experience after which they let the usually-collected side
     * show a little.
     */
    record PersonaView(String wantSelf,String oughtSelf,String actingSelf,String memoryBias,String looseningNote) {}
    /** What this resident carries about one person who is actually in front of them right now.
     * Generative Agents builds the same thing by retrieving on "What is [observer]'s relationship with
     * [observed]?" and summarising the result into a sentence; we point at the retrieved memories
     * already in {@code Context.memories} instead of paying for a second model call to summarise them,
     * so the evidence discipline (a resident may only cite their own real memories) still holds.
     * {@code closeness} is a phrase, never the underlying number - see the note in ResidentDirector on
     * why no internal value may be shown to a model. Deliberately NOT named "relationships": that
     * name belongs to {@code ResidentState.relationships}, a raw {@code Map<String,Integer>} that two
     * separate tests ban by name from ever appearing in a model contract. Reusing it here would have
     * quietly retired those guards. */
    record PersonHereView(String personId,String personName,String closeness,List<String> memoryIds) {}
    record ActorView(String id,String name,String role,String place,String activity,String label) {}
    record MemoryView(String id,String ownerId,String sourceId,String sourceType,String at,String text,String topicId,List<String> evidenceIds) {}
    record TurnView(String speakerId,String text,String at,String source,String emoji) {}
    record WorldObjectView(String id,String kind,String place,String roomId,String label,String state,String projectId) {}
    record KnownPlaceView(String id,String description,List<String> possibleActivities) {}
    /** One complete choice already proven legal by the rules: building, room and exact leaf target
     * stay together so the model never receives their invalid Cartesian product. */
    record DecisionOptionView(String id,String action,String place,String roomId,String targetId) {}
    record PlanView(String id,String action,String place,String targetId,String reason,String startedAt,String endsAt,long remainingSeconds) {}
    record LifeIntentView(String id,String goalId,String purpose,String status,String formedAt,String updatedAt,String lastActedAt) {}
    record WorkArrangementView(String id,String kind,String place,String proposerId,String workerId,String status,String note,
                               String proposedAt,String acceptedAt,String endedAt) {}
    record ServiceRequestView(String id,String requesterId,String kind,String status,String place) {}
    record PausedActionView(String action,String place,String reason,long remainingSeconds) {}
    record PortableActionView(String action,String reason,long remainingSeconds) {}
    /** {@code startedBy} is whose idea it was, by name - the one social fact about a project that was
     * missing entirely. Without it every project reads like a private to-do item, and nobody puts
     * their hands on somebody else's to-do item. {@code stage} says in words how far along it is AND,
     * when it applies, that it has stopped for want of another pair of hands (see
     * ResidentSimulation's SOLO_PROGRESS_CAP) - a fact the town had but never told anyone. */
    record KnownProject(String id,String title,String place,String stage,String startedBy) {
        public KnownProject(String id,String title,String place){this(id,title,place,"刚开始",null);}
    }
    record Decision(String choiceId,String action,String place,String roomId,String targetId,String reason,String speech,List<String> evidenceIds,String projectTitle,String objectKind) {
        static final String LEGACY_ROOM="\u0000legacy-room";
        public Decision(String action,String place,String targetId,String reason,String speech,List<String> evidenceIds,String projectTitle,String objectKind){
            this(null,action,place,LEGACY_ROOM,targetId,reason,speech,evidenceIds,projectTitle,objectKind);
        }
        public Decision(String action,String place,String roomId,String targetId,String reason,String speech,List<String> evidenceIds,String projectTitle,String objectKind){
            this(null,action,place,roomId,targetId,reason,speech,evidenceIds,projectTitle,objectKind);
        }
    }

    static DecisionOptionView selectedOption(Context context,Decision decision){
        if(context==null||decision==null||context.decisionOptions()==null)return null;
        if(decision.choiceId()!=null)return context.decisionOptions().stream()
            .filter(option->decision.choiceId().equals(option.id())).findFirst().orElse(null);
        if(!Decision.LEGACY_ROOM.equals(decision.roomId()))return null;
        return context.decisionOptions().stream().filter(option->java.util.Objects.equals(option.action(),decision.action())
            &&java.util.Objects.equals(option.place(),decision.place())).findFirst().orElse(null);
    }

    static ActorView actorView(Actor actor){return actor==null?null:new ActorView(actor.id(),actor.name(),actor.role(),modelPlace(actor.place()),actor.activity(),actor.label());}
    static List<ActorView> actorViews(List<Actor> actors){return actors==null?List.of():actors.stream().map(ResidentMind::actorView).toList();}
    static MemoryView memoryView(Memory memory){return memory==null?null:new MemoryView(memory.id(),memory.ownerId(),memory.sourceId(),memory.sourceType(),instant(memory.at()),memory.text(),memory.topicId(),List.copyOf(memory.evidenceIds()));}
    static List<MemoryView> memoryViews(List<Memory> memories){return memories==null?List.of():memories.stream().map(ResidentMind::memoryView).toList();}
    static DeedView deedView(Deed deed){return deed==null?null:new DeedView(deed.id,deed.action,modelPlace(deed.place),deed.note,instant(deed.at));}
    static List<DeedView> deedViews(List<Deed> deeds){return deeds==null?List.of():deeds.stream().map(ResidentMind::deedView).toList();}
    static TurnView turnView(Turn turn){return turn==null?null:new TurnView(turn.speakerId(),turn.text(),instant(turn.at()),turn.source(),turn.emoji());}
    static List<TurnView> turnViews(List<Turn> turns){return turns==null?List.of():turns.stream().map(ResidentMind::turnView).toList();}
    static WorldObjectView objectView(WorldObject object){return object==null?null:new WorldObjectView(object.id(),object.kind(),modelPlace(object.place()),object.roomId(),object.label(),observedState(object.state()),object.projectId());}
    static List<WorldObjectView> objectViews(List<WorldObject> objects){return objects==null?List.of():objects.stream().map(ResidentMind::objectView).toList();}
    static PlanView planView(Plan plan,Instant now){return plan==null?null:new PlanView(plan.id(),plan.action(),modelPlace(plan.place()),plan.targetId(),plan.reason(),instant(plan.startedAt()),instant(plan.endsAt()),now==null||plan.endsAt()==null?0:Math.max(0,java.time.Duration.between(now,plan.endsAt()).getSeconds()));}
    private static String modelPlace(String place){return com.betterself.growth.town.companion.domain.TownPlaces.isHome(place)?"home":place;}
    private static String instant(Instant value){return value==null?null:value.toString();}
    private static String observedState(String state){
        if(state==null)return null;if(!state.startsWith("progress-"))return state;
        try{int progress=Integer.parseInt(state.substring("progress-".length()));return progress<25?"刚开始":progress<70?"进行中":progress<100?"大体完成":"已经完成";}catch(NumberFormatException ignored){return "正在变化";}
    }
}
