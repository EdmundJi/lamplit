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
    private final Set<Long> inFlight=ConcurrentHashMap.newKeySet();
    // Development-phase defaults: five residents each writing their own memory need real
    // concurrency, and cost is not a constraint right now - see the constructor for the knobs.
    private final ThreadPoolExecutor executor;
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock){this(store,mind,clock,100000);}
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,int dailyBudget){this(store,mind,clock,dailyBudget,(userId,day,callType,inputTokens,outputTokens)->{});}
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,int dailyBudget,ModelUsageRecorder usageRecorder){this(store,mind,clock,dailyBudget,usageRecorder,8,64,75);}
    @Autowired
    public ResidentDirector(WorldStore store,ResidentMind mind,Clock clock,
                             @Value("${app.town.companion-model-daily-budget:100000}")int dailyBudget,
                             ModelUsageRecorder usageRecorder,
                             @Value("${app.town.companion-mind-pool-size:8}")int poolSize,
                             @Value("${app.town.companion-mind-queue-size:64}")int queueSize,
                             @Value("${app.town.companion-model-decision-throttle-seconds:75}")long decisionThrottleSeconds){
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
        if(!dialogue&&snapshot.modelRequestedAt!=null&&Duration.between(snapshot.modelRequestedAt,now).getSeconds()<decisionThrottleSeconds){inFlight.remove(userId);return;}
        try{executor.execute(()->run(userId));}catch(RejectedExecutionException e){inFlight.remove(userId);}
    }
    private record Work(String kind,ResidentMind.Context context,ConversationLifecycle.Operation operation,
                        ResidentMind.DialogueRequest dialogue,ResidentMind.SummaryRequest summary,long sequence,String day) {}
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
                default->{var r=mind.decideMetered(work.context());result=r.value();usage=r.usage();}
            }
            // Tokens were already spent whether or not the reply below still applies to a fresher world.
            if(usage!=null)recordUsage(userId,work.day(),work.kind(),usage);
            store.update(userId,null,w->{
                if(!w.id.equals(work.context().worldId()))return w;
                w.modelConsecutiveFailures=0;w.modelRetryAfter=null;
                boolean applied;
                if(work.kind().equals("turn")) {
                    var utterance=(ConversationLifecycle.Utterance)result;
                    applied=utterance!=null&&evidenceWithin(utterance.evidenceIds(),work.context().memories())&&ConversationLifecycle.applyTurn(w,work.operation(),utterance,clock.instant());
                    if(!applied)ConversationLifecycle.failTurn(w,work.operation(),clock.instant());
                } else if(work.kind().equals("summary")) {
                    var summary=(ConversationLifecycle.Recollection)result;
                    applied=summary!=null&&evidenceWithin(summary.evidenceIds(),work.summary().conversationMemories())&&ConversationLifecycle.applySummary(w,work.operation(),summary,clock.instant());
                    if(!applied)ConversationLifecycle.failSummary(w,work.operation(),clock.instant());
                } else applied=applyDecision(w,work,(ResidentMind.Decision)result);
                if(!applied){w.modelStatus="刚才的念头已经过时，继续眼前的生活";w.revision++;}
                return w;
            });
        } catch(Exception e){
            log.warn("Companion resident model fallback: {}",safeFailure(e));
            if(job[0]!=null)try{store.update(userId,null,w->{
                if(!w.id.equals(job[0].context().worldId()))return w;
                if(job[0].kind().equals("turn"))ConversationLifecycle.failTurn(w,job[0].operation(),clock.instant());
                if(job[0].kind().equals("summary"))ConversationLifecycle.failSummary(w,job[0].operation(),clock.instant());
                w.modelCallsToday=Math.max(0,w.modelCallsToday-1);w.modelFailuresToday++;w.modelConsecutiveFailures++;
                long delay=Math.min(600,75L*(1L<<Math.min(3,w.modelConsecutiveFailures-1)));
                w.modelRetryAfter=clock.instant().plusSeconds(delay);w.modelStatus="暂时按自己的习惯生活，稍后再想新主意";w.revision++;return w;
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
            var request=new ResidentMind.DialogueRequest(context,c.id,c.turnVersion,operation.operationId(),partnerName(w,c,operation.speakerId()),topic==null?"刚才的话题":topic.title);
            return reserved(w,now,new Work("turn",context,operation,request,null,w.modelSequence+1,day));
        }
        for(Conversation c:w.conversations)if("model".equals(c.mode)&&"ended".equals(c.status)&&!c.turns.isEmpty()) {
            for(String speaker:c.participantIds){
                var operation=ConversationLifecycle.reserveSummary(w,c,speaker,now);if(operation==null)continue;
                var context=perspective(w,speaker,now,List.of());
                var ids=c.turnMemoryIds.getOrDefault(speaker,List.of());
                var memories=w.memories.stream().filter(m->m.ownerId().equals(speaker)&&ids.contains(m.id())).toList();
                var request=new ResidentMind.SummaryRequest(context,c.id,partnerName(w,c,speaker),new ArrayList<>(c.turns),memories);
                return reserved(w,now,new Work("summary",context,operation,null,request,w.modelSequence+1,day));
            }
        }
        if(w.modelRequestedAt!=null&&Duration.between(w.modelRequestedAt,now).getSeconds()<decisionThrottleSeconds)return null;
        // The avatar ("self") shares this ResidentState list so it can be perceived and hold a
        // position, but its activity is user-driven, never a model decision target.
        var candidates=w.residentStates.stream().filter(r->!r.id.equals("self")&&r.plan!=null&&!Set.of("travel","sleep").contains(r.plan.action())&&ResidentSimulation.activeConversation(w,r.id)==null).toList();
        // Legacy rule worlds still allow plan decisions while talking, but their text is not a model turn.
        if(candidates.isEmpty()&&!w.modelConversationsEnabled)candidates=w.residentStates.stream().filter(r->!r.id.equals("self")&&r.plan!=null&&!Set.of("travel","sleep").contains(r.plan.action())).toList();
        if(candidates.isEmpty())return null;
        ResidentState r=candidates.get(Math.floorMod((int)w.modelSequence,candidates.size()));
        var conversation=ResidentSimulation.activeConversation(w,r.id);
        var context=perspective(w,r.id,now,conversation==null?List.of():conversation.turns);
        return reserved(w,now,new Work("decision",context,null,null,null,w.modelSequence+1,day));
    }
    private Work reserved(CompanionWorld w,Instant now,Work work){w.modelRequestedAt=now;w.modelCallsToday++;w.modelSequence++;w.modelStatus=work.kind().equals("turn")?""+work.context().self().name()+"正在想怎么接这句话":work.kind().equals("summary")?"有人在回想刚才的谈话":"有位居民正在想下一步";w.revision++;return work;}
    private ResidentMind.Context perspective(CompanionWorld w,String residentId,Instant now,List<Turn> transcript){
        var r=ResidentSimulation.state(w,residentId);Actor self=ResidentSimulation.actor(w,r.id);
        // The avatar is present in the world the same way any resident is: if it is standing in this
        // place (and not mid-walk), it shows up here too. Its label/name only ever carry the fixed,
        // pre-written phrases from CompanionRules - never anything the user typed.
        var visible=new ArrayList<Actor>(w.residents);if(w.avatar!=null)visible.add(w.avatar);
        var nearby=visible.stream().filter(a->!a.id().equals(r.id)&&a.place().equals(self.place())&&!a.activity().equals("walk")).toList();
        var memories=CompanionRecall.retrieve(w.memories,r.id,r.goal+" "+r.thought,now,10);
        var known=w.projects.stream().filter(p->ResidentSimulation.knows(w,r.id,p.id)).map(p->new ResidentMind.KnownProject(p.id,p.title,ResidentSimulation.knownPlace(r,p))).toList();
        return new ResidentMind.Context(w.id,r.id,r.revision,w.intentRevision,now,now.atZone(ZoneId.of(w.timezone)).toLocalTime().toString(),w.weather,self,r.goal,r.mood,r.thought,r.energy,r.social,new LinkedHashMap<>(r.relationships),memories,nearby,w.objects.stream().filter(o->o.place().equals(self.place())).toList(),known,new ArrayList<>(transcript));
    }
    private boolean applyDecision(CompanionWorld w,Work work,ResidentMind.Decision decision){
        var c=work.context();if(w.modelSequence!=work.sequence()||Duration.between(c.at(),clock.instant()).getSeconds()>90)return false;
        boolean valid=decision!=null&&decision.action()!=null&&decision.place()!=null&&decision.evidenceIds()!=null&&!decision.evidenceIds().isEmpty()&&evidenceWithin(decision.evidenceIds(),c.memories());
        return valid&&(decision.action().equals("propose")
            ?ResidentSimulation.proposeDecision(w,c.residentId(),c.revision(),c.intentRevision(),decision.place(),decision.projectTitle(),decision.objectKind(),decision.reason(),decision.evidenceIds(),clock.instant())
            :ResidentSimulation.applyDecision(w,c.residentId(),c.revision(),c.intentRevision(),decision.place(),decision.action(),decision.targetId(),decision.reason(),decision.speech(),decision.evidenceIds(),clock.instant()));
    }
    // Tags usage with which provider actually served the call (see ResidentMind.Usage/ModelUsageRecorder's
    // provider-aware overload) so spend can be broken down per supplier, not just per call type. A null
    // provider (any ResidentMind that predates routing, or a call that was never attributed) falls back
    // to the plain, untagged record path automatically - see ModelUsageQuery.encodeCallType.
    private void recordUsage(long userId,String day,String callType,ResidentMind.Usage usage){
        try{usageRecorder.record(userId,day,callType,usage.provider(),usage.inputTokens(),usage.outputTokens());}
        catch(Exception e){log.warn("Companion model usage recording failed: {}",safeFailure(e));}
    }
    private static boolean evidenceWithin(List<String> ids,List<Memory> memories){return ids!=null&&ids.stream().allMatch(id->memories.stream().anyMatch(m->m.id().equals(id)));}
    private static String partnerName(CompanionWorld w,Conversation c,String speaker){return ResidentSimulation.actor(w,c.participantIds.stream().filter(id->!id.equals(speaker)).findFirst().orElseThrow()).name();}
    private static String safeFailure(Throwable failure){List<String> codes=new ArrayList<>();for(int i=0;failure!=null&&i<5;i++,failure=failure.getCause())codes.add(failure instanceof com.betterself.growth.shared.api.ApiException api?api.getClass().getSimpleName()+":"+api.code():failure.getClass().getSimpleName());return String.join(" -> ",codes);}
    @PreDestroy void close(){executor.shutdownNow();}
}
