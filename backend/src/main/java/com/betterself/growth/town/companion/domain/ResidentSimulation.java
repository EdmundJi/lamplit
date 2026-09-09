package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/** Small event-driven society. Plans persist; only completed actions change physical objects. */
public final class ResidentSimulation {
    private ResidentSimulation() {}
    /** Plan actions that make a resident ineligible to start or be pulled into a new conversation,
     * whether as initiator or partner - see the "tend" doc comment at its one use site below for why
     * "tend" belongs in this set even though it is not a passive/absent state like the other four. */
    private static final Set<String> UNAVAILABLE_FOR_CONVERSATION = Set.of("travel","sleep","rest","away","tend");

    /** Abstract positions along the shared street, in walking-seconds units - not frontend pixels (04:
     * 后端定结构，前端定像素). The owner's home sits right by the cafe he runs; the student's desk is a
     * few doors down; the cafe itself is the busiest, most central point; the far homes (artist,
     * gardener, the user's own avatar) sit toward the other end, roughly twenty seconds' walk away -
     * see {@link #travelSeconds} for how this becomes an actual travel duration. */
    private static final Map<String,Integer> STREET_POSITION = new LinkedHashMap<>();
    static {
        STREET_POSITION.put(TownPlaces.homeOf("owner"),0);
        STREET_POSITION.put("street",4);
        STREET_POSITION.put("cafe",4);
        STREET_POSITION.put(TownPlaces.homeOf("student"),8);
        STREET_POSITION.put("garden",14);
        STREET_POSITION.put(TownPlaces.homeOf("artist"),20);
        STREET_POSITION.put(TownPlaces.homeOf("gardener"),26);
        STREET_POSITION.put(TownPlaces.homeOf("self"),30);
    }
    private static int streetPosition(String place){
        Integer known=STREET_POSITION.get(place);
        if(known!=null)return known;
        // A manually authored resident's home (see ResidentSeed.addResident) predates this table;
        // place it deterministically along the same span instead of failing on an unknown key.
        return Math.floorMod(place.hashCode(),30);
    }
    /** Real walking duration between two places, replacing the old fixed 12-second travel (item 2):
     * seconds-scale, a few seconds between next-door places, about twenty seconds edge-to-edge across
     * the whole map - see the calibration note on {@link #STREET_POSITION} above. Bounded at both ends
     * so travel is always long enough to be genuinely on the street for a moment (never instant) and
     * never longer than the map actually is. */
    static int travelSeconds(String from,String to){
        if(Objects.equals(from,to))return 3;
        int seconds=Math.abs(streetPosition(from)-streetPosition(to));
        return Math.max(3,Math.min(20,seconds));
    }
    /** Mean-once-every-~40-simulated-minutes, purely time-and-identity-derived so replay stays
     * deterministic (never Math.random - see Personality's own abandonThreshold() for the same
     * discipline). One independent 1/40 chance per simulated minute gives that mean via a geometric
     * distribution. This is the "走神" (mind-wandering) signal item 5 asks for: a resident may notice
     * they want to reconsider what they are doing for no external reason at all, which is part of what
     * makes a purely event-driven world feel like it can still produce a sudden thought. */
    public static boolean driftDue(CompanionWorld w,ResidentState r,Instant now){
        if(r.plan==null)return false;
        long bucket=now.getEpochSecond()/60;
        long hash=Objects.hash(w.id,r.id,bucket);
        return Math.floorMod(hash,40)==0;
    }
    /** Bounded diagnostic history of why a decision was actually triggered - see
     * {@link CompanionWorld#decisionTriggers}. Never read by any model. */
    public static void recordDecisionTrigger(CompanionWorld w,String residentId,String trigger,Instant at){
        w.decisionTriggers.add(new DecisionTrigger("dt-"+(++w.eventSequence),residentId,trigger,at));
        while(w.decisionTriggers.size()>300)w.decisionTriggers.removeFirst();
    }
    /** Per-pair cooldown for a rule-detected encounter (item 3): thirty to sixty simulated minutes was
     * the asked-for range; forty minutes is chosen as the middle of that range rather than an extreme -
     * long enough that two residents who happen to share a place for a normal 20-30 minute study/work
     * stretch are not re-greeted every few minutes (which is what "a greeting mill" would look like),
     * short enough that a second, later encounter the same afternoon is still possible. See the report
     * for how this was sanity-checked against a real accelerated run. */
    private static final long ENCOUNTER_COOLDOWN_SECONDS = 40*60;
    /** True only when the avatar is not currently under the user's own explicit control (no active
     * focus session, and no intent still pending or active - see CompanionRules.submit/advance, which
     * own that state directly on CompanionWorld). Gated additionally on
     * {@link CompanionWorld#avatarAutonomyEnabled}: see that field's javadoc and ResidentDirector's
     * report for why this stays an explicit opt-in this batch rather than always-on. */
    public static boolean selfIsFree(CompanionWorld w){
        if(!w.avatarAutonomyEnabled||w.focus!=null)return false;
        return w.intents.stream().noneMatch(i->Set.of("pending","active").contains(i.status));
    }
    static LifeIntent lifeIntent(CompanionWorld w,ResidentState r,String goalId,String purpose,String status,Instant at){
        // Intent ids are local descriptive state, never world events.  Keeping them out of the event
        // sequence preserves the deterministic social simulation/replay that already keys choices on
        // that sequence.
        LifeIntent intent=new LifeIntent();intent.id="life-"+r.id+"-"+at.toEpochMilli();intent.goalId=goalId;intent.purpose=purpose;intent.status=status;intent.formedAt=at;intent.updatedAt=at;return intent;
    }
    static void setLifeIntent(CompanionWorld w,ResidentState r,String goalId,String purpose,String status,Instant at){
        if(r.lifeIntent==null||!Objects.equals(r.lifeIntent.purpose,purpose)||!Objects.equals(r.lifeIntent.goalId,goalId))r.lifeIntent=lifeIntent(w,r,goalId,purpose,status,at);
        else {r.lifeIntent.status=status;r.lifeIntent.updatedAt=at;}
    }
    public static void advance(CompanionWorld w,Instant now) {
        ResidentSeed.initialize(w,now);
        reconcileLegacyPlaces(w,now);
        ResidentSeed.reconcileLife(w,now);
        CafeService.reconcileSchedule(w,now);
        deduplicateReflections(w);
        if(w.simulatedAt==null)w.simulatedAt=now;
        long elapsed=Duration.between(w.simulatedAt,now).getSeconds();
        if(elapsed>900) {
            // Reconcile needs gently, then at most 24 seconds of recent life. No offline model calls.
            w.simulatedAt=now.minusSeconds(24);
            for(ResidentState r:w.residentStates){r.energy=Math.max(35,r.energy);r.social=Math.max(40,r.social);r.energyUpdatedAt=w.simulatedAt;}
            for(Conversation c:w.conversations)if(c.status.equals("active"))ConversationLifecycle.finish(w,c,now,"离开期间这段谈话已经告一段落");
        }
        int steps=0;
        while(!w.simulatedAt.plusSeconds(6).isAfter(now)&&steps++<10){w.simulatedAt=w.simulatedAt.plusSeconds(6);step(w,w.simulatedAt);}
    }
    static void step(CompanionWorld w,Instant at) {
        CafeService.reconcileSchedule(w,at);
        ConversationLifecycle.recoverSummaries(w,at);
        // The coffee/water chain has its own clock, independent of whose plan is currently running:
        // a request keeps waiting, gets picked up, or goes cold on real elapsed time even while its
        // requester has already moved on to something else while they wait. See CafeService.
        CafeService.tick(w,at);
        for(Conversation c:new ArrayList<>(w.conversations))if(c.status.equals("active"))continueConversation(w,c,at);
        for(ResidentState r:w.residentStates) {
            // The avatar's own state is present so it can be perceived and can hold a position. Its
            // activity is user-driven (CompanionRules) by default; when CompanionWorld.avatarAutonomyEnabled
            // is on AND it is not currently under the user's own explicit control (selfIsFree), it runs
            // through the exact same plan-completion/decision loop as any NPC below - see selfIsFree's
            // javadoc and ResidentDirector's report for why this stays an explicit opt-in this batch.
            if(r.id.equals("self")) {
                if(!selfIsFree(w)) {
                    // Under explicit user control right now: never race CompanionRules for the avatar.
                    // Any plan left over from a previous free stretch is stale and is dropped silently,
                    // without running its completion side effects against a reality it no longer describes.
                    r.plan=null;r.suspendedAction=null;
                    continue;
                }
                Actor live=actor(w,"self");
                String signature=live.place()+"|"+live.activity()+"|"+live.until();
                if(r.plan!=null&&!signature.equals(r.selfActivitySignature)) {
                    // An explicit user intent (or CompanionRules' own idle autopilot) moved the avatar
                    // out from under this plan since it was made - pre-empt it, per item 7's requirement.
                    r.plan=null;r.suspendedAction=null;
                }
            }
            reconcileDayPlan(w,r,at);
            perceive(w,r,at);
            // The owner's sense of responsibility for the counter builds every tick someone is
            // waiting, whatever else the owner is currently doing - not only when they are free to
            // decide. See CafeService.accruePressure.
            if(CafeService.mayTend(w,r.id))CafeService.accruePressure(w,r,at);
            settleEnergy(w,r,at);
            if(activeConversation(w,r.id)!=null)continue;
            if(r.plan!=null&&!at.isBefore(r.plan.endsAt())){Plan completed=r.plan;complete(w,r,at);if(r.plan==completed){r.plan=null;if(!Set.of("sleep","open_cafe").contains(completed.action()))resumeSuspended(w,r,at);}}
            if(r.plan==null)awaitDecision(w,r,at);
        }
        // The model is each resident's decision-maker. Rule-only fallback completes already approved
        // physical work but does not manufacture a reflection, social choice or new intention.
        CafeService.finishClosingIfEmpty(w,at);
        syncLegacyObjects(w);
    }

