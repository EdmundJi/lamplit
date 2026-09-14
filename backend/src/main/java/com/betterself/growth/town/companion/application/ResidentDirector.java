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
    /** A provider may have a longer socket timeout, but an answer built from an old town snapshot is
     * no longer a resident's answer to the scene now in front of them. Decisions already had this
     * guard; keeping it here makes the same expiry rule cover every model call kind. */
    private static final long MODEL_RESULT_MAX_AGE_SECONDS=90;
    private final WorldStore store;
    private final ResidentMind mind;
    private final Clock clock;
    private final int dailyBudget;
    private final long decisionThrottleSeconds;
    /** Upper bound on how many residents of ONE world may have a model call outstanding at once
     * (docs/04-decisions.md 「只并行"想"，写世界仍串行」): a network round trip is ~4s and a world write is
     * microseconds, so 25 residents x 2 days x 4s (7500 calls, 8+ hours if fully serial) is bottlenecked
     * entirely on the model call, never the write - every write still happens inside one short
     * {@code store.update} transaction, exactly as before this change. See {@link #thinking} below for
     * how one resident is still kept to at most one call in flight even while several others think. */
    private final int parallelism;
    private final ModelUsageRecorder usageRecorder;
    /** Fires exactly once per dispatched model call, right where {@code applied} (or its
     * failure/unsupported-capability equivalent) is actually decided - never a guess reconstructed
     * later from {@link CompanionWorld#modelStatus} text, which only a handful of the apply* paths
     * ever touch on success (see the accelerated runner's own audit of this). Default is a no-op so
     * production wiring (which has no listener) pays nothing; test/offline harnesses (see
     * AcceleratedTownRunner) attach one to build an exact offered/selected/applied/rejected account
     * per call type and action, closing the observability gap a purely tick-sampled export cannot.
     * <p>{@code residentId} is who the call was for, and it is what makes a listener able to match an
     * outcome back to the exact request that produced it now that several residents think at once
     * (docs/04-decisions.md 「只并行"想"，写世界仍串行」). It is an exact key rather than a hint: one
     * resident never has two calls outstanding (see {@link #thinking}), so at the moment this fires
     * there is exactly one outstanding call for this id. Matching on arrival order instead - "the row
     * I appended last" - was correct only while the director was single-flight per world. */
    public interface OutcomeListener{void onOutcome(String callType,String residentId,String action,String outcome);}
    private volatile OutcomeListener outcomeListener=(callType,residentId,action,outcome)->{};
    public void setOutcomeListener(OutcomeListener listener){this.outcomeListener=listener==null?(callType,residentId,action,outcome)->{}:listener;}
    /** Bounds how many workers this director has dispatched for one user/world right now - the
     * *optimisation*, not the mutual-exclusion. Replaces the old single {@code inFlight} set now that
     * several residents may think at once (docs/04 「只并行"想"，写世界仍串行」): incremented in {@code
     * consider} before a worker is handed to the executor, decremented in {@code run}'s {@code finally}
     * on every path, so a rejected/crashed dispatch never leaks a permanently "busy" slot. */
    private final Map<Long,java.util.concurrent.atomic.AtomicInteger> workersByUser=new ConcurrentHashMap<>();
    /** One entry per resident that currently has a model call outstanding (world id + resident id, see
     * {@link #thinkingKey}) - this, not {@link #workersByUser}, is what actually stops a second worker
     * from asking the same resident a second question while the first is still in flight. Claimed in
     * {@link #reserved} (which always runs inside {@code store.update}'s own per-user transaction, so
     * claiming is race-free - see that method's own comment), released in {@link #run}'s {@code
     * finally} on every path including an exception or a reservation that found nothing to do. A
     * conflict is still never resolved by locking this set longer or harder: a stale reply is simply
     * discarded, because every {@code ResidentSimulation.apply*}/{@code proposeDecision} already
     * rejects on {@code r.revision!=residentRevision} (plus {@code w.intentRevision} where relevant) -
     * exactly what docs/04's「冲突 = 版本过期 = 丢弃这次结果」means. */
    private final Set<String> thinking=ConcurrentHashMap.newKeySet();
    private static String thinkingKey(String worldId,String residentId){return worldId+'|'+residentId;}
    /** Suppresses repeated model calls for the same already-considered cue while a plan continues.
     * The fingerprint contains qualitative/observable state only; failures are never remembered. */
    private final Map<String,String> consideredDecisionSignals=new ConcurrentHashMap<>();
    // Development-phase defaults: five residents each writing their own memory need real
    // concurrency, and cost is not a constraint right now - see the constructor for the knobs.
    private final ThreadPoolExecutor executor;
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock){this(store,mind,clock,100000);}
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,int dailyBudget){this(store,mind,clock,dailyBudget,(userId,day,callType,inputTokens,outputTokens)->{});}
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,int dailyBudget,ModelUsageRecorder usageRecorder){this(store,mind,clock,dailyBudget,usageRecorder,8,64,12,6);}
    @Autowired
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,
                             @Value("${app.town.companion-model-daily-budget:100000}")int dailyBudget,
                             ModelUsageRecorder usageRecorder,
                             @Value("${app.town.companion-mind-pool-size:8}")int poolSize,
                             @Value("${app.town.companion-mind-queue-size:64}")int queueSize,
                             @Value("${app.town.companion-model-decision-throttle-seconds:12}")long decisionThrottleSeconds,
                             @Value("${app.town.companion-mind-parallelism:6}")int parallelism){
        this.store=store;this.mind=mind;this.clock=clock;this.dailyBudget=Math.max(1,dailyBudget);this.usageRecorder=usageRecorder;
        this.decisionThrottleSeconds=Math.max(0,decisionThrottleSeconds);
        this.parallelism=Math.max(1,parallelism);
        int workers=Math.max(1,poolSize);
        this.executor=new ThreadPoolExecutor(workers,workers,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(Math.max(1,queueSize)),r->{Thread t=new Thread(r,"companion-mind");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean enabled(){return mind.enabled();}
    public void consider(long userId,CompanionWorld snapshot){
        if(!mind.enabled()||snapshot==null||snapshot.simulationVersion<2)return;
        Instant now=clock.instant();
        if(snapshot.modelRetryAfter!=null&&now.isBefore(snapshot.modelRetryAfter))return;
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
        // A raised occasion is a reason to run for exactly the same reason an encounter is: the
        // moment it belongs to is passing, and nothing else about the world has to change for it to
        // deserve an answer.
        boolean occasion=!snapshot.pendingOccasions.isEmpty();
        if(!dialogue&&!anyResidentReady&&!encounter&&!occasion)return;
        dispatch(userId);
    }
    /** Hands exactly ONE worker to the executor, if this world is still under {@link #parallelism}.
     * <p>Parallelism is reached by a chain rather than by a burst: {@link #consider} dispatches one,
     * and a worker that actually reserved something dispatches the next (see {@link #run}). The
     * obvious alternative - have {@code consider} estimate how many jobs are available and dispatch
     * that many at once - was measured against this one and is worse in the case that matters most,
     * the quiet town: the estimate has to count every resident whose decision cooldown is merely
     * clear, which for an idle town is all of them, so every single poll would open {@code
     * parallelism} short {@code store.update} transactions (a {@code select ... for update} row lock
     * each) only for {@code reserve()} to authoritatively find nothing for any of them. A chain pays
     * for a worker only once the previous one has already found real work, so an idle town costs
     * exactly what it cost before this change - one probe - and a busy one still climbs to the full
     * cap within a few milliseconds, which is nothing beside the ~4s model call it is climbing for. */
    private void dispatch(long userId){
        java.util.concurrent.atomic.AtomicInteger workers=workersByUser.computeIfAbsent(userId,id->new java.util.concurrent.atomic.AtomicInteger());
        int slot=workers.incrementAndGet();
        if(slot>parallelism){workers.decrementAndGet();return;}
        // When a world is busy enough to fill every lane, reserve the last lane for bounded
        // cognition work (day plan / due promise / reflection). Otherwise 25 residents with a
        // steady supply of ordinary decisions can keep that work below the queue forever. Passing
        // events still outrank this lane inside reserve(); this only prevents background cognition
        // from having to wait for the entire town to become idle at once.
        boolean backgroundPreferred=parallelism>1&&slot==parallelism;
        try{executor.execute(()->run(userId,backgroundPreferred));}catch(RejectedExecutionException e){workers.decrementAndGet();}
    }
    /** @param sequence diagnostic-only record of which reservation (of {@code w.modelSequence}, which
     * ticks on every reservation in town) this dispatch was - it no longer gates whether the reply may
     * be applied (see {@link #applyDecision}'s own comment; docs/04 「只并行"想"，写世界仍串行」). Kept as a
     * component only because ~11 construction sites already pass {@code w.modelSequence+1} and removing
     * it positionally would be needless churn - a future reader should not read its presence here as an
     * invitation to re-add the equality check. */
    private record Work(String kind,String worldId,long residentRevision,long intentRevision,Instant at,
                        ResidentMind.Context context,ConversationLifecycle.Operation operation,
                        ResidentMind.DialogueRequest dialogue,ResidentMind.SummaryRequest summary,long sequence,String day,
                        ResidentMind.DayPlanRequest dayPlan,ResidentMind.ReactRequest react,
                        ResidentMind.ExplainRequest explain,ResidentMind.ReflectRequest reflect,
                        ResidentMind.VentureRequest venture,ResidentMind.PromiseOfferRequest promiseOffer,
                        ResidentMind.PromiseSettledRequest promiseSettled,ResidentMind.ConsiderRequest consider) {}
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
    /** Same starvation guard, same reason as the three below: an occasion sits in the queue until it
     * is answered or expires, and a mind that cannot answer it would otherwise win the priority race
     * on every tick of its whole TTL. Unlike react there is deliberately no rule-authored fallback -
     * nobody locks the door on a resident's behalf. */
    private final java.util.concurrent.atomic.AtomicBoolean considerUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean dayPlanUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean reflectUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean ventureUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean promiseUnavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
    private void run(long userId,boolean backgroundPreferred){
        final Work[] job={null};
        try {
            store.update(userId,null,w->{job[0]=reserve(w,clock.instant(),backgroundPreferred);return w;});
            Work work=job[0];if(work==null)return;
            // This reservation found real work, so another resident may have work too - hand out the
            // next worker now, before this one spends ~4s on the network (docs/04 「只并行"想"，写世界仍
            // 串行」). This is the whole of the ramp: the chain stops on its own the moment a worker
            // reserves nothing, so a quiet town never pays for a probe it did not need. See dispatch().
            dispatch(userId);
            // This call occurs after the reservation transaction committed. There is no open DB lock.
            Object result;
            ResidentMind.Usage usage;
            switch(work.kind()){
                case "turn"->{var r=mind.generateTurnMetered(work.dialogue());result=r.value();usage=r.usage();}
                case "summary"->{var r=mind.summarizeConversationMetered(work.summary());result=r.value();usage=r.usage();}
                case "dayplan"->{var r=mind.planDayMetered(work.dayPlan());result=r.value();usage=r.usage();}
                case "react"->{var r=mind.reactMetered(work.react());result=r.value();usage=r.usage();}
                case "consider"->{var r=mind.considerMetered(work.consider());result=r.value();usage=r.usage();}
                case "explain"->{var r=mind.explainMetered(work.explain());result=r.value();usage=r.usage();}
                case "reflect"->{var r=mind.reflectMetered(work.reflect());result=r.value();usage=r.usage();}
                case "venture"->{var r=mind.ventureMetered(work.venture());result=r.value();usage=r.usage();}
                case "promise_offer"->{var r=mind.promiseOfferMetered(work.promiseOffer());result=r.value();usage=r.usage();}
                case "promise_settled"->{var r=mind.promiseSettledMetered(work.promiseSettled());result=r.value();usage=r.usage();}
                default->{var r=mind.decideMetered(work.context());result=r.value();usage=r.usage();}
            }
            // Tokens were already spent whether or not the reply below still applies to a fresher world.
            if(usage!=null)recordUsage(userId,work.day(),work.kind(),usage);
            store.update(userId,null,w->{
                if(!w.id.equals(work.worldId()))return w;
                w.modelConsecutiveFailures=0;w.modelRetryAfter=null;
                boolean applied;
                String outcomeAction=null;
                if(resultExpired(work,clock.instant())) {
                    // Conversation reservations have their own operation state which must be released;
                    // the other call kinds are optimistic and leave their still-valid cue available
                    // for a fresh question on a later tick.
                    if(work.kind().equals("turn"))ConversationLifecycle.failTurn(w,work.operation(),clock.instant());
                    if(work.kind().equals("summary"))ConversationLifecycle.failSummary(w,work.operation(),clock.instant());
                    applied=false;
                } else if(work.kind().equals("turn")) {
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
                    // Same as an occasion: an answer we could not use still spends the moment. Left
                    // queued, the pair was asked again every tick of its 90-second life.
                    ResidentSimulation.consumeEncounterWithoutDecision(w,work.react().pendingId());
                } else if(work.kind().equals("consider")) {
                    var draft=(ResidentMind.ConsiderDraft)result;
                    outcomeAction=draft==null?null:draft.choice();
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.context().memories())
                        &&Occasions.apply(w,work.consider().pendingId(),work.residentRevision(),draft.choice(),draft.reason(),
                            draft.speech(),draft.evidenceIds()==null?List.of():draft.evidenceIds(),clock.instant());
                    // The question has been put and answered; an answer we could not use is still this
                    // moment's answer. Left queued, the same resident is re-asked on the very next tick.
                    Occasions.discard(w,work.consider().pendingId());
                } else if(work.kind().equals("dayplan")) {
                    var draft=(ResidentMind.DayPlanDraft)result;
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.context().memories())
                        &&ResidentSimulation.applyDayPlan(w,work.context().residentId(),work.residentRevision(),plannedSegments(draft),draft.evidenceIds(),clock.instant());
                    // Asked once a day, like an occasion: a plan we could not use still leaves the
                    // morning's question answered, or the same resident is re-asked every tick.
                    // A stale revision is not an answer - that resident is simply asked again.
                    ResidentState planner=ResidentSimulation.state(w,work.context().residentId());
                    if(!applied&&planner!=null&&planner.revision==work.residentRevision())ResidentSimulation.markDayPlanUnavailableForToday(w,planner.id,clock.instant());
                } else if(work.kind().equals("explain")) {
                    var draft=(ResidentMind.ExplainDraft)result;
                    applied=draft!=null&&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.context().memories())
                        &&ResidentSimulation.applyExplanation(w,work.context().residentId(),work.residentRevision(),draft.deedIds(),draft.text(),draft.evidenceIds(),clock.instant());
                } else if(work.kind().equals("venture")) {
                    var draft=(ResidentMind.VentureDraft)result;
                    // The question having been put is what the cadence records, not whether anything
                    // came of it: wanting nothing right now is a real answer, and asking again in an
                    // hour would be pestering rather than listening.
                    ResidentSimulation.markVentureAsked(w,work.context().residentId(),clock.instant());
                    applied=draft!=null&&draft.title()!=null&&!draft.title().isBlank()
                        &&draft.evidenceIds()!=null&&evidenceWithin(draft.evidenceIds(),work.context().memories())
                        &&ResidentSimulation.proposeDecision(w,work.context().residentId(),work.residentRevision(),work.intentRevision(),
                            draft.place(),draft.title(),draft.objectKind(),draft.reason(),draft.evidenceIds(),clock.instant());
                } else if(work.kind().equals("promise_offer")) {
                    var draft=(ResidentMind.PromiseOfferDraft)result;
                    // Asked is what the cadence records, exactly as with venture: "nothing I want to fix
                    // a time for" is a real answer, and coming back an hour later to ask again would be
                    // badgering somebody into an obligation rather than listening to them.
                    ResidentSimulation.markPromiseAsked(w,work.context().residentId(),clock.instant());
                    ResidentState current=ResidentSimulation.state(w,work.context().residentId());
                    applied=draft!=null&&current!=null&&current.revision==work.residentRevision()
                        &&draft.evidenceIds()!=null&&evidenceWithin(draft.evidenceIds(),work.context().memories())
                        &&draft.toId()!=null&&work.promiseOffer().peopleHere().stream().anyMatch(person->draft.toId().equals(person.id()))
                        &&draft.what()!=null&&!draft.what().isBlank()&&draft.inHours()!=null&&Double.isFinite(draft.inHours())
                        &&ResidentSimulation.promise(w,work.context().residentId(),draft.toId(),draft.what(),draft.place(),
                            clock.instant().plusSeconds((long)(draft.inHours()*3600)),clock.instant());
                } else if(work.kind().equals("promise_settled")) {
                    var draft=(ResidentMind.PromiseThought)result;
                    // Marked asked whether or not they had anything to say. Having nothing to say about
                    // it is the answer we must never make expensive to give (see the prompt), and the
                    // promise would otherwise come back round for the same person on the next tick.
                    ResidentSimulation.markPromiseThoughtAsked(w,work.promiseSettled().promise().promiseId(),
                        work.context().residentId(),clock.instant());
                    applied=draft!=null&&draft.text()!=null&&!draft.text().isBlank()
                        &&evidenceWithin(draft.evidenceIds()==null?List.of():draft.evidenceIds(),work.promiseSettled().aboutThem())
                        &&ResidentSimulation.applyReflection(w,work.context().residentId(),work.residentRevision(),
                            draft.text(),draft.evidenceIds(),draft.supersedesKey(),clock.instant());
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
                    var selected=ResidentMind.selectedOption(work.context(),decision);
                    outcomeAction=selected==null?(decision==null?null:decision.action()):selected.action();
                    applied=applyDecision(w,work,decision);if(applied)rememberDecisionSignal(w,work.context().residentId(),clock.instant());
                }
                if(work.kind().equals("decision")){
                    ResidentState decider=ResidentSimulation.state(w,work.context().residentId());
                    if(decider!=null)ResidentSimulation.recordDecisionOutcome(decider,applied,clock.instant());
                }
                if(!applied)w.modelStatus="刚才的念头已经过时，继续眼前的生活";
                // Always: JdbcWorldStore writes a world back only when its revision moved, and an
                // accepted answer can change nothing but bookkeeping - a "none" to an occasion just
                // drops the pending question. Without this that drop was never saved and the same
                // residents were re-asked every tick (live: 1,083 consider calls in 17 minutes).
                w.revision++;
                outcomeListener.onOutcome(work.kind(),work.context().residentId(),outcomeAction,applied?"applied":"rejected");
                return w;
            });
        } catch(Exception e){
            log.warn("Companion resident model fallback: {}",safeFailure(e));
            if(job[0]!=null)try{store.update(userId,null,w->{
                if(!w.id.equals(job[0].worldId()))return w;
                // Every branch below changes the world (a refund, a consumed encounter); see the
                // success path on why an unmoved revision would silently drop that change.
                w.revision++;
                // A ResidentMind that simply does not implement day planning (the interface's own
                // default throws UnsupportedOperationException - see RoutingResidentMind, which does
                // not yet route "dayplan" calls to either provider) is a missing capability, not a
                // real failure: it must never consume the shared model-failure backoff budget, or one
                // resident's unsupported morning day-plan request would silently starve every other
                // resident's ordinary decisions and every conversation turn for the whole retry window.
                // A mind with no reaction capability is not a network failure, and the rules cannot
                // turn that absence into a greeting on the resident's behalf. Consume the moment
                // neutrally and leave the shared failure budget untouched.
                if(job[0].kind().equals("react")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    // Missing cognition cannot become a rule-authored social intention. Consume the
                    // passing encounter neutrally: no greeting, dialogue, memory or invented reason.
                    ResidentSimulation.consumeEncounterWithoutDecision(w,job[0].react().pendingId());
                    outcomeListener.onOutcome("react",job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("dayplan")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    dayPlanUnavailable.set(true);
                    ResidentSimulation.markDayPlanUnavailableForToday(w,job[0].context().residentId(),clock.instant());
                    outcomeListener.onOutcome("dayplan",job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                // Explain/reflect have no rule-authored fallback that drains their own trigger (unlike
                // react's greetWithoutDeciding or dayplan's markDayPlanUnavailableForToday), so a
                // missing capability is instead recorded once on this director (see explainUnavailable's
                // own doc comment above) rather than left to lose the exact same priority race on every
                // future tick - neither touches the shared failure backoff either way.
                if(job[0].kind().equals("consider")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    considerUnavailable.set(true);
                    outcomeListener.onOutcome("consider",job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("explain")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    explainUnavailable.set(true);
                    outcomeListener.onOutcome("explain",job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("reflect")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    reflectUnavailable.set(true);
                    outcomeListener.onOutcome("reflect",job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("venture")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    ventureUnavailable.set(true);
                    outcomeListener.onOutcome("venture",job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                if(job[0].kind().startsWith("promise")&&e instanceof UnsupportedOperationException){
                    refundCurrentBudgetDay(w,job[0]);
                    promiseUnavailable.set(true);
                    outcomeListener.onOutcome(job[0].kind(),job[0].context().residentId(),null,"unsupported");
                    return w;
                }
                if(job[0].kind().equals("turn"))ConversationLifecycle.failTurn(w,job[0].operation(),clock.instant());
                if(job[0].kind().equals("summary"))ConversationLifecycle.failSummary(w,job[0].operation(),clock.instant());
                // The request may have crossed local midnight while it was in flight. Never refund
                // yesterday's failed reservation out of today's fresh allowance, and never charge
                // today's failure cap for a request reserved on another day.
                boolean currentBudgetDay=Objects.equals(w.modelBudgetDay,job[0].day());
                if(currentBudgetDay){w.modelCallsToday=Math.max(0,w.modelCallsToday-1);w.modelFailuresToday++;}
                w.modelConsecutiveFailures++;
                long delay=Math.min(600,75L*(1L<<Math.min(3,w.modelConsecutiveFailures-1)));
                w.modelRetryAfter=clock.instant().plusSeconds(delay);w.modelStatus="暂时按自己的习惯生活，稍后再想新主意";
                outcomeListener.onOutcome(job[0].kind(),job[0].context().residentId(),null,"failed");
                return w;
            });}catch(Exception ignored){/* A deleted world is never recreated by a late result. */}
        } finally {
            // Both claims taken for this dispatch are released here, on every path (a successful apply,
            // an exception, or a reservation that found nothing to do) - docs/04 「只并行"想"，写世界仍串
            // 行」. Never merged into one release: #workersByUser only bounds how many workers this
            // director has outstanding (an optimisation), #thinking is the actual per-resident mutual
            // exclusion, and the two are keyed differently (userId vs. worldId+residentId) on purpose.
            java.util.concurrent.atomic.AtomicInteger workers=workersByUser.get(userId);
            if(workers!=null)workers.decrementAndGet();
            // job[0] is null whenever reserve() found nothing to do - no resident claim was ever taken
            // in that case (reserved() is the only place #thinking is added to), so there is nothing to
            // release. Every branch of reserve() builds its Work through perspective(w,<subject>,...),
            // so work.context().residentId() is always the resident this claim was taken for, whatever
            // the work kind - verified by reading every reserved(...) call site above.
            if(job[0]!=null)thinking.remove(thinkingKey(job[0].worldId(),job[0].context().residentId()));
        }
    }
    /** True while this resident already has a model call outstanding, in this or another worker for
     * this same world (see {@link #thinking}'s own field comment) - the only thing stopping a second
     * worker from reserving the very same resident a second time while the first call is still in
     * flight (docs/04 「只并行"想"，写世界仍串行」). Every branch below must check it exhaustively - a
     * dialogue turn, a summary, a pending encounter/occasion, an explain/dayplan/reflect/promise pass,
     * and both ordinary-decision candidate lists all have to skip a resident who is already thinking. */
    private boolean thinking(CompanionWorld w,String residentId){return thinking.contains(thinkingKey(w.id,residentId));}
    private Work reserve(CompanionWorld w,Instant now,boolean backgroundPreferred) {
        if(w.modelRetryAfter!=null&&now.isBefore(w.modelRetryAfter))return null;
        String day=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
        if(!Objects.equals(w.modelBudgetDay,day)){w.modelBudgetDay=day;w.modelCallsToday=0;w.modelFailuresToday=0;w.modelConsecutiveFailures=0;}
        if(w.modelCallsToday>=dailyBudget||w.modelFailuresToday>=32)return null;
        for(Conversation c:w.conversations)if("model".equals(c.mode)&&"active".equals(c.status)) {
            // Neither side of a conversation already mid-call should take a turn - not just the
            // prospective speaker (docs/04 「只并行"想"，写世界仍串行」: #thinking must be checked
            // exhaustively, and the other participant thinking about something else is still a reason
            // to wait for this conversation).
            if(c.participantIds.stream().anyMatch(id->thinking(w,id)))continue;
            var operation=ConversationLifecycle.reserveTurn(w,c,now);if(operation==null)continue;
            var context=perspective(w,operation.speakerId(),now,c.turns);
            Project topic=ResidentSimulation.project(w,c.topicId);
            String topicTitle=topic==null?"眼前的生活和工作":topic.title;
            var request=new ResidentMind.DialogueRequest(context,c.id,c.turnVersion,operation.operationId(),partnerName(w,c,operation.speakerId()),topicTitle);
            return reserved(w,now,new Work("turn",w.id,ResidentSimulation.state(w,operation.speakerId()).revision,w.intentRevision,now,context,operation,request,null,w.modelSequence+1,day,null,null,null,null,null,null,null,null));
        }
        for(Conversation c:w.conversations)if("model".equals(c.mode)&&"ended".equals(c.status)&&!c.turns.isEmpty()) {
            for(String speaker:c.participantIds){
                if(thinking(w,speaker))continue;
                var operation=ConversationLifecycle.reserveSummary(w,c,speaker,now);if(operation==null)continue;
                var context=perspective(w,speaker,now,List.of());
                var ids=c.turnMemoryIds.getOrDefault(speaker,List.of());
                var memories=w.memories.stream().filter(m->m.ownerId().equals(speaker)&&ids.contains(m.id())).toList();
                var request=new ResidentMind.SummaryRequest(context,c.id,partnerName(w,c,speaker),new ArrayList<>(c.turns),ResidentMind.memoryViews(memories));
                return reserved(w,now,new Work("summary",w.id,ResidentSimulation.state(w,speaker).revision,w.intentRevision,now,context,operation,null,request,w.modelSequence+1,day,null,null,null,null,null,null,null,null));
            }
        }
        // A person standing in front of you outranks re-picking what to do with your afternoon: the
        // moment passes (see PENDING_ENCOUNTER_TTL_SECONDS) while an ordinary decision keeps. Placed
        // below dialogue turns so an encounter can never interrupt a conversation already underway,
        // and deliberately NOT subject to the per-resident decision cooldown - the cooldown paces a
        // resident's own restlessness, not their answer to something that just happened to them.
        for(CompanionWorld.PendingEncounter pending:new ArrayList<>(w.pendingEncounters)){
            if(thinking(w,pending.residentId))continue;
            ResidentState r=ResidentSimulation.state(w,pending.residentId);
            if(r==null||r.revision!=pending.residentRevision)continue;
            if(r.id.equals("self")&&!ResidentSimulation.selfIsFree(w))continue;
            if(ResidentSimulation.activeConversation(w,r.id)!=null||ResidentSimulation.activeConversation(w,pending.otherId)!=null)continue;
            Actor other=ResidentSimulation.actor(w,pending.otherId);
            var context=perspective(w,r.id,now,List.of());
            var topic=ResidentSimulation.inviteTopic(w,r.id,pending.otherId,now);
            var request=new ResidentMind.ReactRequest(context,pending.id,other.id(),other.name(),other.activity(),pending.place,
                ResidentSimulation.reactions(w,r.id,pending.otherId,now),topic==null?null:topic.title);
            return reserved(w,now,new Work("react",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,request,null,null,null,null,null,null));
        }
        // A moment that only this moment makes sense in - locking up on the way out, closing for the
        // day once the hour has passed, reconsidering what you do with your days at the junction where
        // the last thing ended. Below a face-to-face fact (a person in front of you outranks a door)
        // and above an ordinary decision, for the reason the encounter above is: the occasion passes
        // and the decision keeps. See Occasions for why any of this is asked separately at all.
        if(!considerUnavailable.get())for(CompanionWorld.PendingOccasion pending:new ArrayList<>(w.pendingOccasions)){
            if(thinking(w,pending.residentId))continue;
            ResidentState r=ResidentSimulation.state(w,pending.residentId);
            if(r==null||r.revision!=pending.residentRevision)continue;
            if(r.id.equals("self")&&!ResidentSimulation.selfIsFree(w))continue;
            if(ResidentSimulation.activeConversation(w,r.id)!=null)continue;
            Occasions.Definition definition=Occasions.of(pending.key);
            if(definition==null)continue;
            var context=perspective(w,r.id,now,List.of());
            var request=new ResidentMind.ConsiderRequest(context,pending.id,pending.key,pending.fact,
                definition.question(),definition.yes(),definition.no(),pending.place);
            return reserved(w,now,new Work("consider",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,null,null,null,null,null,request));
        }
        // Smallville's engine of movement: soon after waking, before anything else is decided, a
        // resident thinks through the day. Every later decision is shown what they meant to be doing
        // (see perspective's plan cues), so the plan has to exist before the first of them.
        ResidentState dayPlanner=!mind.plansDays()||dayPlanUnavailable.get()?null:w.residentStates.stream()
            .filter(r->!thinking(w,r.id)&&(!r.id.equals("self")||ResidentSimulation.selfIsFree(w))
                &&ResidentSimulation.activeConversation(w,r.id)==null&&needsDayPlan(w,r,now))
            .min(fairResidentOrder(w,r->r.lastAskedAt)).orElse(null);
        if(dayPlanner!=null){
            var context=perspective(w,dayPlanner.id,now,List.of());
            return reserved(w,now,new Work("dayplan",w.id,dayPlanner.revision,w.intentRevision,now,context,null,null,null,
                w.modelSequence+1,day,dayPlanRequest(w,dayPlanner,context),null,null,null,null,null,null,null));
        }
        // A saturated 25-person town should not have to become globally idle before anybody can
        // consolidate experience. Passing facts above still win; this reserved lane only competes
        // with explanations and the ordinary "what next?" pool below. A single-flight evaluation
        // gets the same guarantee every sixth reservation so parallelism=1 remains a valid control
        // rather than a mode in which reflection is structurally impossible.
        boolean cognitionTurn=backgroundPreferred||(parallelism==1&&Math.floorMod(w.modelSequence,6)==5);
        if(cognitionTurn){
            Work cognition=reserveCognition(w,now,day);
            if(cognition!=null)return cognition;
        }
        // Accounting for one's own recent unaccounted-for behaviour (explain): a person who is about
        // to re-decide what to do next should first know what they have just been doing - the account
        // is what argues for the next choice, not the other way round. Placed above ordinary decision
        // dispatch for exactly that reason, but still below a live conversation turn/summary or a
        // face-to-face encounter, none of which this should ever delay. Skipped entirely once this
        // mind has already shown (via UnsupportedOperationException) that it does not implement
        // explain - see explainUnavailable's own doc comment on why that guard exists at all.
        if(!explainUnavailable.get()){
            ResidentState r=w.residentStates.stream()
                .filter(candidate->!thinking(w,candidate.id)&&ResidentSimulation.needsExplanation(w,candidate.id,now))
                .min(fairResidentOrder(w,candidate->candidate.lastAskedAt)).orElse(null);
            if(r!=null){
            var context=perspective(w,r.id,now,List.of());
            var deeds=ResidentSimulation.unexplainedDeeds(w,r.id);
            var request=new ResidentMind.ExplainRequest(context,ResidentMind.deedViews(deeds));
            return reserved(w,now,new Work("explain",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,request,null,null,null,null,null));
            }
        }
        // Per-resident decision throttling (item 1): replaces the old world-global modelRequestedAt
        // gate below candidates so each resident thinks on their own clock. Decision throughput across
        // the whole town is now bounded by up to #parallelism concurrent workers (docs/04 「只并行"想"，
        // 写世界仍串行」) rather than one shared in-flight slot, and each resident's own cooldown still
        // paces that resident's own restlessness independently of anyone else's.
        var candidates=w.residentStates.stream()
            // The avatar ("self") only ever joins this pool during its own free/autonomous time (item
            // 7, see ResidentSimulation.selfIsFree) - never while the user is explicitly directing it.
            .filter(r->(!r.id.equals("self")||ResidentSimulation.selfIsFree(w))&&ResidentSimulation.activeConversation(w,r.id)==null)
            // A resident with a call already outstanding must never be handed a second one - see
            // #thinking's own doc comment on why every branch of reserve() checks this.
            .filter(r->!thinking(w,r.id))
            .filter(r->r.decisionRetryAfter==null||!now.isBefore(r.decisionRetryAfter))
            .filter(r->r.lastDecisionRequestedAt==null||Duration.between(r.lastDecisionRequestedAt,now).getSeconds()>=decisionThrottleSeconds)
            .filter(r->needsDecision(w,r,now)).toList();
        // Legacy rule worlds still allow plan decisions while talking, but their text is not a model turn.
        if(candidates.isEmpty()&&!w.modelConversationsEnabled)candidates=w.residentStates.stream().filter(r->!r.id.equals("self")&&!thinking(w,r.id)&&r.plan!=null&&!Set.of("travel","sleep").contains(r.plan.action())).toList();
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
                .min(fairResidentOrder(w,c->c.lastDecisionRequestedAt))
                .orElseThrow();
            var conversation=ResidentSimulation.activeConversation(w,r.id);
            var context=perspective(w,r.id,now,conversation==null?List.of():conversation.turns);
            ResidentSimulation.recordDecisionTrigger(w,r.id,classifyTrigger(w,r,now),now);
            return reserved(w,now,new Work("decision",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,null,null,null,null,null,null));
        }
        Work cognition=reserveCognition(w,now,day);
        if(cognition!=null)return cognition;
        // Dead last, below even reflection: this is only ever asked when the town has run out of
        // things to do together, so nothing else can ever be starved by it.        // Dead last, below even reflection: this is only ever asked when the town has run out of
        // things to do together, so nothing else can ever be starved by it.
        if(!ventureUnavailable.get()){
            // Whoever has gone longest without being asked, not whoever happens to be first in the
            // list - otherwise one resident is asked what they want every single time and the other
            // five never are. Same fairness the ordinary decision candidate already gets.
            ResidentState r=w.residentStates.stream()
                .filter(candidate->!thinking(w,candidate.id)&&ResidentSimulation.needsVenture(w,candidate.id,now))
                .min(fairResidentOrder(w,candidate->candidate.lastVentureAt))
                .orElse(null);
            if(r!=null){
            var context=perspective(w,r.id,now,List.of());
            var left=ResidentSimulation.sharedThingsLeft(w,r.id).stream()
                .map(p->new ResidentMind.KnownProject(p.id,p.title,p.place,"还没做完",
                    r.id.equals(p.ownerId)?null:ResidentSimulation.actor(w,p.ownerId).name())).toList();
            return reserved(w,now,new Work("venture",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day,null,null,null,null,
                new ResidentMind.VentureRequest(context,left),null,null,null));
            }
        }
        // Bottom of the list with venture, and for the same reason: nobody is waiting on it. Whoever has
        // gone longest without being asked, so one resident is not the only person in town who is ever
        // offered the chance to say "I'll be there".
        if(!promiseUnavailable.get()){
            ResidentState r=w.residentStates.stream()
                .filter(candidate->!thinking(w,candidate.id)&&ResidentSimulation.needsPromiseAsk(w,candidate.id,now))
                .min(fairResidentOrder(w,candidate->candidate.lastPromiseAskedAt))
                .orElse(null);
            if(r!=null){
                var context=perspective(w,r.id,now,List.of());
                String place=ResidentSimulation.actor(w,r.id).place();
                var here=w.residentStates.stream()
                    .filter(o->!o.id.equals(r.id)&&!"self".equals(o.id))
                    .map(o->ResidentSimulation.actor(w,o.id))
                    .filter(a->a.place().equals(place)&&!Set.of("walk","travel","sleep","away").contains(a.activity()))
                    .map(a->new ResidentMind.ActorView(a.id(),a.name(),a.role(),a.place(),a.activity(),null)).toList();
                var needsHands=ResidentSimulation.sharedThingsLeft(w,r.id).stream()
                    .map(pr->new ResidentMind.KnownProject(pr.id,pr.title,pr.place,"还没做完",
                        r.id.equals(pr.ownerId)?null:ResidentSimulation.actor(w,pr.ownerId).name())).toList();
                if(!here.isEmpty())
                    return reserved(w,now,new Work("promise_offer",w.id,r.revision,w.intentRevision,now,context,null,null,null,
                        w.modelSequence+1,day,null,null,null,null,null,
                        new ResidentMind.PromiseOfferRequest(context,here,needsHands),null,null));
            }
        }
        return null;
    }

    /** Bounded cognition work that is allowed to use one reserved lane in a saturated town. A due
     * promise comes first because its six-hour window closes; a morning outline comes next; reflection
     * remains last within the group. Every selection uses the resident's own last-asked watermark and
     * a rotating tie-break, so 25 residents reserved at the same simulated instant do not collapse
     * back to source-list order. */
    private Work reserveCognition(CompanionWorld w,Instant now,String day){
        if(!promiseUnavailable.get()){
            ResidentState r=w.residentStates.stream()
                .filter(candidate->!"self".equals(candidate.id)&&!thinking(w,candidate.id)
                    &&!ResidentSimulation.promisesAwaitingThought(w,candidate.id,now).isEmpty())
                .min(fairResidentOrder(w,candidate->candidate.lastAskedAt)).orElse(null);
            if(r!=null){
                var promise=ResidentSimulation.promisesAwaitingThought(w,r.id,now).getFirst();
                var context=perspective(w,r.id,now,List.of());
                String role=promise.byId.equals(r.id)?"made_it":promise.toId.equals(r.id)?"promised_to":"witnessed";
                var view=new ResidentMind.PromiseView(promise.id,promise.byId,
                    ResidentSimulation.actor(w,promise.byId).name(),promise.what,promise.place,
                    promise.dueAt==null?null:promise.dueAt.toString(),promise.outcome,role);
                var aboutThem=ResidentMind.memoryViews(w.memories.stream()
                    .filter(m->m.ownerId().equals(r.id)&&(promise.byId.equals(m.sourceId())
                        ||(m.text()!=null&&m.text().contains(ResidentSimulation.actor(w,promise.byId).name()))))
                    .sorted(Comparator.comparing(CompanionWorld.Memory::at).reversed()).limit(8).toList());
                return reserved(w,now,new Work("promise_settled",w.id,r.revision,w.intentRevision,now,context,null,null,null,
                    w.modelSequence+1,day,null,null,null,null,null,null,
                    new ResidentMind.PromiseSettledRequest(context,view,aboutThem),null));
            }
        }
        if(!reflectUnavailable.get()){
            ResidentState r=w.residentStates.stream()
                .filter(candidate->!thinking(w,candidate.id)&&ResidentSimulation.activeConversation(w,candidate.id)==null
                    &&ResidentSimulation.needsReflection(w,candidate.id,now))
                .min(fairResidentOrder(w,candidate->candidate.lastAskedAt)).orElse(null);
            if(r!=null){
                var context=perspective(w,r.id,now,List.of());
                var source=ResidentSimulation.reflectionSource(w,r.id,now);
                var standingBeliefs=w.memories.stream()
                    .filter(m->m.ownerId().equals(r.id)&&"belief".equals(m.sourceType())&&!m.superseded()&&m.supersedesKey()!=null)
                    .sorted(Comparator.comparing(CompanionWorld.Memory::at).reversed())
                    .map(m->new ResidentMind.StandingBeliefView(m.supersedesKey(),m.text()))
                    .limit(STANDING_BELIEFS_OFFERED).toList();
                var request=new ResidentMind.ReflectRequest(context,ResidentMind.memoryViews(source),
                    ResidentSimulation.habitTraits(r.id).stream()
                        .map(t->new ResidentMind.HabitTraitView(t.key(),t.description())).toList(),standingBeliefs);
                return reserved(w,now,new Work("reflect",w.id,r.revision,w.intentRevision,now,context,null,null,null,
                    w.modelSequence+1,day,null,null,null,request,null,null,null,null));
            }
        }
        return null;
    }

    private static Comparator<ResidentState> fairResidentOrder(CompanionWorld w,java.util.function.Function<ResidentState,Instant> lastAsked){
        int size=Math.max(1,w.residentStates.size());int start=Math.floorMod((int)w.modelSequence,size);
        return Comparator.comparing(lastAsked,Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(r->{int index=w.residentStates.indexOf(r);return Math.floorMod(index-start,size);});
    }
    /** How many of a resident's own standing views are offered back to them when they reflect. Kept
     * short on purpose: a long list turns into a checklist to be worked through, which is the failure
     * mode the habit list already had to be warned against in the prompt. Most recent first, because
     * an old view nobody has touched in days is the least likely thing this afternoon is about. */
    private static final int STANDING_BELIEFS_OFFERED=5;
    /** Awake, outside their own usual sleeping hours, and no plan yet for today. */
    private boolean needsDayPlan(CompanionWorld w,ResidentState r,Instant now){
        if("sleep".equals(ResidentSimulation.actor(w,r.id).activity())||ResidentSimulation.withinUsualSleepWindow(w,r.id,now))return false;
        String today=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
        return r.dayPlan==null||!today.equals(r.dayPlan.day);
    }
    private static ResidentMind.DayPlanRequest dayPlanRequest(CompanionWorld w,ResidentState r,ResidentMind.Context context){
        var duties=r.duties.stream().map(d->new ResidentMind.DutyView(ResidentDuties.clock(d.startMinute),ResidentDuties.clock(d.endMinute),
            TownPlaces.homeOf(r.id).equals(d.place)?"home":d.place,d.action,d.what,
            d.toId==null||ResidentSimulation.state(w,d.toId)==null?null:ResidentSimulation.actor(w,d.toId).name())).toList();
        return new ResidentMind.DayPlanRequest(context,duties,
            r.sleepScheduleSeeded?ResidentDuties.clock(r.usualWakeMinute):null,r.sleepScheduleSeeded?ResidentDuties.clock(r.usualSleepMinute):null);
    }
    private static String restNote(CompanionWorld.DaySegment segment){
        return "rest".equals(segment.action)&&TownPlaces.isHome(segment.place)?"（歇一会儿，不是睡整觉）":"";
    }
    private static List<String> planAware(List<String> routine,List<String> plan){
        if(plan.isEmpty())return routine;var out=new ArrayList<String>(routine);out.addAll(plan);return List.copyOf(out);
    }
    private static List<ResidentSimulation.PlannedSegment> plannedSegments(ResidentMind.DayPlanDraft draft){
        if(draft.segments()==null)return null;
        return draft.segments().stream().map(s->s==null?null:new ResidentSimulation.PlannedSegment(
            ResidentDuties.parseClock(s.start()),ResidentDuties.parseClock(s.end()),s.place(),s.action(),s.label())).toList();
    }
    /** What this resident themselves planned for now and next, stated as their own intention, with
     * the option that carries it named so following the plan is one choice rather than a search. */
    private static List<String> planCues(CompanionWorld w,ResidentState r,Instant now,List<ResidentMind.DecisionOptionView> options){
        var cues=new ArrayList<String>();
        var current=ResidentDuties.currentSegment(w,r,now);
        if(current!=null){
            String modelPlace=TownPlaces.homeOf(r.id).equals(current.place)?"home":current.place;
            var option=options.stream().filter(o->Objects.equals(o.place(),modelPlace)&&Objects.equals(o.action(),current.action)).findFirst().orElse(null);
            boolean alreadyThere=current.place.equals(ResidentSimulation.actor(w,r.id).place())&&r.plan!=null&&Objects.equals(r.plan.action(),current.action);
            if(!alreadyThere)cues.add("今天这会儿的打算（早上自己排的）："+ResidentDuties.clock(current.startMinute)+"–"+ResidentDuties.clock(current.endMinute)
                +"在"+ResidentSimulation.placeName(current.place)+"，"+current.label+restNote(current)+(option==null?"":"——选项"+option.id()+"就是这件事"));
        }
        var next=current==null?ResidentDuties.nextSegment(w,r,now,60):null;
        if(next!=null){
            // Without the walk and the minutes left, "12:30 去咖啡馆" at 12:21 was read as "wait at home".
            String here=ResidentSimulation.actor(w,r.id).place();
            int walk=here.equals(next.place)?0:ResidentDuties.walkMinutes(here,next.place);
            int minutesLeft=next.startMinute-ResidentDuties.localMinuteOf(w,now);
            String modelPlace=TownPlaces.homeOf(r.id).equals(next.place)?"home":next.place;
            var option=options.stream().filter(o->Objects.equals(o.place(),modelPlace)&&Objects.equals(o.action(),next.action)).findFirst().orElse(null);
            boolean setOut=minutesLeft<=walk+10;
            cues.add("接下来的打算："+ResidentDuties.clock(next.startMinute)+"去"+ResidentSimulation.placeName(next.place)+"，"+next.label+restNote(next)
                +(walk==0?"（就在这里）":"（从这儿走过去约"+walk+"分钟）")
                +(setOut&&option!=null?"——现在动身正好，选项"+option.id()+"就是这件事":"——还有"+minutesLeft+"分钟，不必先回住处等"));
        }
        return cues;
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
        // Claims this resident for the duration of the model call about to be dispatched (docs/04
        // 「只并行"想"，写世界仍串行」) - released in run()'s finally on every path. reserve() always runs
        // inside store.update's own per-user transaction (JdbcWorldStore takes a "for update" row lock;
        // InMemoryWorldStore is synchronized), so reservations for one world are serialised and this
        // claim is race-free even though the model call it guards runs outside that transaction.
        thinking.add(thinkingKey(w.id,work.context().residentId()));
        // Per-resident decision cooldown (item 1): only stamped for an actual re-decision, never for a
        // conversation turn/summary/day-plan dispatch, which have their own reservation timing.
        if(work.kind().equals("decision")){ResidentState r=ResidentSimulation.state(w,work.context().residentId());if(r!=null)r.lastDecisionRequestedAt=now;}
        // The "last asked" marker that bounds the 小工作集 (see ResidentState.lastAskedAt's own doc
        // comment). Stamped here because reserved() is the one choke point every dispatch kind passes
        // through, and perspective() above already read the OLD value to bound this dispatch's own
        // window before this line moves it forward - so the window shown next time starts exactly where
        // this one left off.
        //
        // Every kind EXCEPT a conversation turn and its recollection, and that exception is load-bearing
        // rather than tidy. A turn's prompt does not lean on the working set at all: what is happening
        // right now reaches it as Context.conversation, the live transcript. Meanwhile every turn writes
        // two raw memories (ConversationLifecycle.appendSpeech: an "我对X说" for the speaker and an
        // "X当面说" for the listener, both raw tier and therefore working-set material), and turns are
        // dispatched a few seconds apart. Stamping on each one would park the floor at the last thing
        // said, so when the conversation ended and the resident next had to decide anything, every
        // memory of that conversation would sit BEFORE the floor and be filtered out - and the
        // recollection that summarises it is a `reflection`, which the self-account deliberately no
        // longer admits either. The resident would finish a long talk with somebody and then choose
        // what to do next with no trace of it anywhere in the prompt. That is structural, not a matter
        // of timing: it happens every time. So a conversation consumes nothing, and the talk it
        // produced is still there to be seen at the next real decision.
        if(!work.kind().equals("turn")&&!work.kind().equals("summary")){
            ResidentState asked=ResidentSimulation.state(w,work.context().residentId());
            if(asked!=null)asked.lastAskedAt=now;
        }
        w.modelStatus=work.kind().equals("turn")?""+work.context().self().name()+"正在想怎么接这句话":work.kind().equals("summary")?"有人在回想刚才的谈话":work.kind().equals("dayplan")?""+work.context().self().name()+"在想今天大致怎么过":work.kind().equals("explain")?""+work.context().self().name()+"在回想刚才做了什么":work.kind().equals("reflect")?""+work.context().self().name()+"在想些什么":work.kind().equals("venture")?""+work.context().self().name()+"在想有没有什么想做的事":"有位居民正在想下一步";
        w.revision++;return work;
    }
    ResidentMind.Context perspective(CompanionWorld w,String residentId,Instant now,List<Turn> transcript){
        var r=ResidentSimulation.state(w,residentId);Actor self=ResidentSimulation.actor(w,r.id);
        // The avatar is present in the world the same way any resident is: if it is standing in this
        // place (and not mid-walk), it shows up here too. Its label/name only ever carry the fixed,
        // pre-written phrases from CompanionRules - never anything the user typed.
        var visible=new ArrayList<Actor>(w.residents);if(w.avatar!=null)visible.add(w.avatar);
        var nearby=visible.stream().filter(a->!a.id().equals(r.id)&&ResidentSimulation.sameRoom(w,r.id,a.id())&&!a.activity().equals("walk")).toList();
        // Two layers, not one situation-ranked retrieval window (docs/01 「心智」, docs/04 「记忆改成两
        // 层」). This used to query retrieval with the resident's own situation - where they are, what
        // they are aimed at, who is nearby - which sounds like the right fix for the echo it replaced
        // (r.plan.reason(), the resident's own last rationalisation, feeding the query that fetched
        // their memories back to them) but was not enough on its own: recency and layer still agreed
        // with each other every time underneath it, because a `reflection` is written once per decision
        // and is always the freshest thing on record. Measured across 243 real decisions: 51% of
        // everything retrieved was the resident's own previous sentences (66% in a later run), and
        // 44-58% of decisions got a byte-identical memory set to their own previous one - an echo
        // chamber with a relevance score sitting on top of it, immune to reweighting because the
        // imbalance is structural (see CompanionRecall.OWN_VOICE_SHARE's own note), not a tuning
        // problem. The fix is not a better query; it is showing two different things instead of one
        // ranked mix of everything this resident has ever written about themselves.
        //
        // 自述文档: this resident's own currently-true standing material - live beliefs first (the part
        // that grows and revises), seed backstory backfilling whatever capacity is left over (see
        // CompanionRecall.selfAccount's own doc comment for why backstory has to live here rather than
        // in workingSet below: a seed's timestamp predates the town starting, so the moment
        // r.lastAskedAt below is non-null it would otherwise be excluded from every prompt forever).
        // Bounded at its own fixed capacity regardless of how many this resident has ever formed, so a
        // prolific reflector never grows their own prompt.
        var selfAccount=CompanionRecall.selfAccount(w.memories,r.id,now,CompanionRecall.SELF_ACCOUNT_CAPACITY);
        // 小工作集: what has actually happened to this resident, in their own raw terms, since the last
        // time ANYTHING put a question to them - r.lastAskedAt, stamped once per dispatch in reserved()
        // below for every call kind (see that field's own doc comment, and progress.md's "信念为什么是
        // 0" which named its absence). Cut by change, not a timer: the window is exactly as wide as it
        // has actually been since this resident was last asked anything, and that is itself set by
        // rule-detected triggers (an encounter, a plan ending, a perceivable change), not a clock - the
        // same lesson the 30-minute seat cooldown and the 12-minute encounter cooldown both had to learn
        // (docs/04). Raw tier only (see CompanionRecall.workingSet's own doc comment): a reflection or
        // belief is a synthesis, not something that happened, and excluding that tier here is what
        // removes the own-voice flood at its root rather than merely capping its share.
        var recentHappenings=CompanionRecall.workingSet(w.memories,r.id,r.lastAskedAt,now,CompanionRecall.WORKING_SET_CAPACITY);
        var memories=new ArrayList<Memory>(selfAccount);memories.addAll(recentHappenings);
        var known=w.projects.stream().filter(p->ResidentSimulation.knows(w,r.id,p.id)).map(p->{
            var view=r.knownProjects.get(p.id);
            String startedBy=p.ownerId==null||p.ownerId.equals(r.id)?null:ResidentSimulation.actor(w,p.ownerId).name();
            return new ResidentMind.KnownProject(p.id,p.title,ResidentSimulation.knownPlace(r,p),
                projectStage(view==null?null:view.status(),view==null?0:view.progress(),ResidentSimulation.takesMoreThanOnePerson(p)),startedBy);
        }).toList();
        var knownPlaces=knownPlaces(w,r.id);var cafeRoleFacts=cafeRoleFacts(w,r.id);
        var decisionOptions=new ArrayList<ResidentMind.DecisionOptionView>();
        for(var option:ResidentSimulation.availableDecisionOptions(w,r.id,now)){
            if(option.targetIds().isEmpty())decisionOptions.add(new ResidentMind.DecisionOptionView("d"+decisionOptions.size(),option.action(),option.place(),option.roomId(),null));
            else for(String target:option.targetIds())decisionOptions.add(new ResidentMind.DecisionOptionView("d"+decisionOptions.size(),option.action(),option.place(),option.roomId(),target));
        }
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
        String currentRoomId=ResidentSimulation.roomOf(w,r.id);
        return new ResidentMind.Context(r.id,now.atZone(ZoneId.of(w.timezone)).toLocalTime().toString(),w.weather,ResidentMind.actorView(self),r.goal,List.copyOf(perceptions),planAware(ResidentSimulation.routineCues(w,r.id,now),planCues(w,r,now,decisionOptions)),ResidentMind.memoryViews(memories),ResidentSimulation.todaySoFar(w,r.id,now),ResidentMind.actorViews(nearby),ResidentMind.objectViews(w.objects.stream().filter(o->Objects.equals(o.roomId(),currentRoomId)).toList()),peopleHere,knownPlaces,known,ResidentMind.turnViews(transcript),intentView(r.lifeIntent),intentView(r.careerIntent),ResidentMind.planView(r.plan,now),arrangements,r.occupation,personaView(r.id),ResidentSimulation.availableActions(w,r.id,now),decisionOptions,ResidentSimulation.cafeOperatorId(w),cafeRoleFacts,ResidentSimulation.mayTend(w,r.id),requests,w.cafeStatus,ResidentSimulation.cafeScheduleCue(w,r.id,now),ResidentSimulation.cafeNotice(w,r.id),paused==null?null:new ResidentMind.PausedActionView(paused.action(),TownPlaces.isHome(paused.place())?"home":paused.place(),paused.reason(),paused.remainingSeconds()),portable==null?null:new ResidentMind.PortableActionView(portable.action(),portable.reason(),portable.remainingSeconds()),currentRoomId);
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
    /** Where this resident could name as a destination, derived from {@code w.locations} rather than
     * written out as a fixed list. That difference is the whole point: the place enum in the decision
     * schema is built from exactly these ids (see {@code QwenResidentMind.decideMetered}), so a
     * building that exists in the world is addressable the moment it is added, and one that does not
     * exist cannot be named. The old hard-coded {@code ["home","cafe","street","garden"]} was a
     * ceiling nobody could see: a seventh building would have been in the world, drawn on the map, and
     * literally unnameable by any resident. docs/04-decisions.md 「定位是"规则收窄候选集 + 一次调用"…
     * 非法项压根不在选项里」.
     *
     * <p><b>What must NOT narrow this set:</b> anything this resident does not know. Most sharply, a
     * locked cafe door - {@code DoorService} calls that "this town's first real information
     * asymmetry", and a resident who was not standing there when it was locked learns it only by
     * walking up to it and finding it shut ({@code DoorService.perceiveLockedOut}). Dropping "cafe"
     * from the enum while the door is locked would quietly hand every resident a fact they have no way
     * of holding, and they would stop even trying - which deletes the asymmetry the door exists to
     * create. Narrowing here means "narrow to what is addressable", never "narrow to what will
     * succeed"; whether an arrival actually gets in stays a real event that happens on arrival. See
     * KnownPlacesTest for both halves pinned.
     *
     * <p>Other residents' homes are absent because nothing can address them yet - the model says
     * "home" and the rules resolve it to this resident's own ({@code ResidentSimulation.applyDecision},
     * {@code ResidentMind.modelPlace}). When entering someone else's home becomes a thing, it becomes a
     * thing here, in the rules, and the enum grows on its own. */
    static List<ResidentMind.KnownPlaceView> knownPlaces(CompanionWorld w,String residentId){
        var out=new ArrayList<ResidentMind.KnownPlaceView>();
        String ownHome=TownPlaces.homeOf(residentId);
        boolean addedHome=false;
        for(CompanionWorld.Location location:w.locations){
            boolean home="home".equals(location.kind());
            if(home&&!location.id().equals(ownHome))continue;
            if(home){if(addedHome)continue;addedHome=true;}
            String id=home?"home":location.id();
            ResidentMind.KnownPlaceView authored=AUTHORED_PLACES.get(id);
            out.add(authored!=null?authored:describeUnauthored(id,location.kind()));
        }
        return List.copyOf(out);
    }
    /** The hand-written account of the four places this town has had all along. Keyed by the id the
     * model actually says, so "home" (not any one resident's own home id) is the key here. A place
     * absent from this map still reaches the model - see {@link #describeUnauthored} - because being
     * addressable matters more than being described well, and a building nobody can name is worse than
     * one described plainly. */
    private static final Map<String,ResidentMind.KnownPlaceView> AUTHORED_PLACES=Map.of(
        "home",new ResidentMind.KnownPlaceView("home","自己的住处，有床和个人书桌；适合睡觉、休息，也能继续读写或制作。",List.of("sleep","rest","study","read","work","make")),
        // What someone standing at the door would think of, in the order they would think of it. The
        // earlier wording described the cafe almost entirely as a workspace, which is a fair account of
        // the seating and a poor account of what a cafe is for: across a whole simulated day nobody
        // ordered anything even once. A place has to advertise what it is good for before anyone can
        // choose it for that.
        "cafe",new ResidentMind.KnownPlaceView("cafe","营业时可进入的公共室内空间：可以点杯喝的坐一会儿，可以约人在这里碰面、拼个桌一起聊，也可以安静读书、学习、写作、制作。共享讨论桌上摆着还没做完的共同的事，谁都可以坐下添一笔，不必是起头的那个人。有六个独立窗边座位和一张共享讨论桌。吧台设备需要经营或帮工权限。",List.of("observe","rest","study","read","work","make","create","help","request_drink","invite","join")),
        "street",new ResidentMind.KnownPlaceView("street","连接住处、咖啡馆和花园的小街，适合散步、观察和偶遇。",List.of("observe")),
        "garden",new ResidentMind.KnownPlaceView("garden","公共花园，适合观察植物、照料花草或做与植物有关的事，也可以动手做在这里落地的共同的事。",List.of("observe","work","create","help","tend_plants")),
        "academy",new ResidentMind.KnownPlaceView("academy","学院有阅读室和教室，可以读书、复习、教人或安静做一段自己的事。",List.of("observe","rest","study","read","work")),
        "gym",new ResidentMind.KnownPlaceView("gym","健身房有训练区和休息区；器械一次只能给一个人用，用久了也可能要修。",List.of("observe","rest","exercise","repair")),
        "board",new ResidentMind.KnownPlaceView("board","公告板广场是看告示、留消息和路过碰见人的地方；板上的内容要走近才看得清。",List.of("observe","read_notice","rest")),
        "shop",new ResidentMind.KnownPlaceView("shop","商店有货架和一张公用工作台，可以缝补、制作或修东西；别人的物件要经过真实的借或赠送才会换手。",List.of("observe","work","make","repair","lend","gift")));
    /** A place that exists in the world but has no authored description yet. Deliberately plain and
     * deliberately not empty: the alternative - leaving it out - is the bug this whole method was
     * written to remove. It says what kind of place it is and nothing about what happens there, because
     * the rules do not know; {@code availableActions} remains the only thing that decides what may
     * actually be done, here as everywhere. */
    private static ResidentMind.KnownPlaceView describeUnauthored(String id,String kind){
        return new ResidentMind.KnownPlaceView(id,"小镇上的一处"+(kind==null?"地方":kind)+"，还没有人细说过这里。",
            List.of("observe","rest","study","read","work","make","create","help"));
    }
    private static List<String> cafeRoleFacts(CompanionWorld w,String residentId){
        List<String> facts=new ArrayList<>();String operator=ResidentSimulation.cafeOperatorId(w);
        if(Objects.equals(operator,residentId)){facts.add("我是咖啡馆当前经营者，经营权和吧台设备责任仍在我这里。");facts.add("我熟悉咖啡馆、吧台和日常开关门方式。");if(!w.cafeOperating)facts.add("closing".equals(w.cafeStatus)?"我之前暂停了经营，咖啡馆正在收店。":"我之前暂停了经营，咖啡馆目前已经关门。");}
        w.workArrangements.stream().filter(a->"active".equals(a.status)&&residentId.equals(a.workerId)).findFirst().ifPresent(a->facts.add(switch(a.kind){case "takeover"->"我通过双方确认的接手安排成为了当前经营者。";case "delegate"->"我正在受托照看咖啡馆。";default->"我有一份仍生效的咖啡馆帮工安排。";}));
        return List.copyOf(facts);
    }
    private boolean applyDecision(CompanionWorld w,Work work,ResidentMind.Decision decision){
        // The old w.modelSequence!=work.sequence() half of this check is gone (docs/04 「只并行"想"，写
        // 世界仍串行」): modelSequence now ticks on EVERY concurrent reservation across the whole town,
        // not just this resident's, so under parallel dispatch that equality would reject essentially
        // every decision. The 90-second wall-clock staleness check stays. Correctness does not depend
        // on the sequence gate: ResidentSimulation.applyDecision/proposeDecision already reject on their
        // own per-resident optimistic-concurrency checks (r.revision!=residentRevision,
        // w.intentRevision!=intentRevision), which is exactly what「冲突 = 版本过期 = 丢弃这次结果」means -
        // a stale reply is discarded, not locked out in advance.
        var c=work.context();if(resultExpired(work,clock.instant()))return false;
        ResidentMind.DecisionOptionView selected=ResidentMind.selectedOption(c,decision);
        boolean exactOptions=c.decisionOptions()!=null&&!c.decisionOptions().isEmpty();
        String action=selected==null?decision==null?null:decision.action():selected.action();
        String place=selected==null?decision==null?null:decision.place():selected.place();
        String roomId=selected==null?decision==null?null:decision.roomId():selected.roomId();
        String targetId=selected==null?decision==null?null:decision.targetId():selected.targetId();
        boolean valid=decision!=null&&action!=null&&c.availableActions().contains(action)&&place!=null
            &&(!exactOptions||selected!=null)&&decision.evidenceIds()!=null&&evidenceWithin(decision.evidenceIds(),c.memories())
            &&(!"propose".equals(action)||!decision.evidenceIds().isEmpty());
        return valid&&(action.equals("propose")
            ?ResidentSimulation.proposeDecision(w,c.residentId(),work.residentRevision(),work.intentRevision(),place,decision.projectTitle(),decision.objectKind(),decision.reason(),decision.evidenceIds(),clock.instant())
            :ResidentSimulation.applyDecision(w,c.residentId(),work.residentRevision(),work.intentRevision(),place,roomId,action,targetId,decision.reason(),decision.speech(),decision.evidenceIds(),clock.instant()));
    }
    private static boolean resultExpired(Work work,Instant now){
        return work==null||now==null||work.at()==null||Duration.between(work.at(),now).getSeconds()>MODEL_RESULT_MAX_AGE_SECONDS;
    }
    private static void refundCurrentBudgetDay(CompanionWorld w,Work work){
        if(Objects.equals(w.modelBudgetDay,work.day()))w.modelCallsToday=Math.max(0,w.modelCallsToday-1);
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
