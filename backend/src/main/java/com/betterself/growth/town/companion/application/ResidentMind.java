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
    record DayPlanRequest(Context perspective) {}
    /** One face-to-face fact put to the resident it happened to. */
    record ReactRequest(Context perspective,String pendingId,String otherId,String otherName,String otherActivity,String place) {}
    /** {@code reaction} is one of greet / join / none - walk up and say something, sit down near them
     * without speaking, or leave them be. Declining is a first-class answer, not a failure. */
    record ReactDraft(String reaction,String reason,List<String> evidenceIds) {}
    /** 3-4 short qualitative segments for the day ahead (see {@link #planDay}), plus 0-3 of this
     * resident's own memory ids the plan is grounded in - the same evidence discipline every other
     * model output already follows. Never a time-slotted schedule: ResidentSimulation never checks
     * elapsed real time against these segments, only whether one is still "pending" when the day rolls
     * over. */
    record DayPlanDraft(List<String> segments,List<String> evidenceIds) {}
    record Context(String residentId,String localTime,String weather,ActorView self,String goal,
                   List<String> salientPerceptions,List<String> routineCues,List<MemoryView> memories,List<ActorView> nearby,
                   List<WorldObjectView> visibleObjects,List<PersonHereView> peopleHere,List<KnownPlaceView> knownPlaces,List<KnownProject> knownProjects,List<TurnView> conversation,
                   LifeIntentView lifeIntent,LifeIntentView careerIntent,PlanView currentPlan,List<WorkArrangementView> workArrangements,
                   String occupation,PersonaView persona,List<String> availableActions,String cafeOperatorId,List<String> cafeRoleFacts,boolean canTend,List<ServiceRequestView> visibleServiceRequests,
                   String cafeStatus,String cafeScheduleCue,String cafeNotice,PausedActionView pausedAction,PortableActionView portableAction) {
        /** Source-compatible constructor for existing model fixtures.  New runtime contexts always
         * use the qualitative canonical shape above; legacy numeric arguments are intentionally
         * ignored so they cannot reappear in serialized model input. */
        public Context(String worldId,String residentId,long revision,long intentRevision,Instant at,String localTime,
                       String weather,Actor self,String goal,String mood,String thought,double energy,double social,
                       java.util.Map<String,Integer> relationships,List<Memory> memories,List<Actor> nearby,
                       List<WorldObject> visibleObjects,List<KnownProject> knownProjects,List<Turn> conversation){
            this(residentId,localTime,weather,actorView(self),goal,List.of(),List.of(),memoryViews(memories),actorViews(nearby),
                objectViews(visibleObjects),List.of(),List.of(),knownProjects,turnViews(conversation),null,null,null,List.of(),null,null,
                List.of("observe","rest","study","work","read","make","sleep","change_work","propose"),null,List.of(),false,List.of(),null,null,null,null,null);
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
    record WorldObjectView(String id,String kind,String place,String label,String state,String projectId) {}
    record KnownPlaceView(String id,String description,List<String> possibleActivities) {}
    record PlanView(String id,String action,String place,String targetId,String reason,String startedAt,String endsAt,long remainingSeconds) {}
    record LifeIntentView(String id,String goalId,String purpose,String status,String formedAt,String updatedAt,String lastActedAt) {}
    record WorkArrangementView(String id,String kind,String place,String proposerId,String workerId,String status,String note,
                               String proposedAt,String acceptedAt,String endedAt) {}
    record ServiceRequestView(String id,String requesterId,String kind,String status,String place) {}
    record PausedActionView(String action,String place,String reason,long remainingSeconds) {}
    record PortableActionView(String action,String reason,long remainingSeconds) {}
    record KnownProject(String id,String title,String place,String stage) {
        public KnownProject(String id,String title,String place){this(id,title,place,"刚开始");}
    }
    record Decision(String action,String place,String targetId,String reason,String speech,List<String> evidenceIds,String projectTitle,String objectKind) {}

    static ActorView actorView(Actor actor){return actor==null?null:new ActorView(actor.id(),actor.name(),actor.role(),modelPlace(actor.place()),actor.activity(),actor.label());}
    static List<ActorView> actorViews(List<Actor> actors){return actors==null?List.of():actors.stream().map(ResidentMind::actorView).toList();}
    static MemoryView memoryView(Memory memory){return memory==null?null:new MemoryView(memory.id(),memory.ownerId(),memory.sourceId(),memory.sourceType(),instant(memory.at()),memory.text(),memory.topicId(),List.copyOf(memory.evidenceIds()));}
    static List<MemoryView> memoryViews(List<Memory> memories){return memories==null?List.of():memories.stream().map(ResidentMind::memoryView).toList();}
    static TurnView turnView(Turn turn){return turn==null?null:new TurnView(turn.speakerId(),turn.text(),instant(turn.at()),turn.source(),turn.emoji());}
    static List<TurnView> turnViews(List<Turn> turns){return turns==null?List.of():turns.stream().map(ResidentMind::turnView).toList();}
    static WorldObjectView objectView(WorldObject object){return object==null?null:new WorldObjectView(object.id(),object.kind(),modelPlace(object.place()),object.label(),observedState(object.state()),object.projectId());}
    static List<WorldObjectView> objectViews(List<WorldObject> objects){return objects==null?List.of():objects.stream().map(ResidentMind::objectView).toList();}
    static PlanView planView(Plan plan,Instant now){return plan==null?null:new PlanView(plan.id(),plan.action(),modelPlace(plan.place()),plan.targetId(),plan.reason(),instant(plan.startedAt()),instant(plan.endsAt()),now==null||plan.endsAt()==null?0:Math.max(0,java.time.Duration.between(now,plan.endsAt()).getSeconds()));}
    private static String modelPlace(String place){return com.betterself.growth.town.companion.domain.TownPlaces.isHome(place)?"home":place;}
    private static String instant(Instant value){return value==null?null:value.toString();}
    private static String observedState(String state){
        if(state==null)return null;if(!state.startsWith("progress-"))return state;
        try{int progress=Integer.parseInt(state.substring("progress-".length()));return progress<25?"刚开始":progress<70?"进行中":progress<100?"大体完成":"已经完成";}catch(NumberFormatException ignored){return "正在变化";}
    }
}