    private static void awaitDecision(CompanionWorld w,ResidentState r,Instant at){
        Actor current=actor(w,r.id);
        if(!"idle".equals(current.activity())||!at.isBefore(current.until()))
            replaceActor(w,r.id,current.place(),"idle",w.modelConversationsEnabled?"停下来想下一步":"暂时没有新的安排",at.plusSeconds(300));
    }

    /** Energy follows simulated elapsed time, not the number of service calls or completed actions.
     * Values are game tuning: roughly sixteen ordinary waking hours from full to very low, and about
     * eight hours of sleep from very low back toward full. */
    static void settleEnergy(CompanionWorld w,ResidentState r,Instant at){
        if(r.energyUpdatedAt==null){r.energyUpdatedAt=at;return;}
        long seconds=Duration.between(r.energyUpdatedAt,at).getSeconds();
        if(seconds<=0)return;
        String action=r.plan==null?actor(w,r.id).activity():r.plan.action();
        double hourly=switch(action==null?"idle":action){
            case "sleep"->10.0;
            case "rest"->8.0;
            case "study","read"->-7.5;
            case "work","make","create","help","tend","away"->-8.0;
            case "travel","walk"->-7.0;
            default->-6.0;
        };
        r.energy=clamp(r.energy+hourly*seconds/3600.0);r.energyUpdatedAt=at;
    }
    private static void complete(CompanionWorld w,ResidentState r,Instant at) {
        Plan p=r.plan;
        if(p.action().equals("travel")){int duration=r.desiredDurationSeconds>0?r.desiredDurationSeconds:42;schedule(w,r,r.desiredAction,p.place(),p.targetId(),p.reason(),at,duration);maybeEncounter(w,r,at);return;}
        if(p.action().equals("away")){
            String home=TownPlaces.homeOf(r.id);
            replaceActor(w,r.id,home,"idle","刚从外面回来",at.plusSeconds(60));
            TownPlaces.claim(w,r.id,home,null,at);
            memory(w,r.id,r.id,"observed",at,p.targetId(),"我出门处理了自己的事："+p.reason()+"，现在回来了。",List.of(),6);
            event(w,at,"return",home,List.of(r.id),actor(w,r.id).name()+"从外面回来了。",null);
            return;
        }
        if(p.action().equals("relocate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&actor(w,r.id).place().equals("cafe")&&project.place.equals("garden")) {
                project.place="cafe";r.knownProjects.put(project.id,new ProjectKnowledge(project.id,"cafe",project.status,project.progress,at,r.id));
                for(int i=0;i<w.objects.size();i++){WorldObject o=w.objects.get(i);if(Objects.equals(o.projectId(),project.id))w.objects.set(i,new WorldObject(o.id(),o.kind(),"cafe",o.label(),o.state(),o.projectId()));}
                event(w,at,"adapt","cafe",List.of(r.id),actor(w,r.id).name()+"把「"+project.title+"」的材料带到了檐下，免得淋湿。",project.id);
                memory(w,r.id,r.id,"observed",at,project.id,"我把「"+project.title+"」的材料搬到了咖啡馆檐下。",List.of(),6);
            }
        } else if(p.action().equals("invite")) {
            ResidentState partner=state(w,p.targetId());Project topic=project(w,r.goal);
            if(partner!=null&&canTalkTo(w,r.id,partner.id)){
                if(topic!=null&&canInvite(w,r,partner,topic,at))startConversation(w,r,partner,topic,at);
                else startLifeConversation(w,r,partner,at);
            }
        } else if(Set.of("create","help").contains(p.action())) {
            Project project=project(w,p.targetId());
            if(project!=null&&knows(w,r.id,project.id)&&actor(w,r.id).place().equals(project.place)&&!Set.of("ready","celebrating").contains(project.status)) {
                boolean first=!project.contributors.contains(r.id);if(first)project.contributors.add(r.id);
                // A conscientious resident follows through a little more thoroughly once committed;
                // the least conscientious does a little less per attempt. Never lets personality wipe
                // out a contribution entirely.
                int gain=Math.max(4,(first?25:12)+Personality.of(r).diligenceBonus());
                // A communal project cannot finish through one resident's repeated work alone.
                project.progress=Math.min(project.contributors.size()<project.needed?75:100,project.progress+gain);
                project.status=project.progress==100?"ready":"active";
                project.description=actor(w,r.id).name()+"刚完成了一小部分；"+(project.progress==100?"已经可以一起看看了。":"还想听听别人的想法。");
                w.objects.removeIf(o->Objects.equals(o.projectId(),project.id));
                w.objects.add(new WorldObject("project-"+project.id,project.objectKind,project.place,project.title,project.progress==100?"finished":"progress-"+project.progress,project.id));
                String text=actor(w,r.id).name()+"在"+placeName(project.place)+"为「"+project.title+"」添了一笔"+(first?"，留下了自己的做法。":"。");
                String evidence=memory(w,r.id,r.id,"observed",at,project.id,text,List.of(),7);
                event(w,at,"contribution",project.place,List.of(r.id),text,project.id);
                for(ResidentState other:w.residentStates)if(!other.id.equals(r.id)&&!other.id.equals("self")&&actor(w,other.id).place().equals(project.place)&&!actor(w,other.id).activity().equals("walk"))
                    witnessContribution(w,other,r,project,text,evidence,at);
                if(project.progress==100){project.completedAt=at;event(w,at,"ready",project.place,new ArrayList<>(project.contributors),"「"+project.title+"」准备好了，和最初一个人的想法已经不太一样。",project.id);}
            }
        } else if(p.action().equals("celebrate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&project.status.equals("ready")) {
                project.status="celebrating";
                event(w,at,"celebration",project.place,new ArrayList<>(project.contributors),actor(w,r.id).name()+"招呼大家来看「"+project.title+"」。这一次，桌边多了几个熟悉的位置。",project.id);
                for(String member:project.contributors)if(actor(w,member).place().equals(project.place)&&!actor(w,member).activity().equals("walk")) {
                    boolean detail=Personality.of(state(w,member)).sensitivity()>=65;
                    String text=detail?"我亲眼看见，参与的「"+project.title+"」真的做出来了，连细节都跟当初说的差不多。":"我亲眼看见，参与的「"+project.title+"」真的做出来了。";
                    memory(w,member,r.id,"observed",at,project.id,text,List.of(),9);
                }
            }
        } else if(p.action().equals("open_cafe")){CafeService.openForDay(w,r.id,p.reason(),at);}
        else if(p.action().equals("tend")){CafeService.finishTending(w,r,p.targetId(),at);}
    }
    /** Put aside a concrete action rather than throwing it away.  The remaining time is rebuilt from
     * the interruption instant, so an old end timestamp cannot make a returned-to action complete
     * immediately. */
    private static void suspend(ResidentState r,Instant at){
        if(r.plan==null||r.suspendedAction!=null)return;
        long remaining=Math.max(1,Duration.between(at,r.plan.endsAt()).getSeconds());
        SuspendedAction paused=new SuspendedAction();
        paused.plan=new Plan(r.plan.id(),r.plan.action(),r.plan.place(),r.plan.targetId(),r.plan.reason(),at,at.plusSeconds(remaining));
        paused.desiredAction=r.desiredAction;paused.desiredDurationSeconds=r.desiredDurationSeconds;paused.pausedAt=at;
        r.suspendedAction=paused;
    }
    /** Restoring is a normal scheduling transition, including a journey that was interrupted on the
     * street.  This is deliberately called only after the interrupting action actually completes. */
    static void resumeSuspended(CompanionWorld w,ResidentState r,Instant at){
        SuspendedAction paused=r.suspendedAction;if(paused==null||paused.plan==null)return;
        Plan p=paused.plan;
        if("cafe".equals(p.place())&&!"open".equals(w.cafeStatus))return;
        r.suspendedAction=null;
        int remaining=(int)Math.max(1,Duration.between(p.startedAt(),p.endsAt()).getSeconds());
        if("travel".equals(p.action())){
            r.desiredAction=paused.desiredAction;r.desiredDurationSeconds=paused.desiredDurationSeconds;
            r.plan=new Plan("p-"+(++w.eventSequence),"travel",p.place(),p.targetId(),p.reason(),at,at.plusSeconds(remaining));
            r.revision++;r.thought="继续"+p.reason();replaceActor(w,r.id,"street","walk","继续去"+placeName(p.place()),r.plan.endsAt());
        } else moveOrSchedule(w,r,p.action(),p.place(),p.targetId(),p.reason(),at,remaining);
    }
    /** The same contribution is witnessed by everyone present, but what each observer actually
     * writes into their own memory depends on how much attention to detail they personally pay - not
     * on the event itself, which is identical for all of them. Someone with a sharp eye keeps the
     * concrete wording; someone in the middle keeps only the gist, at lower importance; someone not
     * paying close attention writes nothing down at all - the event happened, but for them it left no
     * trace. This is the mechanism the differentiated-memory tests exercise directly. */
    private static void witnessContribution(CompanionWorld w,ResidentState observer,ResidentState actorState,Project project,String actorText,String actorEvidenceId,Instant at) {
        int sensitivity=Personality.of(observer).sensitivity();
        if(sensitivity<35)return;
        boolean detail=sensitivity>=65;
        String text=detail?"我看见"+actorText:"隐约感觉到"+actor(w,actorState.id).name()+"又在忙「"+project.title+"」，具体做了什么我没太看清。";
        memory(w,observer.id,actorState.id,"observed",at,project.id,text,List.of(actorEvidenceId),detail?7:4);
    }
    private static void moveOrSchedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        if(!actor(w,r.id).place().equals(place)) {
            r.desiredAction=action;r.desiredDurationSeconds=duration;
            int travel=travelSeconds(actor(w,r.id).place(),place);
            r.plan=new Plan("p-"+(++w.eventSequence),"travel",place,target,reason,at,at.plusSeconds(travel));
            r.revision++;r.thought=reason;
            TownPlaces.release(w,r.id); // stepping away frees up the spot right away, not 12 seconds from now
            replaceActor(w,r.id,"street","walk","准备去"+placeName(place)+"："+reason,r.plan.endsAt());
        } else schedule(w,r,action,place,target,reason,at,duration);
    }
    static void schedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        r.plan=new Plan("p-"+(++w.eventSequence),action,place,target,reason,at,at.plusSeconds(duration));r.revision++;r.thought=reason;
        r.desiredAction=null;r.desiredDurationSeconds=0;
        String label=switch(action){case "create","help"->"动手准备"+(project(w,target)==null?"手上的小事":"「"+project(w,target).title+"」");case "study"->"在窗边复习，想守住一点安静";case "invite"->reason;case "join"->"过去和"+(target==null?"邻居":actor(w,target).name())+"坐一起";case "sleep"->"睡着了，给明天留一点精神";case "rest"->"捧着杯子歇一会儿";case "celebrate"->"想请大家看看一起做出来的东西";case "wait"->reason;case "tend"->r.id.equals(CafeService.operatorId(w))?"回到吧台，照应一下柜台前的人":"替"+actor(w,CafeService.operatorId(w)).name()+"照看吧台";case "away"->"出门去处理自己的事："+reason;default->reason;};
        replaceActor(w,r.id,place,action,label,r.plan.endsAt());
        // "能站的地方都能去" (04-decisions.md): a named position is only claimed for the handful of
        // things that are genuinely owned and capacity-limited - a bed, the owner's coffee machine,
        // the student's window seat. Standing, chatting, observing, creating at the shared table,
        // inviting someone, celebrating - none of that needs a slot; positionId simply stays null and
        // the resident is loosely "at" the place, exactly where the frontend's own walkable-area
        // pathing already puts a standing actor. This is also why the garden's four named spots no
        // longer force four people into a pile - most of what happens there never claims one.
        String kind=preferredKind(action,place);
        if(kind==null){TownPlaces.release(w,r.id);return;}
        TownPlaces.Outcome outcome=TownPlaces.claim(w,r.id,place,kind,at);
        if(outcome==TownPlaces.Outcome.WAITING) {
            // Only a bed, counter, study seat or home desk reaches here, so this is the genuinely-scarce case. Stand by a moment instead of
            // being placed on top of someone.
            String waitReason="这里现在坐满了，先在旁边等一等";
            r.plan=new Plan("p-"+(++w.eventSequence),"wait",place,target,waitReason,at,at.plusSeconds(12));r.thought=waitReason;
            replaceActor(w,r.id,place,"wait",waitReason,r.plan.endsAt());
        }
    }
    /** Which actions still need a named, owned position claimed - see the "能站的地方都能去" note in
     * schedule() above. Null means this action never claims one at all, not merely "any spot will
     * do". */
    private static String preferredKind(String action,String place) {
        if(TownPlaces.isHome(place)&&Set.of("study","read","work","make").contains(action))return "desk";
        if("cafe".equals(place)&&Set.of("study","read","work","make").contains(action))return "seat";
        if("join".equals(action))return switch(place){case "cafe"->"seat";case "street","garden"->"bench";default->null;};
        return switch(action) {
            case "sleep"->"bed";
            case "tend"->"equipment";
            default->null;
        };
    }
    private static boolean canInvite(CompanionWorld w,ResidentState a,ResidentState b,Project p,Instant at) {
        if(!knows(w,a.id,p.id)||knownStatus(a,p.id).equals("celebrating")||p.members.contains(b.id))return false;
        Instant previous=p.invitationHistory.get(pairKey(a.id,b.id));
        if(previous==null)previous=w.conversations.stream().filter(c->c.topicId.equals(p.id)&&c.participantIds.contains(a.id)&&c.participantIds.contains(b.id)).map(c->c.startedAt).max(Comparator.naturalOrder()).orElse(null);
        // The inviter's own extroversion can only lengthen this cooldown (introverts wait longer to
        // re-approach the same person), never shorten it below the 900-second baseline.
        return previous==null||Duration.between(previous,at).getSeconds()>=Personality.of(a).inviteCooldownSeconds();
    }
    private static String pairKey(String a,String b){return a.compareTo(b)<0?a+":"+b:b+":"+a;}
    static void startConversation(CompanionWorld w,ResidentState a,ResidentState b,Project p,Instant at) {
        p.invitationHistory.put(pairKey(a.id,b.id),at);
        // A chat can be a detour through an already meaningful afternoon, rather than a command to
        // discard that afternoon altogether.
        suspend(a,at);suspend(b,at);
        Conversation c=new Conversation();c.id="c-"+(++w.eventSequence);c.place=actor(w,a.id).place();c.topicId=p.id;c.status="active";c.participantIds.add(a.id);c.participantIds.add(b.id);c.startedAt=at;c.updatedAt=at;
        // A production/model conversation never drops into the rule-authored agreement script just
        // because the provider is cooling down. ConversationLifecycle will end it neutrally if the
        // model is unavailable; only explicit rule-only historical runs use the template below.
        if(w.modelConversationsEnabled){
            c.mode="model";c.nextSpeakerId=a.id;w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
            replaceActor(w,a.id,c.place,"talk","想和"+actor(w,b.id).name()+"聊聊，正在组织语言",at.plusSeconds(45));
            replaceActor(w,b.id,c.place,"talk","停下手里的事，等对方开口",at.plusSeconds(45));
            event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"叫住了"+actor(w,b.id).name()+"。",p.id);
            while(w.conversations.size()>24)w.conversations.removeFirst();return;
        }
        String invitation=switch(p.objectKind){case "poster"->"你看这个，哪块颜色最像你？";case "flowers"->"有株苗多出来了。你窗边放得下吗？";case "tea"->"茶我泡了两种。你要不要尝一口？";default->"你那本读到一半的书，也带来吧？";};
        // A's own private fondness for b (never visible to b, and never sent to anyone else's model
        // context - see ResidentDirector.perspective()) can let itself show once, the first time it
        // is high enough; after that it does not keep repeating the same tell every single time.
        boolean fond=a.relationships.getOrDefault(b.id,40)>55;
        boolean alreadyShown=Boolean.TRUE.equals(a.affectionExpressed.get(b.id));
        if(fond&&!alreadyShown)a.affectionExpressed.put(b.id,true);
        String opener=fond&&!alreadyShown?"你上次说的，我还记着。":"";
        c.turns.add(new Turn(a.id,opener+invitation,at));
        w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
        replaceActor(w,a.id,c.place,"talk",c.turns.getFirst().text(),at.plusSeconds(36));replaceActor(w,b.id,c.place,"talk","停下手里的事，听听对方",at.plusSeconds(36));
        var shared=a.knownProjects.get(p.id);if(shared!=null)b.knownProjects.put(p.id,new ProjectKnowledge(shared.id(),shared.place(),shared.status(),shared.progress(),at,a.id));
        memory(w,b.id,a.id,"heard",at,p.id,actor(w,a.id).name()+"当面告诉我，正在准备「"+p.title+"」。",ownEvidence(w,a.id,p.id),7);
        event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"叫住了"+actor(w,b.id).name()+"，聊起「"+p.title+"」。",p.id);
        while(w.conversations.size()>24)w.conversations.removeFirst();
    }
    /** A conversation may be about a shift, a possible new life, or an unresolved arrangement;
     * it does not need a public project to be a legitimate encounter. */
    private static void startLifeConversation(CompanionWorld w,ResidentState a,ResidentState b,Instant at){
        suspend(a,at);suspend(b,at);Conversation c=new Conversation();c.id="c-"+(++w.eventSequence);c.place=actor(w,a.id).place();c.topicId="life";c.status="active";c.participantIds.add(a.id);c.participantIds.add(b.id);c.startedAt=at;c.updatedAt=at;c.mode=w.modelConversationsEnabled?"model":"fallback";c.nextSpeakerId=a.id;w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
        replaceActor(w,a.id,c.place,"talk","想聊聊手头的生活安排",at.plusSeconds(45));replaceActor(w,b.id,c.place,"talk","停下来听听对方的打算",at.plusSeconds(45));event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"和"+actor(w,b.id).name()+"聊起最近的生活安排。",null);
    }
    private static void continueConversation(CompanionWorld w,Conversation c,Instant at) {
        if(ConversationLifecycle.tick(w,c,at))return;
        if(Duration.between(c.updatedAt,at).getSeconds()<8)return;
        ResidentState a=state(w,c.participantIds.get(0)),b=state(w,c.participantIds.get(1));Project p=project(w,c.topicId);
        if(!actor(w,a.id).place().equals(c.place)||!actor(w,b.id).place().equals(c.place)){ConversationLifecycle.finish(w,c,at,"有人先离开了");return;}
        int n=c.stage;String speaker,text;
        if(n==1) {
            speaker=b.id;
            boolean reluctant=b.energy<40||b.id.equals("student")&&p.kind.equals("gathering")||b.relationships.getOrDefault(a.id,40)<32;
            c.accepted=!reluctant;
            text=reluctant?(b.id.equals("student")?"我还要复习。真要聚的话，给我留个安静点的位置。":"今天不行，我脑子已经转不动了。要不我只帮一小会儿？")
                :b.id.equals("artist")?"这块太整齐了。让我留点真的人住过的痕迹？"
                :b.id.equals("gardener")?"苗能放，不过得有人记得浇水。你管，还是我管？":"我能帮你理一理。先说最急的是哪件？";
            b.mood=reluctant?"有些犹豫":"被需要";relation(a,b,reluctant?-3:4);
        } else if(n==2) {
            speaker=a.id;
            if(!c.accepted && b.energy<32) {
                text="行，你先歇。";
                a.mood="更体谅了";
            } else if(!c.accepted) {
                text="那就把安静角留出来。别的先照旧。";
                p.description="听过"+actor(w,b.id).name()+"的顾虑，改成小规模准备，并给安静和休息留位置。";
                a.mood="重新想过了";c.accepted=true;
                event(w,at,"change_of_mind",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"因为"+actor(w,b.id).name()+"的顾虑，改了「"+p.title+"」的做法。",p.id);
            } else text=switch(b.id){case "artist"->"行，那块留给你。别画得太规矩。";case "gardener"->"浇水我来记。你帮我看看放哪儿合适。";case "student"->"安静的位置我留着。";default->"最急的是把材料归一下。别的先放着。";};
        } else if(n==3) {
            speaker=b.id;
            if(!c.accepted) {
                text="好，那我先歇。";
                c.turns.add(new Turn(speaker,text,at));c.stage++;c.updatedAt=at;relation(a,b,2);
                a.revision++;b.revision++;
                memory(w,a.id,b.id,"heard",at,p.id,actor(w,b.id).name()+"说今天想先休息，没有答应参与。",ownEvidence(w,a.id,p.id),6);
                event(w,at,"declined",c.place,List.of(a.id,b.id),actor(w,b.id).name()+"婉拒了这一次邀请，"+actor(w,a.id).name()+"决定不催促。",p.id);
                replaceActor(w,speaker,c.place,"talk",text,at.plusSeconds(12));return;
            }
            text=switch(b.id){case "student"->"行。等我把这页看完。";case "artist"->"行，留一块给我。";case "gardener"->"行，我晚点把苗拿来。";default->"行，我收完台面就来。";};
            if(!p.members.contains(b.id))p.members.add(b.id);b.goal=p.id;b.thought="听过对方的安排后，我愿意试着一起做一点。";
            relation(a,b,6);b.social=clamp(b.social+18);a.social=clamp(a.social+18);
            String evidence=memory(w,b.id,a.id,"heard",at,p.id,"我们当面商量过「"+p.title+"」，对方接受了我的想法，我答应做一小部分。",ownEvidence(w,b.id,p.id),8);
            memory(w,a.id,b.id,"heard",at,p.id,actor(w,b.id).name()+"当面答应参与「"+p.title+"」。",List.of(evidence),8);
            event(w,at,"agreement",c.place,List.of(a.id,b.id),actor(w,b.id).name()+"答应参与「"+p.title+"」，不是旁观者了。",p.id);
        } else {
            ConversationLifecycle.finish(w,c,at,"各自继续手上的事");return;
        }
        c.turns.add(new Turn(speaker,text,at));c.stage++;c.updatedAt=at;a.revision++;b.revision++;replaceActor(w,speaker,c.place,"talk",text,at.plusSeconds(24));
    }
    // reflect() and newWish() used to live here. Both were already unreachable - nothing called
    // either one - and they are removed rather than moved so the next batch builds reflection from
    // scratch instead of inheriting a corpse. What the metrics counted as "reflections" was never
    // this method: it is applyDecision() below storing the model's own stated reason for an action
    // as a reflection-typed memory. The town has had no reflection mechanism at all.

    public static boolean proposeDecision(CompanionWorld w,String id,long residentRevision,long intentRevision,String place,String title,String objectKind,String reason,List<String> evidence,Instant now) {
        ResidentState r=state(w,id);
        if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision||!TownPlaces.contains(w,place)||TownPlaces.isHome(place))return false;
        if(title==null||title.isBlank()||title.length()>36||reason==null||reason.length()>160||!Set.of("poster","flowers","books","tea").contains(objectKind==null?"":objectKind))return false;
        if(evidence==null||evidence.isEmpty()||evidence.stream().anyMatch(e->w.memories.stream().noneMatch(m->m.ownerId().equals(id)&&m.id().equals(e))))return false;
        if(w.projects.stream().filter(p->p.ownerId.equals(id)&&!p.status.equals("celebrating")).count()>=2)return false;
        propose(w,r,place,title,objectKind,reason,evidence,now);w.modelStatus="模型刚让"+actor(w,id).name()+"想到了一个新愿望";w.revision++;return true;
    }
    private static void propose(CompanionWorld w,ResidentState r,String place,String title,String kind,String reason,List<String> evidence,Instant now) {
        String id="wish-"+(++w.eventSequence);
        project(w,id,title,"shared",place,r.id,kind,reason,2);r.knownProjects.put(id,new ProjectKnowledge(id,place,"idea",0,now,r.id));r.goal=id;r.thought=reason;r.mood="又有了一个小主意";r.revision++;
        memory(w,r.id,r.id,"observed",now,id,"我在纸上写下一个还没实现的愿望：「"+title+"」。",List.of(),7);
        if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,id,reason,evidence,8);
        event(w,now,"new_wish",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"写下一个新愿望：「"+title+"」。",id);
        while(w.projects.size()>16){Project old=w.projects.stream().filter(p->p.status.equals("celebrating")).findFirst().orElse(null);if(old==null)break;w.projects.remove(old);}
        while(w.objects.size()>20)w.objects.removeFirst();
    }
    /** An offer records only one side of a social arrangement.  It does not grant equipment use;
     * acceptance below is intentionally a separate, auditable transition. */
    public static boolean proposeWorkArrangement(CompanionWorld w,String proposerId,String kind,String otherId,String note,Instant now){
        return proposeWorkArrangement(w,proposerId,kind,otherId,note,List.of(),now);
    }
    public static boolean proposeWorkArrangement(CompanionWorld w,String proposerId,String kind,String otherId,String note,List<String> evidence,Instant now){
        ResidentState proposer=state(w,proposerId),other=state(w,otherId);String operator=CafeService.operatorId(w);
        if(proposer==null||other==null||proposerId.equals(otherId)||!Set.of("assist","delegate","takeover").contains(kind)||note==null||note.isBlank()||note.length()>160)return false;
        boolean valid="assist".equals(kind)?Objects.equals(otherId,operator):Objects.equals(proposerId,operator);
        if(!valid)return false;
        WorkArrangement a=new WorkArrangement();a.id="work-"+(++w.eventSequence);a.kind=kind;a.place="cafe";a.proposerId=proposerId;a.workerId="assist".equals(kind)?proposerId:otherId;a.status="proposed";a.note=note;a.proposedAt=now;a.proposerEvidenceIds.addAll(evidence);w.workArrangements.add(a);
        memory(w,proposerId,proposerId,"observed",now,"work","assist".equals(kind)?"我提出可以替对方照看一阵吧台。":"我提出把吧台的责任交给对方。",List.of(),6);
        event(w,now,"work_offer","cafe",List.of(proposerId,otherId),actor(w,proposerId).name()+"提出了关于咖啡馆的安排。",null);return true;
    }
    /** Both people must say yes before the helper can use the counter.  A takeover additionally
     * transfers the concrete counter owner; delegation and help remain use permissions only. */
    public static boolean acceptWorkArrangement(CompanionWorld w,String accepterId,String arrangementId,Instant now){
        return acceptWorkArrangement(w,accepterId,arrangementId,List.of(),now);
    }
    public static boolean acceptWorkArrangement(CompanionWorld w,String accepterId,String arrangementId,List<String> evidence,Instant now){
        WorkArrangement a=w.workArrangements.stream().filter(x->Objects.equals(x.id,arrangementId)&&"proposed".equals(x.status)).findFirst().orElse(null);
        if(a==null)return false;String operator=CafeService.operatorId(w);String required="assist".equals(a.kind)?operator:a.workerId;
        if(!Objects.equals(required,accepterId))return false;
        a.status="active";a.acceptedAt=now;a.workerEvidenceIds.addAll(evidence);
        if("takeover".equals(a.kind)){String former=CafeService.operatorId(w);TownPlaces.transferCafeCounter(w,a.workerId);w.cafeOperating=true;state(w,a.workerId).occupation="经营咖啡馆";state(w,a.workerId).careerIntent=lifeIntent(w,state(w,a.workerId),null,"试着把咖啡馆经营下去","active",now);replaceRole(w,a.workerId,"咖啡馆经营者");if(!former.equals(a.workerId))replaceRole(w,former,"前店主，正在找新的生活方向");}
        else {state(w,a.workerId).occupation="assist".equals(a.kind)?"在高峰时替人照看吧台":"受托照看咖啡馆";replaceRole(w,a.workerId,"assist".equals(a.kind)?"咖啡馆帮工":"受托经营者");}
        memory(w,accepterId,accepterId,"observed",now,"work","我明确答应了这份关于咖啡馆的安排。",List.of(),7);
        event(w,now,"work_agreement","cafe",List.of(a.proposerId,accepterId),"关于咖啡馆的安排被两个人说清楚了。",null);return true;
    }
    /** Either participant may decline an offer or end an active arrangement; ended arrangements
     * never regain equipment authority if the counter later reopens. */
    public static boolean endWorkArrangement(CompanionWorld w,String residentId,String arrangementId,String status,Instant now){
        WorkArrangement a=w.workArrangements.stream().filter(x->Objects.equals(x.id,arrangementId)&&Set.of("proposed","active").contains(x.status)).findFirst().orElse(null);
        if(a==null||!Set.of("rejected","ended").contains(status)||(!residentId.equals(a.proposerId)&&!residentId.equals(a.workerId)&&!residentId.equals(CafeService.operatorId(w))))return false;
        a.status=status;a.endedAt=now;event(w,now,"work_"+status,"cafe",List.of(residentId),actor(w,residentId).name()+"没有继续这份咖啡馆安排。",null);return true;
    }
    /** Choosing another life can close the counter.  It never invents a replacement resident. */
    public static boolean changeOccupation(CompanionWorld w,String residentId,String occupation,Instant now){
        return changeOccupation(w,residentId,occupation,null,now);
    }
    public static boolean changeOccupation(CompanionWorld w,String residentId,String occupation,String closingSpeech,Instant now){
        ResidentState r=state(w,residentId);if(r==null||occupation==null||occupation.isBlank()||occupation.length()>80)return false;
        if(Objects.equals(CafeService.operatorId(w),residentId)){
            if("open".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place())&&closingSpeech!=null&&!closingSpeech.isBlank())CafeService.closeForDay(w,residentId,closingSpeech,now);
            CafeService.pauseOperation(w,residentId,now);
            for(Conversation conversation:new ArrayList<>(w.conversations))if("active".equals(conversation.status)&&"cafe".equals(conversation.place))ConversationLifecycle.finish(w,conversation,now,"经营者暂停营业，这段谈话先停在这里");
            moveFocusedAvatarHome(w,now);
        }
        r.occupation=occupation;r.goal=null;r.plan=null;r.suspendedAction=null;TownPlaces.release(w,residentId);r.careerIntent=lifeIntent(w,r,null,"试着过上"+occupation+"的日子","active",now);
        if(Objects.equals(CafeService.operatorId(w),residentId))replaceRole(w,residentId,"正在转向"+occupation);
        memory(w,residentId,residentId,"reflection",now,"work","我想把日子往“"+occupation+"”的方向试一试，先不把这当成已经成功。",List.of(),7);
        event(w,now,"occupation_change",actor(w,residentId).place(),List.of(residentId),actor(w,residentId).name()+"正在重新想自己想做什么。",null);return true;
    }
    /** Called only after an authorized open_cafe action physically completes. The prior direction is
     * kept in the new description instead of being silently erased by returning to the counter. */
    static void markCafeReturned(CompanionWorld w,String residentId,String reason,Instant at){
        if(!Objects.equals(CafeService.operatorId(w),residentId))return;
        ResidentState r=state(w,residentId);if(r==null)return;
        String previous=r.occupation==null||r.occupation.isBlank()?"未记录":r.occupation;
        if(previous.startsWith("恢复咖啡馆经营；此前方向："))previous=previous.substring("恢复咖啡馆经营；此前方向：".length());
        String chosenReason=reason==null||reason.isBlank()?"本人选择重新开门":reason;
        r.occupation="恢复咖啡馆经营；此前方向："+previous;
        r.careerIntent=lifeIntent(w,r,null,"恢复咖啡馆经营："+chosenReason+"；此前方向："+previous,"active",at);r.lifeIntent=lifeIntent(w,r,null,chosenReason,"active",at);
        replaceRole(w,residentId,"咖啡馆经营者");r.revision++;
    }
    public record PortableAction(String action,String reason,int remainingSeconds) {}

    /** Only unusually strong body signals enter the resident's conscious context. Internal values
     * remain simulation state and are never exposed to the language model. */
    public static List<String> salientPerceptions(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null)return List.of();List<String> result=new ArrayList<>();
        if(r.energy<=8)result.add("困得几乎做不了需要专注的事");
        else if(r.energy<=20)result.add("已经很累，注意力很难维持");
        if("closing".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place()))result.add("咖啡馆正在打烊，店里不再接新单");
        else if("closed".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place()))result.add("咖啡馆已经关门");
        if(CafeService.mayTend(w,residentId)&&"cafe".equals(actor(w,residentId).place())&&CafeService.oldestWaitingRequestId(w)!=null)result.add("柜台前有人在等");
        ServiceRequest ownWait=w.serviceRequests.stream().filter(request->residentId.equals(request.requesterId)&&"waiting".equals(request.status)&&"cafe".equals(actor(w,residentId).place())).findFirst().orElse(null);
        if(ownWait!=null&&Duration.between(ownWait.requestedAt,at).getSeconds()>=120)result.add("这杯已经等了一阵，还没有人来做");
        return List.copyOf(result);
    }
    public static String cafeScheduleCue(CompanionWorld w,String residentId,Instant at){return CafeService.scheduleCue(w,residentId,at);}
    public static List<String> routineCues(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null||!r.sleepScheduleSeeded||"sleep".equals(actor(w,residentId).activity()))return List.of();
        ZonedDateTime local=at.atZone(ZoneId.of(w.timezone));int minute=local.getHour()*60+local.getMinute();
        boolean inWindow=r.usualSleepMinute<r.usualWakeMinute?minute>=r.usualSleepMinute&&minute<r.usualWakeMinute:minute>=r.usualSleepMinute||minute<r.usualWakeMinute;
        return inWindow?List.of("到了我平常睡觉的时间"):List.of();
    }
    public static String cafeNotice(CompanionWorld w,String residentId){
        if(!"closing".equals(w.cafeStatus)||!"cafe".equals(actor(w,residentId).place()))return null;
        return CafeService.latestClosingNotice(w,residentId);
    }
    private static boolean canTalkTo(CompanionWorld w,String speakerId,String otherId){
        ResidentState other=state(w,otherId);if(other==null||speakerId.equals(otherId)||activeConversation(w,otherId)!=null)return false;
        // The avatar is only ever a valid conversation partner during its own free/autonomous time
        // (item 7) - never while the user is explicitly directing it, so a rule- or model-triggered
        // chat can never yank the avatar out of a focus session or an explicit intent.
        if("self".equals(otherId)&&!selfIsFree(w))return false;
        Actor speaker=actor(w,speakerId),candidate=actor(w,otherId);
        return speaker.place().equals(candidate.place())&&!Set.of("walk","travel","sleep","rest","away","tend").contains(candidate.activity());
    }
    /** Rule-detected "just ran into someone" (item 3, docs/01's 偶遇): fires when `arriving` finishes
     * travelling into a place where another eligible resident already is. The model still writes every
     * word - this only opens the conversation (via the same {@link #startLifeConversation} a manual
     * "invite" without a shared project already uses), it never authors a line of dialogue. Gated on
     * {@link CompanionWorld#modelConversationsEnabled}: a rule-only world has no model to write an
     * opening turn with, so it does not manufacture one.
     * <p>Deliberately NOT implemented: the "cross paths while walking" half of item 3's description.
     * Every traveller's activity is "walk" for the entire trip (see {@link #moveOrSchedule}), and the
     * same item's own bound forbids triggering while either party is walking - the two requirements
     * are in direct tension, and resolving it by literally stopping two people mid-walk would violate
     * the bound. Rather than guess, only the unambiguous "one arrives where the other already is" case
     * is implemented; see the batch report for this call. */
    private static void maybeEncounter(CompanionWorld w,ResidentState arriving,Instant at){
        if(!w.modelConversationsEnabled||activeConversation(w,arriving.id)!=null)return;
        Actor here=actor(w,arriving.id);
        for(ResidentState other:w.residentStates){
            if(other.id.equals(arriving.id)||!actor(w,other.id).place().equals(here.place()))continue;
            if(!canTalkTo(w,arriving.id,other.id)||!canTalkTo(w,other.id,arriving.id))continue;
            String key=pairKey(arriving.id,other.id);
            Instant last=w.encounterCooldowns.get(key);
            if(last!=null&&Duration.between(last,at).getSeconds()<ENCOUNTER_COOLDOWN_SECONDS)continue;
            w.encounterCooldowns.put(key,at);
            startLifeConversation(w,arriving,other,at);
            recordDecisionTrigger(w,arriving.id,"encounter",at);
            recordDecisionTrigger(w,other.id,"encounter",at);
            return; // one encounter per arrival is enough
        }
    }
    /** End-of-day reconciliation for a resident's coarse day plan (item 4): once the local calendar
     * date has moved past the day this plan was formed for, any segment still "pending" (or somehow
     * left "active") is a plan reality wrecked rather than one the resident finished - it becomes
     * exactly one reflection memory naming what did not happen, never a silent success and never a
     * scolding. A fresh plan is formed the next morning through the normal ResidentDirector "dayplan"
     * work item; this method never creates one itself. */
    private static void reconcileDayPlan(CompanionWorld w,ResidentState r,Instant at){
        if(r.dayPlan==null)return;
        String today=at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
        if(today.equals(r.dayPlan.day))return;
        var missed=r.dayPlan.segments.stream().filter(s->!"done".equals(s.status)&&!"skipped".equals(s.status)).map(s->s.label).toList();
        if(!missed.isEmpty())memory(w,r.id,r.id,"reflection",at,null,"今天原本想"+String.join("、",missed)+"，一天过去了，没顾上。",List.of(),6);
        r.dayPlan=null;
    }
    /** A defensive no-op for a {@link ResidentMind} that does not implement day planning at all (the
     * interface's own default throws {@code UnsupportedOperationException} - see
     * {@link com.betterself.growth.town.companion.application.ResidentMind#planDay}). Recorded exactly
     * like a real day plan with zero segments so {@link #reconcileDayPlan} has nothing to call missed
     * and {@code needsDayPlan} in ResidentDirector stops re-asking for the rest of today, without ever
     * touching the world's model-failure/backoff bookkeeping - an unsupported capability is not the
     * same kind of failure as a real network or parsing error. */
    public static void markDayPlanUnavailableForToday(CompanionWorld w,String residentId,Instant now){
        ResidentState r=state(w,residentId);if(r==null)return;
        CompanionWorld.DayPlan plan=new CompanionWorld.DayPlan();
        plan.id="dayplan-unavailable-"+(++w.eventSequence);plan.day=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();plan.formedAt=now;
        r.dayPlan=plan;
    }
    /** Applies a model-proposed day plan (item 4). Coarse on purpose - three or four short segments,
     * never a time-slotted schedule; ResidentSimulation never checks elapsed real time against them,
     * only whether one is still pending when the day rolls over (see {@link #reconcileDayPlan}). */
    public static boolean applyDayPlan(CompanionWorld w,String residentId,long residentRevision,List<String> segments,List<String> evidence,Instant now){
        ResidentState r=state(w,residentId);
        if(r==null||r.revision!=residentRevision||segments==null||segments.size()<3||segments.size()>4)return false;
        for(String s:segments)if(s==null||s.isBlank()||s.length()>40)return false;
        if(evidence!=null&&evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        CompanionWorld.DayPlan plan=new CompanionWorld.DayPlan();
        plan.id="dayplan-"+(++w.eventSequence);plan.day=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();plan.formedAt=now;
        for(String s:segments){CompanionWorld.DaySegment seg=new CompanionWorld.DaySegment();seg.label=s;plan.segments.add(seg);}
        r.dayPlan=plan;r.revision++;w.revision++;
        event(w,now,"day_plan",actor(w,residentId).place(),List.of(residentId),actor(w,residentId).name()+"给今天理了个大致的想法。",null);
        return true;
    }
    public static PortableAction portableAction(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null||!"closing".equals(w.cafeStatus)||!"cafe".equals(actor(w,residentId).place()))return null;
        Plan plan=portablePlan(r);if(plan==null)return null;
        int remaining=(int)Math.max(1,Duration.between(at,plan.endsAt()).getSeconds());
        if(r.suspendedAction!=null&&plan==r.suspendedAction.plan)remaining=(int)Math.max(1,Duration.between(plan.startedAt(),plan.endsAt()).getSeconds());
        return new PortableAction(plan.action(),plan.reason(),remaining);
    }
    public static List<String> availableActions(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null)return List.of();
        LinkedHashSet<String> actions=new LinkedHashSet<>(List.of("observe","rest","study","work","read","make","sleep","away","change_work"));
        if(r.plan!=null&&!("cafe".equals(actor(w,residentId).place())&&!"open".equals(w.cafeStatus)))actions.add("continue");
        if(w.projects.stream().anyMatch(p->knows(w,residentId,p.id)&&!Set.of("ready","celebrating").contains(p.status)))actions.add("create");
        if(w.projects.stream().anyMatch(p->knows(w,residentId,p.id)&&p.members.contains(residentId)&&!Set.of("ready","celebrating").contains(p.status)))actions.add("help");
        if(w.residentStates.stream().anyMatch(other->canTalkTo(w,residentId,other.id))){actions.add("invite");actions.add("join");}
        if(CafeService.mayTend(w,residentId)&&"cafe".equals(actor(w,residentId).place())&&CafeService.oldestWaitingRequestId(w)!=null)actions.add("tend");
        if(CafeService.acceptingOrders(w)&&"cafe".equals(actor(w,residentId).place())&&!residentId.equals(CafeService.operatorId(w))
            &&w.serviceRequests.stream().noneMatch(request->residentId.equals(request.requesterId)&&Set.of("waiting","preparing","delivered").contains(request.status)))actions.add("request_drink");
        if(CafeService.mayManage(w,residentId)&&"closed".equals(w.cafeStatus))actions.add("open_cafe");
        if(CafeService.mayManage(w,residentId)&&"open".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place())&&!"sleep".equals(actor(w,residentId).activity()))actions.add("close_cafe");
        if(portableAction(w,residentId,at)!=null)actions.add("continue_home");
        PausedAction paused=pausedAction(w,residentId,at);if(paused!=null&&(!"cafe".equals(paused.place())||"open".equals(w.cafeStatus)))actions.add("resume");
        if(w.projects.stream().filter(project->residentId.equals(project.ownerId)&&!"celebrating".equals(project.status)).count()<2)actions.add("propose");
        return List.copyOf(actions);
    }
    public static boolean configureCafeHours(CompanionWorld w,int openMinute,int closeMinute){
        if(openMinute<0||openMinute>=1440||closeMinute<0||closeMinute>=1440||openMinute==closeMinute)return false;
        w.cafeOpenMinute=openMinute;w.cafeCloseMinute=closeMinute;w.revision++;return true;
    }
    public static boolean configureSleepHours(CompanionWorld w,String residentId,int sleepMinute,int wakeMinute){
        ResidentState r=state(w,residentId);if(r==null||sleepMinute<0||sleepMinute>=1440||wakeMinute<0||wakeMinute>=1440||sleepMinute==wakeMinute)return false;
        r.usualSleepMinute=sleepMinute;r.usualWakeMinute=wakeMinute;r.sleepScheduleSeeded=true;r.revision++;w.revision++;return true;
    }
    private static final Set<String> PORTABLE_ACTIONS=Set.of("study","read","work","make");
    public record PausedAction(String action,String place,String reason,int remainingSeconds) {}
    public static PausedAction pausedAction(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null||r.suspendedAction==null||r.suspendedAction.plan==null)return null;
        Plan plan=r.suspendedAction.plan;int remaining=(int)Math.max(1,Duration.between(plan.startedAt(),plan.endsAt()).getSeconds());String effectivePlace=plan.place();
        if(PORTABLE_ACTIONS.contains(plan.action())&&"cafe".equals(plan.place())&&!"open".equals(w.cafeStatus)&&TownPlaces.isHome(actor(w,residentId).place()))effectivePlace=TownPlaces.homeOf(residentId);
        return new PausedAction(plan.action(),effectivePlace,plan.reason(),remaining);
    }
    private static Plan portablePlan(ResidentState r){
        if(r.plan!=null&&PORTABLE_ACTIONS.contains(r.plan.action()))return r.plan;
        return r.suspendedAction!=null&&r.suspendedAction.plan!=null&&PORTABLE_ACTIONS.contains(r.suspendedAction.plan.action())?r.suspendedAction.plan:null;
    }
    private static boolean continueAtHome(CompanionWorld w,ResidentState r,Instant at){
        PortableAction view=portableAction(w,r.id,at);Plan plan=portablePlan(r);if(view==null||plan==null)return false;
        String target=plan.targetId();r.plan=null;r.suspendedAction=null;
        moveOrSchedule(w,r,view.action(),TownPlaces.homeOf(r.id),target,view.reason(),at,view.remainingSeconds());return true;
    }
    private static boolean closeCafe(CompanionWorld w,ResidentState manager,String speech,Instant at){
        if(!CafeService.closeForDay(w,manager.id,speech,at))return false;
        for(Conversation c:new ArrayList<>(w.conversations))if("active".equals(c.status)&&"cafe".equals(c.place))ConversationLifecycle.finish(w,c,at,"店里开始打烊，这段谈话先停在这里");
        if(manager.plan!=null&&("tend".equals(manager.plan.action())||"travel".equals(manager.plan.action())&&"tend".equals(manager.desiredAction))){manager.plan=null;manager.suspendedAction=null;}
        moveFocusedAvatarHome(w,at);return true;
    }
    private static void moveFocusedAvatarHome(CompanionWorld w,Instant at){
        if(w.avatar==null||!"cafe".equals(w.avatar.place()))return;
        Actor a=w.avatar;String home=TownPlaces.homeOf("self");
        w.avatar=new Actor(a.id(),a.name(),a.role(),home,a.activity(),a.label(),a.x(),a.y(),a.until());
        TownPlaces.release(w,"self");TownPlaces.claim(w,"self",home,Set.of("focus","study").contains(a.activity())?"desk":null,at);
    }
    private static int sleepDurationSeconds(CompanionWorld w,ResidentState r,Instant at){
        ZonedDateTime local=at.atZone(ZoneId.of(w.timezone));int wakeMinute=r.sleepScheduleSeeded?r.usualWakeMinute:7*60;
        ZonedDateTime wake=local.withHour(wakeMinute/60).withMinute(wakeMinute%60).withSecond(0).withNano(0);
        if(!wake.isAfter(local))wake=wake.plusDays(1);
        if(!routineCues(w,r.id,at).isEmpty())return (int)Math.max(90*60,Duration.between(local,wake).getSeconds());
        double hours=Math.max(1.5,Math.min(4.0,(65-r.energy)/10.0));return (int)Math.round(hours*3600);
    }
    private static final Set<String> DECISION_ACTIONS=Set.of("continue","resume","observe","create","help","invite","join","rest","sleep","study","work","read","make","request_drink","tend","open_cafe","close_cafe","continue_home","away","offer_assist","offer_delegate","offer_takeover","accept_work","change_work");
    public static boolean applyDecision(CompanionWorld w,String residentId,long residentRevision,long intentRevision,String place,String action,String target,String reason,String speech,List<String> evidence,Instant now) {
        ResidentState r=state(w,residentId);if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision||!DECISION_ACTIONS.contains(action))return false;
        if(reason==null||reason.isBlank()||reason.length()>160||speech!=null&&speech.length()>180)return false;
        if(evidence==null||evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        // "away" (item 8) genuinely leaves the map: its own place is a sentinel, not one of the four
        // real locations, so it is handled before the generic place-containment check below ever runs.
        if("away".equals(action)){
            if(activeConversation(w,residentId)!=null)return false;
            if(r.plan!=null)suspend(r,now);
            TownPlaces.release(w,residentId);
            int duration=Math.max(600,Math.min(2700,600+Math.floorMod(reason.hashCode()+(int)now.getEpochSecond(),2100)));
            r.plan=new Plan("p-"+(++w.eventSequence),"away","away",target,reason,now,now.plusSeconds(duration));
            r.revision++;r.thought=reason;
            replaceActor(w,residentId,"away","away",reason,r.plan.endsAt());
            if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,r.goal,reason,evidence,7);
            w.modelStatus="模型刚让"+actor(w,residentId).name()+"暂时出门了";w.revision++;
            event(w,now,"away","street",List.of(residentId),actor(w,residentId).name()+"出门去处理自己的事，暂时不在小街上。",target);
            return true;
        }
        // The model still speaks of "home" generically; the resident's own home is what that resolves to.
        String resolvedPlace="home".equals(place)?TownPlaces.homeOf(residentId):place;
        if(!TownPlaces.contains(w,resolvedPlace)||TownPlaces.isHome(resolvedPlace)&&!resolvedPlace.equals(TownPlaces.homeOf(residentId)))return false;
        if("continue".equals(action)){
            if("cafe".equals(actor(w,residentId).place())&&!"open".equals(w.cafeStatus))return false;
            if(r.plan!=null){r.thought=reason;return appliedThought(w,r,residentId,reason,r.plan.targetId(),now);}
            if(r.suspendedAction==null)return false;Plan paused=r.suspendedAction.plan;resumeSuspended(w,r,now);
            if(r.plan==null)return false;return appliedThought(w,r,residentId,reason,paused==null?null:paused.targetId(),now);
        }
        if("continue_home".equals(action)){
            if(!resolvedPlace.equals(TownPlaces.homeOf(residentId))||!continueAtHome(w,r,now))return false;
            return appliedThought(w,r,residentId,reason,null,now);
        }
        if("resume".equals(action)){
            PausedAction paused=pausedAction(w,residentId,now);if(paused==null||!resolvedPlace.equals(paused.place())||("cafe".equals(paused.place())&&!"open".equals(w.cafeStatus)))return false;
            Plan stored=r.suspendedAction.plan;
            if(!stored.place().equals(paused.place()))r.suspendedAction.plan=new Plan(stored.id(),stored.action(),paused.place(),stored.targetId(),stored.reason(),stored.startedAt(),stored.endsAt());
            r.plan=null;resumeSuspended(w,r,now);if(r.plan==null)return false;
            return appliedThought(w,r,residentId,reason,r.plan.targetId(),now);
        }
        if("close_cafe".equals(action)){
            if(!"cafe".equals(resolvedPlace)||!closeCafe(w,r,speech,now))return false;
            return appliedThought(w,r,residentId,reason,null,now);
        }
        if("open_cafe".equals(action)){
            if(!"cafe".equals(resolvedPlace)||!CafeService.mayManage(w,residentId)||!"closed".equals(w.cafeStatus))return false;
            if(r.plan!=null)suspend(r,now);
            moveOrSchedule(w,r,"open_cafe","cafe",null,reason,now,30);
            return appliedThought(w,r,residentId,reason,null,now);
        }
        if("request_drink".equals(action)){
            if(!"cafe".equals(resolvedPlace)||!"cafe".equals(actor(w,residentId).place())||!availableActions(w,residentId,now).contains("request_drink"))return false;
            Plan current=r.plan;int before=w.serviceRequests.size();CafeService.request(w,r,now);if(w.serviceRequests.size()==before)return false;
            if(current==null)moveOrSchedule(w,r,"rest","cafe",w.serviceRequests.getLast().id,"等刚才点的饮料",now,1200);
            else if(!"rest".equals(current.action())){suspend(r,now);moveOrSchedule(w,r,"rest","cafe",w.serviceRequests.getLast().id,"等刚才点的饮料",now,1200);}
            return appliedThought(w,r,residentId,reason,w.serviceRequests.getLast().id,now);
        }
        if("cafe".equals(resolvedPlace)&&!"open".equals(w.cafeStatus))return false;
        if("sleep".equals(action)&&!resolvedPlace.equals(TownPlaces.homeOf(residentId)))return false;
        if(Set.of("create","help").contains(action)){Project p=project(w,target);if(p==null||!knows(w,r.id,p.id)||!p.place.equals(resolvedPlace)||Set.of("ready","celebrating").contains(p.status))return false;}
        if(action.equals("invite")&&(target==null||!canTalkTo(w,residentId,target)))return false;
        // "join" (item 3): sit down with someone already there. It is a physical positioning choice,
        // not itself a conversation - it deliberately reuses canTalkTo's same-place/available check
        // rather than requiring the stricter "no one is already mid-conversation with them" nuance
        // invite needs, since sitting near someone who is quietly reading is perfectly ordinary.
        if(action.equals("join")&&(target==null||!canTalkTo(w,residentId,target)||!resolvedPlace.equals(actor(w,target).place())))return false;
        if(action.equals("tend")){
            if(!"cafe".equals(resolvedPlace)||!CafeService.mayTend(w,residentId))return false;
            if(target==null)target=CafeService.oldestWaitingRequestId(w);
            String requestId=target;
            if(requestId==null||w.serviceRequests.stream().noneMatch(request->requestId.equals(request.id)&&"waiting".equals(request.status)&&"cafe".equals(request.place)))return false;
            if(r.plan!=null)suspend(r,now);CafeService.beginPreparing(w,target,now);
        }
        if(Set.of("offer_assist","offer_delegate","offer_takeover","accept_work").contains(action))return false;
        if(action.equals("change_work")){if(!changeOccupation(w,residentId,reason,speech,now))return false;return appliedThought(w,r,residentId,reason,null,now);}
        // Model may enrich this resident's current speaking turn; never invent the other party's reply.
        Conversation c=activeConversation(w,residentId);
        if(c!=null) {
            if(speech==null||speech.isBlank()||!actor(w,r.id).place().equals(c.place))return false;
            c.turns.add(new Turn(r.id,speech,now));c.updatedAt=now;
            for(String listener:c.participantIds)if(!listener.equals(r.id)&&actor(w,listener).place().equals(c.place))
                memory(w,listener,r.id,"heard",now,c.topicId,actor(w,r.id).name()+"当面说：“"+speech+"”",evidence,6);
            replaceActor(w,r.id,c.place,"talk",speech,now.plusSeconds(24));
        } else {
            if(Set.of("rest","sleep").contains(action)&&r.plan!=null){
                suspend(r,now);
                if(!"open".equals(w.cafeStatus)&&"cafe".equals(actor(w,r.id).place())&&TownPlaces.isHome(resolvedPlace)
                    &&r.suspendedAction!=null&&r.suspendedAction.plan!=null&&PORTABLE_ACTIONS.contains(r.suspendedAction.plan.action())){
                    Plan paused=r.suspendedAction.plan;
                    r.suspendedAction.plan=new Plan(paused.id(),paused.action(),TownPlaces.homeOf(r.id),paused.targetId(),paused.reason(),paused.startedAt(),paused.endsAt());
                }
            }
            else if(!"tend".equals(action))r.suspendedAction=null;
            if(Set.of("work","read","make").contains(action))setLifeIntent(w,r,null,reason,"active",now);
            int duration="tend".equals(action)?CafeService.PREP_SECONDS:"sleep".equals(action)?sleepDurationSeconds(w,r,now):"rest".equals(action)?1200:"join".equals(action)?900:Set.of("study","read","work","make").contains(action)?1800:60;
            moveOrSchedule(w,r,action,resolvedPlace,target,reason,now,duration);
        }
        if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,r.goal,reason,evidence,7);
        w.modelStatus="模型刚为"+actor(w,r.id).name()+"补充了一个念头";w.revision++;r.revision++;
        event(w,now,"thought",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"想了想："+reason,target);
        return true;
    }
    private static boolean appliedThought(CompanionWorld w,ResidentState r,String residentId,String reason,String target,Instant now){w.modelStatus="模型刚为"+actor(w,residentId).name()+"补充了一个念头";w.revision++;r.revision++;event(w,now,"thought",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"想了想："+reason,target);return true;}
    private static void perceive(CompanionWorld w,ResidentState r,Instant now) {
        Actor a=actor(w,r.id);if(a.activity().equals("walk")||a.activity().equals("sleep"))return;
        boolean detailSensitive=Personality.of(r).sensitivity()>=65;
        for(Project p:w.projects)if(p.place.equals(a.place())&&(p.progress>0||knows(w,r.id,p.id))) {
            ProjectKnowledge prior=r.knownProjects.get(p.id);
            boolean changed=prior!=null&&!prior.status().equals(p.status);
            r.knownProjects.put(p.id,new ProjectKnowledge(p.id,p.place,p.status,p.progress,now,r.id));
            // Only residents who pay close attention to detail bother writing down an ambient change
            // in something they were not part of; everyone else's private tracking above still
            // updates, just silently and unrecorded - it never becomes a memory they can retrieve.
            if(changed&&detailSensitive&&!p.contributors.contains(r.id))
                memory(w,r.id,p.ownerId,"observed",now,p.id,"路过时注意到「"+p.title+"」的样子变了，好像又往前推进了一点。",List.of(),4);
        }
    }
    private static String knownStatus(ResidentState r,String id){ProjectKnowledge p=r.knownProjects.get(id);return p==null?"idea":p.status();}
    public static String knownPlace(ResidentState r,Project p){ProjectKnowledge known=r.knownProjects.get(p.id);return known==null?p.place:known.place();}
    public static ResidentState state(CompanionWorld w,String id){return w.residentStates.stream().filter(r->r.id.equals(id)).findFirst().orElse(null);}
    public static boolean mayTend(CompanionWorld w,String id){return CafeService.mayTend(w,id);}
    public static String cafeOperatorId(CompanionWorld w){return CafeService.operatorId(w);}
    /** Explicit authoring entry point for a future manually designed neighbour.  No simulation path
     * calls this and there is no automatic migration/recruitment: callers supply their id, name,
     * role and livelihood description, then the resident gets the same neutral state/home/plan
     * machinery as everyone else. */
    public static Actor actor(CompanionWorld w,String id){return "self".equals(id)?w.avatar:w.residents.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow();}
    public static Project project(CompanionWorld w,String id){return w.projects.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);}
    public static boolean knows(CompanionWorld w,String id,String topic){return w.memories.stream().anyMatch(m->m.ownerId().equals(id)&&Objects.equals(m.topicId(),topic)&&!m.sourceType().equals("reflection"));}
    public static Conversation activeConversation(CompanionWorld w,String id){return w.conversations.stream().filter(c->c.status.equals("active")&&c.participantIds.contains(id)).findFirst().orElse(null);}
    /** Each side's own private affection number moves independently, scaled by that side's own
     * emotional volatility - not by the same shared delta. A applies its own scaled change to its own
     * view of B, and B applies its own (generally different) scaled change to its own view of A;
     * neither ever reads or writes the other's number. Over many events with different partners and
     * different histories this is enough for A's feeling about B and B's feeling about A to genuinely
     * diverge - without hand-scripting who likes whom. */
    static void relation(ResidentState a,ResidentState b,int delta){
        bump(a,b.id,scaledDelta(a,delta));
        bump(b,a.id,scaledDelta(b,delta));
    }
    private static void bump(ResidentState owner,String otherId,int delta){owner.relationships.compute(otherId,(k,v)->Math.max(0,Math.min(100,(v==null?40:v)+delta)));}
    private static int scaledDelta(ResidentState owner,int delta){return (int)Math.round(delta*Personality.of(owner).intensity());}
    static void replaceActor(CompanionWorld w,String id,String place,String activity,String label,Instant until){
        // The avatar is not in w.residents (see ResidentSeed's note on that list staying exactly the
        // four NPCs the frontend already renders) - "self" resolves through w.avatar instead, exactly
        // like the read side (actor()) already does. Without this, ResidentSimulation could compute a
        // plan for the avatar (item 7) but the change would silently vanish: this write is a no-op
        // against a list "self" was never in.
        if("self".equals(id)) {
            if(w.avatar==null)return;
            w.avatar=new Actor(id,w.avatar.name(),w.avatar.role(),place,activity,label,w.avatar.x(),w.avatar.y(),until);
            ResidentState r=state(w,id);if(r!=null)r.selfActivitySignature=place+"|"+activity+"|"+until;
            return;
        }
        for(int i=0;i<w.residents.size();i++){Actor a=w.residents.get(i);if(a.id().equals(id))w.residents.set(i,new Actor(id,a.name(),a.role(),place,activity,label,a.x(),a.y(),until));}
    }
    static void replaceRole(CompanionWorld w,String id,String role){
        if("self".equals(id)) {
            if(w.avatar==null)return;
            w.avatar=new Actor(id,w.avatar.name(),role,w.avatar.place(),w.avatar.activity(),w.avatar.label(),w.avatar.x(),w.avatar.y(),w.avatar.until());
            return;
        }
        for(int i=0;i<w.residents.size();i++){Actor a=w.residents.get(i);if(a.id().equals(id))w.residents.set(i,new Actor(id,a.name(),role,a.place(),a.activity(),a.label(),a.x(),a.y(),a.until()));}
    }
    static void project(CompanionWorld w,String id,String title,String kind,String place,String owner,String object,String description,int needed){Project p=new Project();p.id=id;p.title=title;p.kind=kind;p.place=place;p.ownerId=owner;p.objectKind=object;p.description=description;p.status="idea";p.needed=needed;p.members.add(owner);w.projects.add(p);}
    static String memory(CompanionWorld w,String owner,String source,String type,Instant at,String topic,String text,List<String> evidence,int importance){
        if(type.equals("reflection")){Memory existing=w.memories.stream().filter(m->m.ownerId().equals(owner)&&m.sourceType().equals(type)&&m.text().equals(text)).findFirst().orElse(null);if(existing!=null)return existing.id();}
        String id="m2-"+(++w.eventSequence);w.memories.add(new Memory(id,owner,source,type,at,text,topic,evidence,importance));while(w.memories.size()>200) {
            Set<String> referenced=new HashSet<>();
            for(Memory m:w.memories)if(m.evidenceIds()!=null)referenced.addAll(m.evidenceIds());
            // CafeService.reflectOnDuty holds memory ids on the owner's own state
            // (dutyComplaintEvidenceIds / dutyInterruptionEvidenceIds) between when they are recorded
            // and when enough of them accumulate to actually be cashed into a reflection's evidence -
            // now potentially several reflection windows later, since evidence below threshold is
            // meant to keep accumulating rather than being wiped. Those ids are a real, live reference
            // even though no memory's own evidenceIds names them yet; protect them the same way, or a
            // held id can be evicted here and a later reflection ends up citing a memory that no
            // longer exists.
            for(ResidentState r:w.residentStates){referenced.addAll(r.dutyComplaintEvidenceIds);referenced.addAll(r.dutyInterruptionEvidenceIds);}
            Memory removable=w.memories.stream().filter(m->!m.id().equals(id)&&!referenced.contains(m.id())).findFirst().orElse(null);
            // Keep a source chain intact if all older memories are still cited by retained thoughts.
            if(removable==null)break;
            w.memories.remove(removable);
        }return id;}
    private static void deduplicateReflections(CompanionWorld w) {
        Map<String,String> canonical=new HashMap<>(),replacements=new HashMap<>();
        List<Memory> kept=new ArrayList<>();
        for(Memory m:w.memories){
            String previous=m.sourceType().equals("reflection")?canonical.putIfAbsent(m.ownerId()+"\n"+m.text(),m.id()):null;
            if(previous==null)kept.add(m);else replacements.put(m.id(),previous);
        }
        if(replacements.isEmpty())return;
        w.memories=kept.stream().map(m->new Memory(m.id(),m.ownerId(),m.sourceId(),m.sourceType(),m.at(),m.text(),m.topicId(),
            m.evidenceIds()==null?List.of():m.evidenceIds().stream().map(id->replacements.getOrDefault(id,id)).distinct().toList(),m.importance())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
    static List<String> ownEvidence(CompanionWorld w,String id,String topic){return w.memories.stream().filter(m->m.ownerId().equals(id)&&Objects.equals(m.topicId(),topic)).sorted(Comparator.comparing(Memory::at).reversed()).limit(2).map(Memory::id).toList();}
    static void event(CompanionWorld w,Instant at,String type,String place,List<String> ids,String text,String project){w.events.add(new WorldEvent("e-"+(++w.eventSequence),at,type,place,ids,text,project));while(w.events.size()>80)w.events.removeFirst();if(w.avatar!=null&&w.avatar.place().equals(place)&&Set.of("ready","agreement","change_of_mind","celebration").contains(type)){w.diary.add(new Entry("d2-"+w.eventSequence,at,"路过时看见："+text));while(w.diary.size()>80)w.diary.removeFirst();}}
    private static double clamp(double v){return Math.max(0,Math.min(100,v));}
    private static String placeName(String place){if(TownPlaces.isHome(place))return"住处";return switch(place){case "cafe"->"咖啡馆";case "garden"->"花园";default->"小街";};}

    /** The user's avatar is the fifth resident: it shares this same ResidentState/position model so
     * the four NPCs can perceive it and contend with it for a seat, but nothing here drives its
     * activity - that stays with CompanionRules and the user's intents. Idempotent: safe to call on
     * every advance, including for saves from before the avatar had a state at all. */
    public static ResidentState ensureAvatarState(CompanionWorld w) {
        ResidentState existing=state(w,"self");
        if(existing!=null)return existing;
        ResidentState r=new ResidentState();r.id="self";r.energy=70;r.social=60;r.curiosity=60;r.mood="如常";r.revision=1;
        w.residentStates.add(r);return r;
    }
    /** Repairs an older save: gives it the location/position catalog and an avatar state if it is
     * missing either, and moves anyone still parked at the old single shared "home" into their own
     * home place. A brand-new world never needs this - {@link #initialize} already does it. */
    private static void reconcileLegacyPlaces(CompanionWorld w,Instant now) {
        TownPlaces.seed(w);ensureAvatarState(w);
        for(ResidentState r:w.residentStates) {
            if(r.id.equals("self"))continue;
            Actor a=actor(w,r.id);
            if(a.place().equals("home")){
                String home=TownPlaces.homeOf(r.id);replaceActor(w,r.id,home,a.activity(),a.label(),a.until());
                // A pre-place save can say "sleeping at home" while its old flat plan still points
                // at the shared cafe.  The visible, concrete old action wins during repair; without
                // this it immediately walks the resident back out of their newly repaired home.
                if("sleep".equals(a.activity()))r.plan=new Plan("legacy-sleep-"+r.id,"sleep",home,null,a.label(),now,a.until());
            }
            if(r.positionId==null||TownPlaces.position(w,r.positionId)==null){String place=actor(w,r.id).place();TownPlaces.claim(w,r.id,place,r.plan==null?null:preferredKind(r.plan.action(),place),now);}
        }
        if(w.avatar!=null) {
            if(w.avatar.place().equals("home"))w.avatar=new Actor(w.avatar.id(),w.avatar.name(),w.avatar.role(),TownPlaces.homeOf("self"),w.avatar.activity(),w.avatar.label(),w.avatar.x(),w.avatar.y(),w.avatar.until());
            ResidentState self=state(w,"self");
            if(self.positionId==null||TownPlaces.position(w,self.positionId)==null)TownPlaces.claim(w,"self",w.avatar.place(),Set.of("focus","study").contains(w.avatar.activity())&&TownPlaces.isHome(w.avatar.place())?"desk":null,now);
        }
    }
    /** The one legacy WorldObject that used to advertise a "state" nobody ever wrote back: keep it
     * reflecting whatever the real cafe-worktable position now holds. */
    private static void syncLegacyObjects(CompanionWorld w) {
        Position table=TownPlaces.position(w,"cafe-worktable");
        if(table==null)return;
        String state=table.occupantIds.isEmpty()?"available":"occupied";
        for(int i=0;i<w.objects.size();i++) {
            WorldObject o=w.objects.get(i);
            if(o.id().equals("worktable")&&!o.state().equals(state))w.objects.set(i,new WorldObject(o.id(),o.kind(),o.place(),o.label(),state,o.projectId()));
        }
    }
}
