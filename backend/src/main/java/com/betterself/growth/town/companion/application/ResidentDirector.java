package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Short operation reservation -> asynchronous model I/O -> identity-checked authoritative input. */
@Service
public class ResidentDirector {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ResidentDirector.class);
    private final WorldStore store;
    private final ResidentMind mind;
    private final Clock clock;
    private final int dailyBudget;
    private final long decisionThrottleSeconds;
    private final ModelUsageRecorder usageRecorder;
    /** Fires exactly once per dispatched model call, right where {@code applied} (or its
     * failure/unsupported-capability equivalent) is actually decided - never a guess reconstructed
     * later from {@link CompanionWorld#modelStatus} text, which only a handful of the apply* paths
     * ever touch on success (see the accelerated runner's own audit of this). Default is a no-op so
     * production wiring (which has no listener) pays nothing; test/offline harnesses (see
     * AcceleratedTownRunner) attach one to build an exact offered/selected/applied/rejected account
     * per call type and action, closing the observability gap a purely tick-sampled export cannot. */
    public interface OutcomeListener{void onOutcome(String callType,String action,String outcome);}
    private volatile OutcomeListener outcomeListener=(callType,action,outcome)->{};
    public void setOutcomeListener(OutcomeListener listener){this.outcomeListener=listener==null?(callType,action,outcome)->{}:listener;}
    private final Set<Long> inFlight=ConcurrentHashMap.newKeySet();
    /** Suppresses repeated model calls for the same already-considered cue while a plan continues.
     * The fingerprint contains qualitative/observable state only; failures are never remembered. */
    private final Map<String,String> consideredDecisionSignals=new ConcurrentHashMap<>();
    // Development-phase defaults: five residents each writing their own memory need real
    // concurrency, and cost is not a constraint right now - see the constructor for the knobs.
    private final ThreadPoolExecutor executor;
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock){this(store,mind,clock,100000);}
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,int dailyBudget){this(store,mind,clock,dailyBudget,(userId,day,callType,inputTokens,outputTokens)->{});}
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,int dailyBudget,ModelUsageRecorder usageRecorder){this(store,mind,clock,dailyBudget,usageRecorder,8,64,12);}
    @Autowired
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,
                             @Value("${app.town.companion-model-daily-budget:100000}")int dailyBudget,
                             ModelUsageRecorder usageRecorder,
                             @Value("${app.town.companion-mind-pool-size:8}")int poolSize,
                             @Value("${app.town.companion-mind-queue-size:64}")int queueSize,
                             @Value("${app.town.companion-model-decision-throttle-seconds:12}")long decisionThrottleSeconds){
        this.store=store;this.mind=mind;this.clock=clock;this.dailyBudget=Math.max(1,dailyBudget);this.usageRecorder=usageRecorder;
        this.decisionThrottleSeconds=Math.max(0,decisionThrottleSeconds);
        int workers=Math.max(1,poolSize);
        this.executor=new ThreadPoolExecutor(workers,workers,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(Math.max(1,queueSize)),r->{Thread t=new Thread(r,"companion-mind");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean enabled(){return mind.enabled();}
    public void consider(long userId,CompanionWorld snapshot){
        if(!mind.enabled()||snapshot==null||snapshot.simulationVersion<2||!inFlight.add(userId))return;
        Instant now=clock.instant();
        if(snapshot.modelRetryAfter!=null&&now.isBefore(snapshot.modelRetryAfter)){inFlight.remove(userId);return;}
        boolean dialogue=snapshot.conversations.stream().anyMatch(c->c.mode.equals("model")&&(c.status.equals("active")||c.summarizedParticipants.size()<c.participantIds.size()));
        // Per-resident decision throttling (item 1): each resident now has their own cooldown
        // (ResidentState.lastDecisionRequestedAt) instead of one world-global timer, so this quick,
        // pre-transaction check (a cheap way to skip an executor dispatch when nothing could possibly
        // be reserved) asks "is ANY resident's own cooldown clear" rather than "has the whole town
        // waited long enough since the last decision, whoever it was for". reserve() re-checks each
        // candidate's own cooldown precisely inside the transaction; this is only an optimization.
        boolean anyResidentReady=snapshot.residentStates.stream().anyMatch(r->r.lastDecisionRequestedAt==null||Duration.between(r.lastDecisionRequestedAt,now).getSeconds()>=decisionThrottleSeconds);
        // A queued face-to-face fact is time-limited and ignores the per-resident cooldown, so it has
        // to be able to wake the executor on its own - otherwise the cheap pre-check above throws away
        // exactly the dispatch the encounter was waiting for.
        boolean encounter=!snapshot.pendingEncounters.isEmpty();
        if(!dialogue&&!anyResidentReady&&!encounter){inFlight.remove(userId);return;}
        try{executor.execute(()->run(userId));}catch(RejectedExecutionException e){inFlight.remove(userId);}
    }
    private record Work(String kind,String worldId,long residentRevision,long intentRevision,Instant at,
                        ResidentMind.Context context,ConversationLifecycle.Operation operation,
                        ResidentMind.DialogueRequest dialogue,ResidentMind.SummaryRequest summary,long sequence,String day,
                        ResidentMind.DayPlanRequest dayPlan,ResidentMind.ReactRequest react,
                        ResidentMind.ExplainRequest explain,ResidentMind.ReflectRequest reflect) {}
    /** Set once, permanently, the first time this director learns (via {@link
     * UnsupportedOperationException}) that the wired {@link ResidentMind} does not implement {@code
     * explain}/{@code reflect}. A missing capability is not a network failure and must never burn the
     * shared model-failure backoff budget (see run()'s catch handling) - but unlike react/dayplan,
     * neither call has a rule-authored fallback that "consumes" the trigger that asked for it
     * (greeting on the resident's behalf, marking today's plan unavailable). Without something to stop
     * asking, the exact same unsupported request would win reserve()'s priority race again on every
     * single tick forever, since nothing about the resident's state changed - starving every ordinary
     * decision and conversation turn in town, which is precisely what item 6 forbids. Learning "this
     * mind doesn't do that" once and never asking again is the fix that needs no change to
     * ResidentSimulation.java's queue-draining logic at all.
     */
    private final java.util.concurrent.atomic.AtomicBoolean explainUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean reflectUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private void run(long userId){
        final Work[] job={null};
        try {
            store.update(userId,null,w->{job[0]=reserve(w,clock.instant());return w;});
            Work work=job[0];if(work==null)return;
            // This call occurs after the reservation transaction committed. There is no open DB lock.
            Object result;
            ResidentMind.Usage usage;
            switch(work.kind()){
                case "turn"->{var r=mind.generateTurnMetered(work.dialogue());result=r.value();usage=r.usage();}
                case "summary"->{var r=mind.summarizeConversationMetered(work.summary());result=r.value();usage=r.usage();}
                case "dayplan"->{var r=mind.planDayMetered(work.dayPlan());result=r.value();usage=r.usage();}
                case "react"->{var r=mind.reactMetered(work.react());result=r.value();usage=r.usage();}
                case "explain"->{var r=mind.explainMetered(work.explain());result=r.value();usage=r.usage();}
                case "reflect"->{var r=mind.reflectMetered(work.reflect());result=r.value();usage=r.usage();}
                default->{var r=mind.decideMetered(work.context());result=r.value();usage=r.usage();}
            }
            // Tokens were already spent whether or not the reply below still applies to a fresher world.
            if(usage!=null)recordUsage(userId,work.day(),work.kind(),usage);
            store.update(userId,null,w->{
                if(!w.id.equals(work.worldId()))return w;
                w.modelConsecutiveFailures=0;w.modelRetryAfter=null;
                boolean applied;
                String outcomeAction=null;
                if(work.kind().equals("turn")) {
                    var utterance=(ConversationLifecycle.Utterance)result;
                    outcomeAction=utterance==null?null:utterance.stance();
                    applied=utterance!=null&&evidenceWithin(utterance.evidenceIds(),work.context().memories())&&ConversationLifecycle.applyTurn(w,work.operation(),utterance,clock.instant());
                    if(!applied)ConversationLifecycle.failTurn(w,work.operation(),clock.instant());
                } else if(work.kind().equals("summary")) {
                    var summary=(ConversationLifecycle.Recollection)result;
                    applied=summary!=null&&evidenceWithin(summary.evidenceIds(),work.summary().conversationMemories())&&ConversationLifecycle.applySummary(w,work.operation(),summary,clock.instant());
                    if(!applied)ConversationLifecycle.failSummary(w,work.operation(),clock.instant());
                } else if(work.kind().equals("react")) {
                    var draft=(ResidentMind.ReactDraft)result;
                    outcomeAction=draft==null?null:draft.reaction();
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.context().memories())
                        &&ResidentSimulation.applyReaction(w,work.react().pendingId(),work.residentRevision(),draft.reaction(),draft.reason(),
                            draft.evidenceIds()==null?List.of():draft.evidenceIds(),clock.instant());
                } else if(work.kind().equals("dayplan")) {
                    var draft=(ResidentMind.DayPlanDraft)result;
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.context().memories())
                        &&ResidentSimulation.applyDayPlan(w,work.context().residentId(),work.residentRevision(),draft.segments(),draft.evidenceIds(),clock.instant());
                } else if(work.kind().equals("explain")) {
                    var draft=(ResidentMind.ExplainDraft)result;
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.context().memories())
                        &&ResidentSimulation.applyExplanation(w,work.context().residentId(),work.residentRevision(),draft.deedIds(),draft.text(),draft.evidenceIds(),clock.instant());
                } else if(work.kind().equals("reflect")) {
                    var draft=(ResidentMind.ReflectDraft)result;
                    // Evidence is checked against reflectionSource(...) - the open browse this call
                    // actually offered the model - never against work.context().memories(), which is
                    // a different, retrieval-query-shaped slice of this resident's memory built for
                    // ordinary decisions and would wrongly reject perfectly real evidence.
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.reflect().source())
                        &&ResidentSimulation.applyReflection(w,work.context().residentId(),work.residentRevision(),draft.text(),draft.evidenceIds(),draft.supersedesKey(),clock.instant());
                } else {
                    var decision=(ResidentMind.Decision)result;
                    outcomeAction=decision==null?null:decision.action();
                    applied=applyDecision(w,work,decision);if(applied)rememberDecisionSignal(w,work.context().residentId(),clock.instant());
                }
                if(!applied){w.modelStatus="刚才的念头已经过时，继续眼前的生活";w.revision++;}
                outcomeListener.onOutcome(work.kind(),outcomeAction,applied?"applied":"rejected");
                return w;
            });
        } catch(Exception e){
            log.warn("Companion resident model fallback: {}",safeFailure(e));
            if(job[0]!=null)try{store.update(userId,null,w->{
                if(!w.id.equals(job[0].worldId()))return w;
                // A ResidentMind that simply does not implement day planning (the interface's own
                // default throws UnsupportedOperationException - see RoutingResidentMind, which does
                // not yet route "dayplan" calls to either provider) is a missing capability, not a
                // real failure: it must never consume the shared model-failure backoff budget, or one
                // resident's unsupported morning day-plan request would silently starve every other
                // resident's ordinary decisions and every conversation turn for the whole retry window.
                // A mind with no opinion about encounters must not make the town silent: fall back to
                // the sociable answer, the same one a rule-only world uses, rather than dropping the
                // moment. Not a real failure, so it never touches the backoff budget.
                if(job[0].kind().equals("react")&&e instanceof UnsupportedOperationException){
                    w.modelCallsToday=Math.max(0,w.modelCallsToday-1);
                    ResidentSimulation.greetWithoutDeciding(w,job[0].react().pendingId(),clock.instant());
                    outcomeListener.onOutcome("react",null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("dayplan")&&e instanceof UnsupportedOperationException){
                    w.modelCallsToday=Math.max(0,w.modelCallsToday-1);
                    ResidentSimulation.markDayPlanUnavailableForToday(w,job[0].context().residentId(),clock.instant());
                    outcomeListener.onOutcome("dayplan",null,"unsupported");
                    return w;
                }
                // Explain/reflect have no rule-authored fallback that drains their own trigger (unlike
                // react's greetWithoutDeciding or dayplan's markDayPlanUnavailableForToday), so a
                // missing capability is instead recorded once on this director (see explainUnavailable's
                // own doc comment above) rather than left to lose the exact same priority race on every
                // future tick - neither touches the shared failure backoff either way.
                if(job[0].kind().equals("explain")&&e instanceof UnsupportedOperationException){
                    w.modelCallsToday=Math.max(0,w.modelCallsToday-1);
                    explainUnavailable.set(true);
                    outcomeListener.onOutcome("explain",null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("reflect")&&e instanceof UnsupportedOperationException){
                    w.modelCallsToday=Math.max(0,w.modelCallsToday-1);
                    reflectUnavailable.set(true);
                    outcomeListener.onOutcome("reflect",null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("turn"))ConversationLifecycle.failTurn(w,job[0].operation(),clock.instant());
                if(job[0].kind().equals("summary"))ConversationLifecycle.failSummary(w,job[0].operation(),clock.instant());
                w.modelCallsToday=Math.max(0,w.modelCallsToday-1);w.modelFailuresToday++;w.modelConsecutiveFailures++;
                long delay=Math.min(600,75L*(1L<<Math.min(3,w.modelConsecutiveFailures-1)));
                w.modelRetryAfter=clock.instant().plusSeconds(delay);w.modelStatus="暂时按自己的习惯生活，稍后再想新主意";w.revision++;
                outcomeListener.onOutcome(job[0].kind(),null,"failed");
                return w;
            });}catch(Exception ignored){/* A deleted world is never recreated by a late result. */}
        } finally {inFlight.remove(userId);}
    }
    private Work reserve(CompanionWorld w,Instant now) {
        if(w.modelRetryAfter!=null&&now.isBefore(w.modelRetryAfter))return null;
        String day=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
        if(!Objects.equals(w.modelBudgetDay,day)){w.modelBudgetDay=day;w.modelCallsToday=0;w.modelFailuresToday=0;w.modelConsecutiveFailures=0;}
        if(w.modelCallsToday>=dailyBudget||w.modelFailuresToday>=32)return null;
        for(Conversation c:w.conversations)if("model".equals(c.mode)&&"active".equals(c.status)) {
            var operation=ConversationLifecycle.reserveTurn(w,c,now);if(operation==null)continue;
            var context=perspective(w,operation.speakerId(),now,c.turns);
            Project topic=ResidentSimulation.project(w,c.topicId);
            String topicTitle=topic==null?"眼前的生活和工作":topic.title;
            var request=new ResidentMind.DialogueRequest(context,c.id,c.turnVersion,operation.operationId(),partnerName(w,c,operation.speakerId()),topicTitle);
            return reserved(w,now,new Work("turn",w.id,ResidentSimulation.state(w,operation.speakerId()).revision,w.intentRevision,now,context,operation,request,null,w.modelSequence+1,day,null,null,null,null));
        }
        for(Conversation c:w.conversations)if("model".equals(c.mode)&&"ended".equals(c.status)&&!c.turns.isEmpty()) {
            for(String speaker:c.participantIds){
                var operation=ConversationLifecycle.reserveSummary(w,c,speaker,now);if(operation==null)continue;
                var context=perspective(w,speaker,now,List.of());
                var ids=c.turnMemoryIds.getOrDefault(speaker,List.of());
                var memories=w.memories.stream().filter(m->m.ownerId().equals(speaker)&&ids.contains(m.id())).toList();
                var request=new ResidentMind.SummaryRequest(context,c.id,partnerName(w,c,speaker),new ArrayList<>(c.turns),ResidentMind.memoryViews(memories));
                return reserved(w,now,new Work("summary",w.id,ResidentSimulation.state(w,speaker).revision,w.intentRevision,now,context,operation,null,request,w.modelSequence+1,day,null,null,null,null));
            }
        }
        // A person standing in front of you outranks re-picking what to do with your afternoon: the
        // moment passes (see PENDING_ENCOUNTER_TTL_SECONDS) while an ordinary decision keeps. Placed
        // below dialogue turns so an encounter can never interrupt a conversation already underway,
        // and deliberately NOT subject to the per-resident decision cooldown - the cooldown paces a
        // resident's own restlessness, not their answer to something that just happened to them.
        for(CompanionWorld.PendingEncounter pending:new ArrayList<>(w.pendingEncounters)){
            ResidentState r=ResidentSimulation.state(w,pending.residentId);
            if(r==null||r.revision!=pending.residentRevision)continue;
            if(r.id.equals("self")&&!ResidentSimulation.selfIsFree(w))continue;
            if(ResidentSimulation.activeConversation(w,r.id)!=null||ResidentSimulation.activeConversation(w,pending.otherId)!=null)continue;
            Actor other=ResidentSimulation.actor(w,pending.otherId);
            var context=perspective(w,r.id,now,List.of());
            var request=new ResidentMind.ReactRequest(context,pending.id,other.id(),other.name(),other.activity(),pending.place);
            return reserved(w,now,new Work("react",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,request,null,null));
        }
        // Accounting for one's own recent unaccounted-for behaviour (explain): a person who is about
        // to re-decide what to do next should first know what they have just been doing - the account
        // is what argues for the next choice, not the other way round. Placed above ordinary decision
        // dispatch for exactly that reason, but still below a live conversation turn/summary or a
        // face-to-face encounter, none of which this should ever delay. Skipped entirely once this
        // mind has already shown (via UnsupportedOperationException) that it does not implement
        // explain - see explainUnavailable's own doc comment on why that guard exists at all.
        if(!explainUnavailable.get())for(ResidentState r:w.residentStates){
            if(!ResidentSimulation.needsExplanation(w,r.id,now))continue;
            var context=perspective(w,r.id,now,List.of());
            var deeds=ResidentSimulation.unexplainedDeeds(w,r.id);
            var request=new ResidentMind.ExplainRequest(context,ResidentMind.deedViews(deeds));
            return reserved(w,now,new Work("explain",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,request,null));
        }
        // Per-resident decision throttling (item 1): replaces the old world-global modelRequestedAt
        // gate below candidates so each resident thinks on their own clock - the town's decision
        // throughput is now bounded only by the shared single-flight (see AcceleratedTownRunner's
        // javadoc on why that guarantee exists and must not be weakened) and each resident's own
        // cooldown, not by a single shared timer round-robining across everyone.
        var candidates=w.residentStates.stream()
            // The avatar ("self") only ever joins this pool during its own free/autonomous time (item
            // 7, see ResidentSimulation.selfIsFree) - never while the user is explicitly directing it.
            .filter(r->(!r.id.equals("self")||ResidentSimulation.selfIsFree(w))&&ResidentSimulation.activeConversation(w,r.id)==null)
            .filter(r->r.lastDecisionRequestedAt==null||Duration.between(r.lastDecisionRequestedAt,now).getSeconds()>=decisionThrottleSeconds)
            .filter(r->needsDecision(w,r,now)).toList();
        // Legacy rule worlds still allow plan decisions while talking, but their text is not a model turn.
        if(candidates.isEmpty()&&!w.modelConversationsEnabled)candidates=w.residentStates.stream().filter(r->!r.id.equals("self")&&r.plan!=null&&!Set.of("travel","sleep").contains(r.plan.action())).toList();
        if(!candidates.isEmpty()) {
            // Whoever has waited longest gets the turn. This used to be
            // candidates.get(modelSequence % candidates.size()), which only round-robins fairly when
            // the list is the same length every time - and it never is, because the list is filtered
            // by who currently needs a decision. A resident whose chosen action ended quickly rejoined
            // the pool immediately and, with the pool usually one or two people deep, kept landing on
            // the same index. In a measured day one resident took 123 of the town's 300 decisions that
            // way. Sorting by each resident's own last-asked time is stable under a changing pool: the
            // one who has gone longest without thinking is by definition not the one who just thought.
            ResidentState r=candidates.stream()
                .min(Comparator.comparing((ResidentState c)->c.lastDecisionRequestedAt,Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
            var conversation=ResidentSimulation.activeConversation(w,r.id);
            var context=perspective(w,r.id,now,conversation==null?List.of():conversation.turns);
            ResidentSimulation.recordDecisionTrigger(w,r.id,classifyTrigger(w,r,now),now);
            return reserved(w,now,new Work("decision",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,null,null));
        }
        // Recursive day plan (item 4): one call per resident per local morning. Checked only once no
        // ordinary decision is needed anywhere in town - a ResidentMind that does not implement day
        // planning (the interface default throws UnsupportedOperationException; see run()'s catch
        // handling for exactly that case) must never be able to starve ordinary life by winning this
        // race every time. Gated the same way self's own decision candidacy is (see
        // ResidentSimulation.selfIsFree) so this never reaches into the avatar while the user is
        // explicitly directing it. No longer the lowest priority - see reflect below, which reasons
        // about a resident's own past rather than anything that changes what they do today or next.
        for(ResidentState r:w.residentStates){
            if(r.id.equals("self")&&!ResidentSimulation.selfIsFree(w))continue;
            if(ResidentSimulation.activeConversation(w,r.id)!=null)continue;
            if(!needsDayPlan(w,r,now))continue;
            var context=perspective(w,r.id,now,List.of());
            return reserved(w,now,new Work("dayplan",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,new ResidentMind.DayPlanRequest(context),null,null,null));
        }
        // Reflection: genuinely the LOWEST priority of everything in reserve(). Generalizing about the
        // past must never come ahead of anything that changes what a resident does next - not a
        // conversation, not an encounter, not an ordinary decision, not even the coarse morning day
        // plan - so it is checked dead last, only once nothing else in the whole town needs the model
        // at all. Skipped once this mind has already shown it does not implement reflect, for the same
        // starvation reason explainUnavailable exists (see its own doc comment) - though because this
        // tier is already the lowest, the risk here is smaller than explain's.
        if(!reflectUnavailable.get())for(ResidentState r:w.residentStates){
            if(ResidentSimulation.activeConversation(w,r.id)!=null)continue;
            if(!ResidentSimulation.needsReflection(w,r.id,now))continue;
            var context=perspective(w,r.id,now,List.of());
            var source=ResidentSimulation.reflectionSource(w,r.id,now);
            var request=new ResidentMind.ReflectRequest(context,ResidentMind.memoryViews(source),
                ResidentSimulation.habitTraits(r.id).stream()
                    .map(t->new ResidentMind.HabitTraitView(t.key(),t.description())).toList());
            return reserved(w,now,new Work("reflect",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,null,request));
        }
        return null;
    }
    private boolean needsDayPlan(CompanionWorld w,ResidentState r,Instant now){
        ZonedDateTime local=now.atZone(ZoneId.of(w.timezone));
        if(local.getHour()<5||local.getHour()>=11)return false;
        String today=local.toLocalDate().toString();
        return r.dayPlan==null||!today.equals(r.dayPlan.day);
    }
    /** Diagnostic-only classification of why this decision is actually being dispatched (item 6),
     * checked in the same precedence order a person would reason about the cause: an ended/never-
     * started plan first, then whether something is genuinely paused waiting to resume, then a
     * perceivable body signal, then a routine/time anchor, then a perceivable environment change, and
     * finally unexplained low-frequency drift when nothing else changed. Recorded onto
     * {@link CompanionWorld#decisionTriggers} purely for later export/inspection - never read by any
     * model and never gates whether the decision itself is allowed to happen. */
    private static String classifyTrigger(CompanionWorld w,ResidentState r,Instant now){
        if(r.plan==null)return r.suspendedAction!=null?"interrupted":"plan_ended";
        if(!ResidentSimulation.salientPerceptions(w,r.id,now).isEmpty())return "body";
        if(!ResidentSimulation.routineCues(w,r.id,now).isEmpty()||(w.period!=null&&!w.period.equals(r.periodAtLastDecision)))return "time_anchor";
        if(ResidentSimulation.pausedAction(w,r.id,now)!=null||ResidentSimulation.portableAction(w,r.id,now)!=null)return "environment";
        String cue=ResidentSimulation.cafeScheduleCue(w,r.id,now);if(cue!=null)return "environment";
        if(ResidentSimulation.mayTend(w,r.id)&&w.serviceRequests.stream().anyMatch(x->"waiting".equals(x.status)&&Objects.equals(x.place,ResidentSimulation.actor(w,r.id).place())))return "environment";
        if(ResidentSimulation.driftDue(w,r,now))return "drift";
        return "environment";
    }
    private boolean needsDecision(CompanionWorld w,ResidentState r,Instant now){
        if(r.plan==null)return true;
        if(Set.of("travel","sleep").contains(r.plan.action()))return false;
        String fingerprint=decisionFingerprint(w,r.id,now);String key=decisionSignalKey(w,r.id);
        boolean changed=!fingerprint.isBlank()&&!Objects.equals(consideredDecisionSignals.get(key),fingerprint);
        if(fingerprint.isBlank())consideredDecisionSignals.remove(key);
        // A broad time anchor (item 5): the day's own morning/afternoon/evening/night boundary, kept
        // separate from the fingerprint above so a totally quiet plan with nothing else to report can
        // still be reconsidered as the day moves on, without disturbing the existing "blank fingerprint
        // means nothing to report yet" short-circuit the fingerprint itself relies on.
        boolean periodChanged=w.period!=null&&!w.period.equals(r.periodAtLastDecision);
        // Low-frequency, purely time-derived "走神" (item 5): even when nothing perceivable changed,
        // a resident may occasionally reconsider what they are doing for no external reason at all -
        // see ResidentSimulation.driftDue's own javadoc for the rate and why it stays deterministic.
        return changed||periodChanged||ResidentSimulation.driftDue(w,r,now);
    }
    private void rememberDecisionSignal(CompanionWorld w,String residentId,Instant now){
        String fingerprint=decisionFingerprint(w,residentId,now);String key=decisionSignalKey(w,residentId);
        if(fingerprint.isBlank())consideredDecisionSignals.remove(key);else consideredDecisionSignals.put(key,fingerprint);
        ResidentState r=ResidentSimulation.state(w,residentId);if(r!=null)r.periodAtLastDecision=w.period;
    }
    private static String decisionSignalKey(CompanionWorld w,String residentId){return w.id+":"+residentId;}
    private static String decisionFingerprint(CompanionWorld w,String residentId,Instant now){
        ResidentState r=ResidentSimulation.state(w,residentId);if(r==null)return "";
        var signals=new ArrayList<String>(ResidentSimulation.salientPerceptions(w,residentId,now));
        ResidentSimulation.routineCues(w,residentId,now).forEach(cue->signals.add("routine:"+cue));
        String cue=ResidentSimulation.cafeScheduleCue(w,residentId,now);if(cue!=null)signals.add("schedule:"+cue);
        var paused=ResidentSimulation.pausedAction(w,residentId,now);if(paused!=null)signals.add("paused:"+paused.action()+":"+paused.place()+":"+paused.reason());
        var portable=ResidentSimulation.portableAction(w,residentId,now);if(portable!=null)signals.add("portable:"+portable.action()+":"+portable.reason());
        if(ResidentSimulation.mayTend(w,residentId))signals.addAll(w.serviceRequests.stream().filter(request->"waiting".equals(request.status)&&Objects.equals(request.place,ResidentSimulation.actor(w,residentId).place())).map(request->"request:"+request.id).sorted().toList());
        if(signals.isEmpty())return "";
        String plan=r.plan==null?"none":r.plan.id()+":"+r.plan.action()+":"+r.plan.place()+":"+r.plan.targetId()+":"+r.plan.endsAt();
        return plan+"|"+w.cafeStatus+"|"+String.join("|",signals);
    }
    private Work reserved(CompanionWorld w,Instant now,Work work){
        w.modelRequestedAt=now;w.modelCallsToday++;w.modelSequence++;
        // Per-resident decision cooldown (item 1): only stamped for an actual re-decision, never for a
        // conversation turn/summary/day-plan dispatch, which have their own reservation timing.
        if(work.kind().equals("decision")){ResidentState r=ResidentSimulation.state(w,work.context().residentId());if(r!=null)r.lastDecisionRequestedAt=now;}
        w.modelStatus=work.kind().equals("turn")?""+work.context().self().name()+"正在想怎么接这句话":work.kind().equals("summary")?"有人在回想刚才的谈话":work.kind().equals("dayplan")?""+work.context().self().name()+"在想今天大致怎么过":work.kind().equals("explain")?""+work.context().self().name()+"在回想刚才做了什么":work.kind().equals("reflect")?""+work.context().self().name()+"在想些什么":"有位居民正在想下一步";
        w.revision++;return work;
    }
    ResidentMind.Context perspective(CompanionWorld w,String residentId,Instant now,List<Turn> transcript){
        var r=ResidentSimulation.state(w,residentId);Actor self=ResidentSimulation.actor(w,r.id);
        // The avatar is present in the world the same way any resident is: if it is standing in this
        // place (and not mid-walk), it shows up here too. Its label/name only ever carry the fixed,
        // pre-written phrases from CompanionRules - never anything the user typed.
        var visible=new ArrayList<Actor>(w.residents);if(w.avatar!=null)visible.add(w.avatar);
        var nearby=visible.stream().filter(a->!a.id().equals(r.id)&&a.place().equals(self.place())&&!a.activity().equals("walk")).toList();
        String intentText=r.lifeIntent==null?"":r.lifeIntent.purpose;
        String planText=r.plan==null?"":r.plan.reason();
        String people=nearby.stream().map(Actor::name).reduce("",(a,b)->a+" "+b);
        // Retrieval follows the unfinished thread and the people actually in front of this resident,
        // rather than treating the current public project as their entire reason to remember.
        var memories=CompanionRecall.retrieve(w.memories,r.id,intentText+" "+planText+" "+people,now,10);
        var known=w.projects.stream().filter(p->ResidentSimulation.knows(w,r.id,p.id)).map(p->{
            var view=r.knownProjects.get(p.id);
            String startedBy=p.ownerId==null||p.ownerId.equals(r.id)?null:ResidentSimulation.actor(w,p.ownerId).name();
            return new ResidentMind.KnownProject(p.id,p.title,ResidentSimulation.knownPlace(r,p),
                projectStage(view==null?null:view.status(),view==null?0:view.progress(),ResidentSimulation.takesMoreThanOnePerson(p)),startedBy);
        }).toList();
        var knownPlaces=knownPlaces();var cafeRoleFacts=cafeRoleFacts(w,r.id);
        var arrangements=w.workArrangements.stream().filter(a->Set.of("proposed","active").contains(a.status))
            .filter(a->r.id.equals(a.proposerId)||r.id.equals(a.workerId)||r.id.equals(w.cafeOperatorId))
            .map(a->new ResidentMind.WorkArrangementView(a.id,a.kind,a.place,a.proposerId,a.workerId,a.status,a.note,instant(a.proposedAt),instant(a.acceptedAt),instant(a.endedAt))).toList();
        var requests=w.serviceRequests.stream().filter(x->x.place.equals(self.place())&&Set.of("waiting","preparing","delivered").contains(x.status))
            .map(x->new ResidentMind.ServiceRequestView(x.id,x.requesterId,x.kind,x.status,x.place)).toList();
        var perceptions=new ArrayList<>(ResidentSimulation.salientPerceptions(w,r.id,now));
        for(Actor person:nearby){int relation=r.relationships.getOrDefault(person.id(),40);if(relation>=80)perceptions.add("看到"+person.name()+"时，我自然会多一分亲近和信任。");else if(relation<=20)perceptions.add(person.name()+"在场时，我会有些戒备。");}
        var paused=ResidentSimulation.pausedAction(w,r.id,now);var portable=ResidentSimulation.portableAction(w,r.id,now);
        var peopleHere=nearby.stream().filter(person->!person.id().equals("self")||ResidentSimulation.selfIsFree(w))
            .map(person->new ResidentMind.PersonHereView(person.id(),person.name(),closeness(r.relationships.getOrDefault(person.id(),40)),
                memories.stream().filter(m->m.text()!=null&&m.text().contains(person.name())||person.id().equals(m.sourceId())).map(Memory::id).toList())).toList();
        return new ResidentMind.Context(r.id,now.atZone(ZoneId.of(w.timezone)).toLocalTime().toString(),w.weather,ResidentMind.actorView(self),r.goal,List.copyOf(perceptions),ResidentSimulation.routineCues(w,r.id,now),ResidentMind.memoryViews(memories),ResidentMind.actorViews(nearby),ResidentMind.objectViews(w.objects.stream().filter(o->o.place().equals(self.place())).toList()),peopleHere,knownPlaces,known,ResidentMind.turnViews(transcript),intentView(r.lifeIntent),intentView(r.careerIntent),ResidentMind.planView(r.plan,now),arrangements,r.occupation,personaView(r.id),ResidentSimulation.availableActions(w,r.id,now),ResidentSimulation.cafeOperatorId(w),cafeRoleFacts,ResidentSimulation.mayTend(w,r.id),requests,w.cafeStatus,ResidentSimulation.cafeScheduleCue(w,r.id,now),ResidentSimulation.cafeNotice(w,r.id),paused==null?null:new ResidentMind.PausedActionView(paused.action(),TownPlaces.isHome(paused.place())?"home":paused.place(),paused.reason(),paused.remainingSeconds()),portable==null?null:new ResidentMind.PortableActionView(portable.action(),portable.reason(),portable.remainingSeconds()));
    }
    /** Copies {@link ResidentSeed#narrative} straight into {@code ResidentMind.Context} - text only,
     * never a gate: {@code null} for the avatar ("self") and any resident this batch never authored
     * text for, exactly as {@link ResidentSeed#narrative} itself returns. Recomputed on every call
     * rather than cached on {@link ResidentState}, because the mapping is static per resident id and
     * never changes within a world, so there is nothing here that would need self-healing. */
    /** Public so a live model probe can build the same persona the production context carries,
     * rather than a hand-rolled copy that drifts from it. Pure lookup, no world state. */
    public static ResidentMind.PersonaView personaView(String residentId){
        var narrative=ResidentSeed.narrative(residentId);
        return narrative==null?null:new ResidentMind.PersonaView(narrative.wantSelf(),narrative.oughtSelf(),narrative.actingSelf(),narrative.memoryBias(),narrative.looseningNote());
    }
    private static ResidentMind.LifeIntentView intentView(LifeIntent intent){return intent==null?null:new ResidentMind.LifeIntentView(intent.id,intent.goalId,intent.purpose,intent.status,instant(intent.formedAt),instant(intent.updatedAt),instant(intent.lastActedAt));}
    /** The relationship number turned into the kind of thing a person would actually say to
     * themselves. The number itself never leaves the rules engine (see docs/04: internal values must
     * not enter a model context) - Humanoid Agents does the same with its own closeness score,
     * rendering it as a phrase like "John Lin is feeling close to Eddy Lin" rather than a value. */
    private static String closeness(int relation){
        if(relation>=80)return "很亲近，看到就放松";
        if(relation>=60)return "熟，处得来";
        if(relation>=35)return "认识，谈不上深";
        if(relation>=20)return "有点生分";
        return "见了会有些戒备";
    }
    private static String instant(Instant value){return value==null?null:value.toString();}
    /** How far along a project is, in the words the resident who knows it would use. The middle case
     * is the one that matters and the one that used to be missing: a project that takes more than one
     * person and has reached {@link ResidentSimulation#SOLO_PROGRESS_CAP} has not "大体完成" - it has
     * stopped, and it will stay stopped forever unless somebody else puts their hands on it. Mapping
     * that state to a cheerful "进行中" told every resident in town that the thing was moving along
     * fine without them, which is the single reason a measured day finished nothing. */
    private static String projectStage(String status,int progress,boolean takesMoreThanOnePerson){
        // Null status means this resident knows OF the thing without having seen how it is getting
        // on - hearsay off the noticeboard, say. Set.of(...).contains(null) throws, and this only
        // never blew up because "knows" used to be true, by accident, exactly when knownProjects
        // also had an entry.
        if(status!=null&&Set.of("ready","celebrating").contains(status))return "已经完成";
        if(takesMoreThanOnePerson&&progress>=ResidentSimulation.SOLO_PROGRESS_CAP)return "一个人能做的都做完了，剩下的得有人一起动手才动得了";
        return progress<=0?"刚开始":progress<45?"做了一些":progress<80?"进行中":"大体完成";
    }
    private static List<ResidentMind.KnownPlaceView> knownPlaces(){return List.of(
        new ResidentMind.KnownPlaceView("home","自己的住处，有床和个人书桌；适合睡觉、休息，也能继续读写或制作。",List.of("sleep","rest","study","read","work","make")),
        // What someone standing at the door would think of, in the order they would think of it. The
        // earlier wording described the cafe almost entirely as a workspace, which is a fair account of
        // the seating and a poor account of what a cafe is for: across a whole simulated day nobody
        // ordered anything even once. A place has to advertise what it is good for before anyone can
        // choose it for that.
        new ResidentMind.KnownPlaceView("cafe","营业时可进入的公共室内空间：可以点杯喝的坐一会儿，可以约人在这里碰面、拼个桌一起聊，也可以安静读书、学习、写作、制作。共享讨论桌上摆着还没做完的共同的事，谁都可以坐下添一笔，不必是起头的那个人。有六个独立窗边座位和一张共享讨论桌。吧台设备需要经营或帮工权限。",List.of("observe","rest","study","read","work","make","create","help","request_drink","invite","join")),
        new ResidentMind.KnownPlaceView("street","连接住处、咖啡馆和花园的小街，适合散步、观察和偶遇。",List.of("observe")),
        new ResidentMind.KnownPlaceView("garden","公共花园，适合观察植物、照料花草或做与植物有关的事，也可以动手做在这里落地的共同的事。",List.of("observe","work","create","help")));
    }
    private static List<String> cafeRoleFacts(CompanionWorld w,String residentId){
        List<String> facts=new ArrayList<>();String operator=ResidentSimulation.cafeOperatorId(w);
        if(Objects.equals(operator,residentId)){facts.add("我是咖啡馆当前经营者，经营权和吧台设备责任仍在我这里。");facts.add("我熟悉咖啡馆、吧台和日常开关门方式。");if(!w.cafeOperating)facts.add("closing".equals(w.cafeStatus)?"我之前暂停了经营，咖啡馆正在收店。":"我之前暂停了经营，咖啡馆目前已经关门。");}
        w.workArrangements.stream().filter(a->"active".equals(a.status)&&residentId.equals(a.workerId)).findFirst().ifPresent(a->facts.add(switch(a.kind){case "takeover"->"我通过双方确认的接手安排成为了当前经营者。";case "delegate"->"我正在受托照看咖啡馆。";default->"我有一份仍生效的咖啡馆帮工安排。";}));
        return List.copyOf(facts);
    }
    private boolean applyDecision(CompanionWorld w,Work work,ResidentMind.Decision decision){
        var c=work.context();if(w.modelSequence!=work.sequence()||Duration.between(work.at(),clock.instant()).getSeconds()>90)return false;
        boolean valid=decision!=null&&decision.action()!=null&&c.availableActions().contains(decision.action())&&decision.place()!=null&&decision.evidenceIds()!=null&&evidenceWithin(decision.evidenceIds(),c.memories())&&(!"propose".equals(decision.action())||!decision.evidenceIds().isEmpty());
        return valid&&(decision.action().equals("propose")
            ?ResidentSimulation.proposeDecision(w,c.residentId(),work.residentRevision(),work.intentRevision(),decision.place(),decision.projectTitle(),decision.objectKind(),decision.reason(),decision.evidenceIds(),clock.instant())
            :ResidentSimulation.applyDecision(w,c.residentId(),work.residentRevision(),work.intentRevision(),decision.place(),decision.action(),decision.targetId(),decision.reason(),decision.speech(),decision.evidenceIds(),clock.instant()));
    }
    // Tags usage with which provider actually served the call (see ResidentMind.Usage/ModelUsageRecorder's
    // provider-aware overload) so spend can be broken down per supplier, not just per call type. A null
    // provider (any ResidentMind that predates routing, or a call that was never attributed) falls back
    // to the plain, untagged record path automatically - see ModelUsageQuery.encodeCallType.
    private void recordUsage(long userId,String day,String callType,ResidentMind.Usage usage){
        try{usageRecorder.record(userId,day,callType,usage.provider(),usage.inputTokens(),usage.outputTokens());}
        catch(Exception e){log.warn("Companion model usage recording failed: {}",safeFailure(e));}
    }
    private static boolean evidenceWithin(List<String> ids,List<ResidentMind.MemoryView> memories){return ids!=null&&ids.stream().allMatch(id->memories.stream().anyMatch(m->m.id().equals(id)));}
    private static String partnerName(CompanionWorld w,Conversation c,String speaker){return ResidentSimulation.actor(w,c.participantIds.stream().filter(id->!id.equals(speaker)).findFirst().orElseThrow()).name();}
    private static String safeFailure(Throwable failure){List<String> codes=new ArrayList<>();for(int i=0;failure!=null&&i<5;i++,failure=failure.getCause())codes.add(failure instanceof com.betterself.growth.shared.api.ApiException api?api.getClass().getSimpleName()+":"+api.code():failure.getClass().getSimpleName());return String.join(" -> ",codes);}
    @PreDestroy void close(){executor.shutdownNow();}
}
