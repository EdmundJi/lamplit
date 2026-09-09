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
        if(!dialogue&&snapshot.modelRequestedAt!=null&&Duration.between(snapshot.modelRequestedAt,now).getSeconds()<decisionThrottleSeconds){inFlight.remove(userId);return;}
        try{executor.execute(()->run(userId));}catch(RejectedExecutionException e){inFlight.remove(userId);}
    }
    private record Work(String kind,String worldId,long residentRevision,long intentRevision,Instant at,
                        ResidentMind.Context context,ConversationLifecycle.Operation operation,
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
                if(!w.id.equals(work.worldId()))return w;
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
                } else {applied=applyDecision(w,work,(ResidentMind.Decision)result);if(applied)rememberDecisionSignal(w,work.context().residentId(),clock.instant());}
                if(!applied){w.modelStatus="刚才的念头已经过时，继续眼前的生活";w.revision++;}
                return w;
            });
        } catch(Exception e){
            log.warn("Companion resident model fallback: {}",safeFailure(e));
            if(job[0]!=null)try{store.update(userId,null,w->{
                if(!w.id.equals(job[0].worldId()))return w;
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
            String topicTitle=topic==null?"眼前的生活和工作":topic.title;
            var request=new ResidentMind.DialogueRequest(context,c.id,c.turnVersion,operation.operationId(),partnerName(w,c,operation.speakerId()),topicTitle);
            return reserved(w,now,new Work("turn",w.id,ResidentSimulation.state(w,operation.speakerId()).revision,w.intentRevision,now,context,operation,request,null,w.modelSequence+1,day));
        }
        for(Conversation c:w.conversations)if("model".equals(c.mode)&&"ended".equals(c.status)&&!c.turns.isEmpty()) {
            for(String speaker:c.participantIds){
                var operation=ConversationLifecycle.reserveSummary(w,c,speaker,now);if(operation==null)continue;
                var context=perspective(w,speaker,now,List.of());
                var ids=c.turnMemoryIds.getOrDefault(speaker,List.of());
                var memories=w.memories.stream().filter(m->m.ownerId().equals(speaker)&&ids.contains(m.id())).toList();
                var request=new ResidentMind.SummaryRequest(context,c.id,partnerName(w,c,speaker),new ArrayList<>(c.turns),ResidentMind.memoryViews(memories));
                return reserved(w,now,new Work("summary",w.id,ResidentSimulation.state(w,speaker).revision,w.intentRevision,now,context,operation,null,request,w.modelSequence+1,day));
            }
        }
        if(w.modelRequestedAt!=null&&Duration.between(w.modelRequestedAt,now).getSeconds()<decisionThrottleSeconds)return null;
        // The avatar ("self") shares this ResidentState list so it can be perceived and hold a
        // position, but its activity is user-driven, never a model decision target.
        var candidates=w.residentStates.stream().filter(r->!r.id.equals("self")&&ResidentSimulation.activeConversation(w,r.id)==null)
            .filter(r->needsDecision(w,r,now)).toList();
        // Legacy rule worlds still allow plan decisions while talking, but their text is not a model turn.
        if(candidates.isEmpty()&&!w.modelConversationsEnabled)candidates=w.residentStates.stream().filter(r->!r.id.equals("self")&&r.plan!=null&&!Set.of("travel","sleep").contains(r.plan.action())).toList();
        if(candidates.isEmpty())return null;
        ResidentState r=candidates.get(Math.floorMod((int)w.modelSequence,candidates.size()));
        var conversation=ResidentSimulation.activeConversation(w,r.id);
        var context=perspective(w,r.id,now,conversation==null?List.of():conversation.turns);
        return reserved(w,now,new Work("decision",w.id,r.revision,w.intentRevision,now,context,null,null,null,w.modelSequence+1,day));
    }
    private boolean needsDecision(CompanionWorld w,ResidentState r,Instant now){
        if(r.plan==null)return true;
        if(Set.of("travel","sleep").contains(r.plan.action()))return false;
        String fingerprint=decisionFingerprint(w,r.id,now);String key=decisionSignalKey(w,r.id);
        if(fingerprint.isBlank()){consideredDecisionSignals.remove(key);return false;}
        return !Objects.equals(consideredDecisionSignals.get(key),fingerprint);
    }
    private void rememberDecisionSignal(CompanionWorld w,String residentId,Instant now){String fingerprint=decisionFingerprint(w,residentId,now);String key=decisionSignalKey(w,residentId);if(fingerprint.isBlank())consideredDecisionSignals.remove(key);else consideredDecisionSignals.put(key,fingerprint);}
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
    private Work reserved(CompanionWorld w,Instant now,Work work){w.modelRequestedAt=now;w.modelCallsToday++;w.modelSequence++;w.modelStatus=work.kind().equals("turn")?""+work.context().self().name()+"正在想怎么接这句话":work.kind().equals("summary")?"有人在回想刚才的谈话":"有位居民正在想下一步";w.revision++;return work;}
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
        var known=w.projects.stream().filter(p->ResidentSimulation.knows(w,r.id,p.id)).map(p->{var view=r.knownProjects.get(p.id);return new ResidentMind.KnownProject(p.id,p.title,ResidentSimulation.knownPlace(r,p),projectStage(view==null?null:view.status(),view==null?0:view.progress()));}).toList();
        var knownPlaces=knownPlaces();var cafeRoleFacts=cafeRoleFacts(w,r.id);
        var arrangements=w.workArrangements.stream().filter(a->Set.of("proposed","active").contains(a.status))
            .filter(a->r.id.equals(a.proposerId)||r.id.equals(a.workerId)||r.id.equals(w.cafeOperatorId))
            .map(a->new ResidentMind.WorkArrangementView(a.id,a.kind,a.place,a.proposerId,a.workerId,a.status,a.note,instant(a.proposedAt),instant(a.acceptedAt),instant(a.endedAt))).toList();
        var requests=w.serviceRequests.stream().filter(x->x.place.equals(self.place())&&Set.of("waiting","preparing","delivered").contains(x.status))
            .map(x->new ResidentMind.ServiceRequestView(x.id,x.requesterId,x.kind,x.status,x.place)).toList();
        var perceptions=new ArrayList<>(ResidentSimulation.salientPerceptions(w,r.id,now));
        for(Actor person:nearby){int relation=r.relationships.getOrDefault(person.id(),40);if(relation>=80)perceptions.add("看到"+person.name()+"时，我自然会多一分亲近和信任。");else if(relation<=20)perceptions.add(person.name()+"在场时，我会有些戒备。");}
        var paused=ResidentSimulation.pausedAction(w,r.id,now);var portable=ResidentSimulation.portableAction(w,r.id,now);
        return new ResidentMind.Context(r.id,now.atZone(ZoneId.of(w.timezone)).toLocalTime().toString(),w.weather,ResidentMind.actorView(self),r.goal,List.copyOf(perceptions),ResidentSimulation.routineCues(w,r.id,now),ResidentMind.memoryViews(memories),ResidentMind.actorViews(nearby),ResidentMind.objectViews(w.objects.stream().filter(o->o.place().equals(self.place())).toList()),knownPlaces,known,ResidentMind.turnViews(transcript),intentView(r.lifeIntent),intentView(r.careerIntent),ResidentMind.planView(r.plan,now),arrangements,r.occupation,ResidentSimulation.availableActions(w,r.id,now),ResidentSimulation.cafeOperatorId(w),cafeRoleFacts,ResidentSimulation.mayTend(w,r.id),requests,w.cafeStatus,ResidentSimulation.cafeScheduleCue(w,r.id,now),ResidentSimulation.cafeNotice(w,r.id),paused==null?null:new ResidentMind.PausedActionView(paused.action(),TownPlaces.isHome(paused.place())?"home":paused.place(),paused.reason(),paused.remainingSeconds()),portable==null?null:new ResidentMind.PortableActionView(portable.action(),portable.reason(),portable.remainingSeconds()));
    }
    private static ResidentMind.LifeIntentView intentView(LifeIntent intent){return intent==null?null:new ResidentMind.LifeIntentView(intent.id,intent.goalId,intent.purpose,intent.status,instant(intent.formedAt),instant(intent.updatedAt),instant(intent.lastActedAt));}
    private static String instant(Instant value){return value==null?null:value.toString();}
    private static String projectStage(String status,int progress){if(Set.of("ready","celebrating").contains(status))return "已经完成";return progress<=0?"刚开始":progress<45?"做了一些":progress<80?"进行中":"大体完成";}
    private static List<ResidentMind.KnownPlaceView> knownPlaces(){return List.of(
        new ResidentMind.KnownPlaceView("home","自己的住处，有床和个人书桌；适合睡觉、休息，也能继续读写或制作。",List.of("sleep","rest","study","read","work","make")),
        new ResidentMind.KnownPlaceView("cafe","营业时可进入的公共室内空间；有六个独立窗边座位和共享讨论桌，适合安静读书、学习、写作、制作或与邻居见面。吧台设备需要经营或帮工权限。",List.of("observe","rest","study","read","work","make","request_drink")),
        new ResidentMind.KnownPlaceView("street","连接住处、咖啡馆和花园的小街，适合散步、观察和偶遇。",List.of("observe")),
        new ResidentMind.KnownPlaceView("garden","公共花园，适合观察植物、照料花草或做与植物有关的事。",List.of("observe","work")));
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
