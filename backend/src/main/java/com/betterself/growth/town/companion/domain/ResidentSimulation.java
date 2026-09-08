package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/** Small event-driven society. Plans persist; only completed actions change physical objects. */
public final class ResidentSimulation {
    private ResidentSimulation() {}
    private static final List<String> IDS=List.of("owner","student","artist","gardener");
    public static void initialize(CompanionWorld w,Instant now) {
        if(w.simulationVersion>=2)return;
        long initialRevision=w.revision;
        w.simulationVersion=3; w.residentStates.clear();w.projects.clear();w.objects.clear();w.conversations.clear();
        TownPlaces.seed(w);ensureAvatarState(w);
        boolean modelMode=w.modelConversationsEnabled;w.modelConversationsEnabled=false;
        Instant past=now.minusSeconds(90);
        project(w,"reading-night","留一盏灯的读书小聚","gathering","cafe","owner","books","想让晚归的人也有一个能坐下来的地方。",2);
        project(w,"quiet-corner","窗边的安静角","quiet","cafe","student","books","需要安静备考，又不想把邻居们都挡在门外。",2);
        project(w,"street-colors","小街颜色采集册","art","garden","artist","poster","收集四个人眼里的小街，画成一张大家都认得的画。",3);
        project(w,"seed-exchange","带一株新芽回家","garden","garden","gardener","flowers","给门前空花盆找新主人，也想学会怎么把花画下来。",2);
        for(int i=0;i<4;i++) {
            ResidentState r=new ResidentState();r.id=IDS.get(i);r.energy=i==1?42:74-i*5;r.social=i==0?48:62;r.curiosity=60+i*8;
            r.mood=i==1?"有点紧绷":"有所期待";r.goal=w.projects.get(i).id;r.thought=w.projects.get(i).description;
            r.lastSocialAt=past.minusSeconds(120);r.lastReflectionAt=past;r.revision=1;
            for(String other:IDS)if(!other.equals(r.id))r.relationships.put(other,other.equals("owner")?62:38+Math.floorMod((w.id+r.id+other).hashCode(),20));
            w.residentStates.add(r);
            Project own=w.projects.get(i);r.knownProjects.put(own.id,new ProjectKnowledge(own.id,own.place,own.status,own.progress,past,r.id));
            memory(w,r.id,"history","seed",past.minusSeconds(86400),r.goal,r.thought,List.of(),7);
            String place=i<2?"cafe":i==2?"cafe":"garden";
            schedule(w,r,i==1?"study":"observe",place,null,i==1?"先守住今天的复习时间":"看看邻居手上的事，再决定从哪里开始",past,36+i*7);
        }
        // Shared history is split into individual perspectives, rather than a global script.
        memory(w,"artist","owner","seed",past.minusSeconds(3600),"reading-night","阿禾昨天问我能不能帮她画一张读书小聚的招贴，我说可以先聊聊。",List.of(),8);
        w.projects.get(0).members.add("artist");
        state(w,"artist").knownProjects.put("reading-night",new ProjectKnowledge("reading-night","cafe","idea",0,past,"owner"));
        w.objects.add(new WorldObject("worktable","table","cafe","一张可共用的长桌","available",null));
        w.objects.add(new WorldObject("noticeboard","board","street","门前留言板","empty",null));
        w.objects.add(new WorldObject("flowerbed","flowers","garden","等待移栽的新芽","growing","seed-exchange"));
        // A short deterministic warm start creates actual earlier interactions and unfinished plans.
        w.simulatedAt=past;
        for(int i=1;i<=15;i++)step(w,past.plusSeconds(i*6L));
        w.simulatedAt=now;
        // Ensure arrival opens onto an unfinished social moment, including the two night owls.
        ResidentState owner=state(w,"owner"),artist=state(w,"artist");
        schedule(w,owner,"observe","cafe",null,"等知夏看看桌上还没定稿的招贴",now,42);
        schedule(w,artist,"observe","cafe",null,"想先问清小聚想让人记住什么",now,48);
        owner.lastSocialAt=now.minusSeconds(120);artist.lastSocialAt=now.minusSeconds(120);
        w.conversations.stream().filter(c->c.status.equals("active")).forEach(c->c.status="ended");
        w.modelConversationsEnabled=modelMode;
        startConversation(w,owner,artist,w.projects.get(0),now);
        event(w,now,"arrival","street",List.of(),"小街的日子早就开始了。咖啡馆里，一张招贴还没有定稿。",null);
        w.revision=initialRevision;
    }
    public static void advance(CompanionWorld w,Instant now) {
        initialize(w,now);
        reconcileLegacyPlaces(w,now);
        deduplicateReflections(w);
        if(w.simulatedAt==null)w.simulatedAt=now;
        long elapsed=Duration.between(w.simulatedAt,now).getSeconds();
        if(elapsed>900) {
            // Reconcile needs gently, then at most 24 seconds of recent life. No offline model calls.
            w.simulatedAt=now.minusSeconds(24);
            for(ResidentState r:w.residentStates){r.energy=Math.max(45,r.energy);r.social=Math.max(40,r.social);}
            for(Conversation c:w.conversations)if(c.status.equals("active"))ConversationLifecycle.finish(w,c,now,"离开期间这段谈话已经告一段落");
        }
        int steps=0;
        while(!w.simulatedAt.plusSeconds(6).isAfter(now)&&steps++<10){w.simulatedAt=w.simulatedAt.plusSeconds(6);step(w,w.simulatedAt);}
    }
    private static void step(CompanionWorld w,Instant at) {
        ConversationLifecycle.recoverSummaries(w,at);
        // The coffee/water chain has its own clock, independent of whose plan is currently running:
        // a request keeps waiting, gets picked up, or goes cold on real elapsed time even while its
        // requester has already moved on to something else while they wait. See CafeService.
        CafeService.tick(w,at);
        for(Conversation c:new ArrayList<>(w.conversations))if(c.status.equals("active"))continueConversation(w,c,at);
        for(ResidentState r:w.residentStates) {
            // The avatar's own state is present so it can be perceived and can hold a position, but
            // its activity stays entirely user-driven (CompanionRules); it never runs the autonomous
            // choose()/complete() loop the four NPCs use.
            if(r.id.equals("self"))continue;
            perceive(w,r,at);
            // The owner's sense of responsibility for the counter builds every tick someone is
            // waiting, whatever else the owner is currently doing - not only when they are free to
            // decide. See CafeService.accruePressure.
            if(r.id.equals(CafeService.OWNER))CafeService.accruePressure(w,r,at);
            // Emotionally volatile residents swing harder in both directions; steady ones barely move.
            double intensity=Personality.of(r).intensity();
            boolean resting=r.plan!=null&&Set.of("rest","sleep").contains(r.plan.action());
            r.energy=clamp(r.energy+(resting?1.8*intensity:-.16*intensity));r.social=clamp(r.social-.16*intensity);r.curiosity=clamp(r.curiosity+.18);
            if(activeConversation(w,r.id)!=null)continue;
            if(r.plan!=null&&!at.isBefore(r.plan.endsAt())){Plan completed=r.plan;complete(w,r,at);if(r.plan==completed)r.plan=null;}
            if(r.plan==null)choose(w,r,at);
        }
        for(ResidentState r:w.residentStates) {
            // Extroverts recover their appetite for company faster than introverts do.
            if(r.id.equals("self")||activeConversation(w,r.id)!=null||r.plan==null||Set.of("travel","sleep","rest").contains(r.plan.action())||Duration.between(r.lastSocialAt,at).getSeconds()<Personality.of(r).socialRefractorySeconds())continue;
            Actor a=actor(w,r.id);
            ResidentState partner=w.residentStates.stream().filter(other->!other.id.equals(r.id)&&!other.id.equals("self")&&activeConversation(w,other.id)==null
                && other.plan!=null&&!Set.of("travel","sleep","rest").contains(other.plan.action())
                && actor(w,other.id).place().equals(a.place())&&Duration.between(other.lastSocialAt,at).getSeconds()>=Personality.of(other).socialRefractorySeconds()
                && w.projects.stream().anyMatch(p->canInvite(w,r,other,p,at)))
                .max(Comparator.comparingDouble(other->r.relationships.getOrDefault(other.id,40)+(100-other.social)*.3)).orElse(null);
            if(partner==null||TownPlaces.isHome(a.place()))continue;
            Project topic=w.projects.stream().filter(p->canInvite(w,r,partner,p,at))
                .max(Comparator.comparingInt(p->(!knows(w,partner.id,p.id)?50:0)+(p.ownerId.equals(r.id)?20:0)+(p.id.equals(r.goal)?15:0))).orElse(null);
            if(topic!=null){startConversation(w,r,partner,topic,at);break;}
        }
        for(ResidentState r:w.residentStates){if(r.id.equals("self"))continue;reflect(w,r,at);newWish(w,r,at);CafeService.reflectOnDuty(w,r,at);}
        syncLegacyObjects(w);
    }
    private static void choose(CompanionWorld w,ResidentState r,Instant at) {
        Personality personality=Personality.of(r);
        int hour=at.atZone(ZoneId.of(w.timezone)).getHour();
        boolean quietNight=hour<6||hour>=23;
        boolean nightOwl=r.id.equals("owner")||r.id.equals("artist");
        if(r.energy<28||quietNight&&!nightOwl&&hour!=5){moveOrSchedule(w,r,"sleep",TownPlaces.homeOf(r.id),null,"先睡一会儿，明天还想把自己的小事做好",at,100);return;}
        if(r.energy<46){moveOrSchedule(w,r,"rest","cafe",null,"先喝口热水，别把想做的事变成负担",at,45);return;}
        Project current=project(w,r.goal);
        boolean unfinished=current!=null&&!Set.of("ready","celebrating").contains(current.status);
        // Responsibility as pressure, not a rule: the owner never gets an "if someone is waiting, go
        // serve them" branch. Every time the owner is free to choose, CafeService.decide() weighs the
        // pressure that has been quietly accumulating against everything else pulling at them right
        // now, and either side can win - see CafeService's own doc for the competition itself.
        if(r.id.equals(CafeService.OWNER)) {
            CafeService.Decision duty=CafeService.decide(w,r,personality,current,unfinished,at);
            if(duty!=null) {
                if(duty.interruptsOwnProject())CafeService.recordInterruption(w,r,current,at);
                CafeService.beginPreparing(w,duty.requestId(),at);
                moveOrSchedule(w,r,"tend","cafe",duty.requestId(),duty.reason(),at,CafeService.PREP_SECONDS);
                return;
            }
        }
        // Low-conscientiousness residents sometimes drift away from their own unfinished project
        // before it is done, deterministically (a hash of who/what/when, never Math.random) rather
        // than always grinding a commitment through to the end.
        boolean givingUp=unfinished&&current.ownerId.equals(r.id)&&abandonsNow(w,r,current,personality,at);
        Project goal=current;
        if(current==null||knownStatus(r,current.id).equals("celebrating")||givingUp) {
            goal=w.projects.stream().filter(p->knows(w,r.id,p.id)&&!knownStatus(r,p.id).equals("celebrating")&&!(givingUp&&p.id.equals(current.id)))
                .min(Comparator.comparingInt(p->r.knownProjects.getOrDefault(p.id,new ProjectKnowledge(p.id,p.place,"idea",0,at,r.id)).progress()-(p.members.contains(r.id)?35:0))).orElse(givingUp?current:null);
            if(goal!=null&&goal!=current){
                if(givingUp)memory(w,r.id,r.id,"reflection",at,current.id,"手上的「"+current.title+"」还没做完，我又想去看看别的事了。",List.of(),5);
                r.goal=goal.id;r.thought=givingUp?"心思飘到别处，先去看看"+actor(w,goal.ownerId).name()+"那边的事。":"自己的事告一段落了，想看看能不能帮上"+actor(w,goal.ownerId).name()+"。";
            }
        }
        if(goal!=null&&knownStatus(r,goal.id).equals("ready")) {
            moveOrSchedule(w,r,"celebrate",knownPlace(r,goal),goal.id,"去看看大家一起做出来的"+goal.title,at,48);return;
        }
        if(r.social<personality.socialThreshold()||goal!=null&&goal.contributors.size()<goal.needed&&r.knownProjects.getOrDefault(goal.id,new ProjectKnowledge(goal.id,goal.place,"idea",0,at,r.id)).progress()>=45) {
            ResidentState friend=w.residentStates.stream().filter(o->!o.id.equals(r.id)&&!o.id.equals("self")&&!TownPlaces.isHome(actor(w,o.id).place())
                &&w.projects.stream().anyMatch(p->canInvite(w,r,o,p,at)))
                .max(Comparator.comparingInt(o->r.relationships.getOrDefault(o.id,40)+(goalNeeds(w,r,o)?100:0))).orElse(null);
            if(friend!=null){moveOrSchedule(w,r,"invite",actor(w,friend.id).place(),friend.id,"想当面问问"+actor(w,friend.id).name()+"愿不愿意一起做",at,36);return;}
        }
        if(goal!=null) {
            String place=knownPlace(r,goal);
            if(w.weather.equals("rain")&&place.equals("garden")) {
                if(actor(w,r.id).place().equals("garden"))moveOrSchedule(w,r,"relocate","cafe",goal.id,"把能搬动的准备材料带到檐下，免得淋湿",at,18);
                else moveOrSchedule(w,r,"observe","garden",null,"先去看看花园里的材料，哪些需要避雨",at,18);
                return;
            }
            moveOrSchedule(w,r,goal.ownerId.equals(r.id)?"create":"help",place,goal.id,
                goal.ownerId.equals(r.id)?"把心里的小愿望往前做一点":"答应过的帮忙，想认真做完",at,42+Math.floorMod((w.id+r.id+w.eventSequence).hashCode(),20));return;
        }
        moveOrSchedule(w,r,"observe",w.weather.equals("rain")?"cafe":"garden",null,"没有急事，想看看今天有哪些新变化",at,55);
    }
    private static boolean goalNeeds(CompanionWorld w,ResidentState r,ResidentState other){Project p=project(w,r.goal);return p!=null&&!p.contributors.contains(other.id);}
    /** Deterministic stand-in for "did this resident's follow-through fail this time": a hash of the
     * world, resident, project and a five-minute time bucket (so it does not flicker every 6-second
     * tick) compared against the personality's own abandon threshold. Same inputs always give the
     * same answer, so a replay of the same world produces the same choices. */
    private static boolean abandonsNow(CompanionWorld w,ResidentState r,Project goal,Personality personality,Instant at) {
        int threshold=personality.abandonThreshold();
        if(threshold<=0)return false;
        int hash=Math.floorMod((w.id+r.id+goal.id+(at.getEpochSecond()/300)).hashCode(),100);
        return hash<threshold;
    }
    private static void complete(CompanionWorld w,ResidentState r,Instant at) {
        Plan p=r.plan;
        if(p.action().equals("travel")){schedule(w,r,r.desiredAction,p.place(),p.targetId(),p.reason(),at,42);return;}
        if(p.action().equals("relocate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&actor(w,r.id).place().equals("cafe")&&project.place.equals("garden")) {
                project.place="cafe";r.knownProjects.put(project.id,new ProjectKnowledge(project.id,"cafe",project.status,project.progress,at,r.id));
                for(int i=0;i<w.objects.size();i++){WorldObject o=w.objects.get(i);if(Objects.equals(o.projectId(),project.id))w.objects.set(i,new WorldObject(o.id(),o.kind(),"cafe",o.label(),o.state(),o.projectId()));}
                event(w,at,"adapt","cafe",List.of(r.id),actor(w,r.id).name()+"把「"+project.title+"」的材料带到了檐下，免得淋湿。",project.id);
                memory(w,r.id,r.id,"observed",at,project.id,"我把「"+project.title+"」的材料搬到了咖啡馆檐下。",List.of(),6);
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
                r.energy=clamp(r.energy-4);r.curiosity=clamp(r.curiosity-10);r.mood=first?"有点得意":"踏实";
                if(project.progress==100){project.completedAt=at;event(w,at,"ready",project.place,new ArrayList<>(project.contributors),"「"+project.title+"」准备好了，和最初一个人的想法已经不太一样。",project.id);}
            }
        } else if(p.action().equals("celebrate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&project.status.equals("ready")) {
                project.status="celebrating";r.mood="开心";r.social=clamp(r.social+20);
                event(w,at,"celebration",project.place,new ArrayList<>(project.contributors),actor(w,r.id).name()+"招呼大家来看「"+project.title+"」。这一次，桌边多了几个熟悉的位置。",project.id);
                for(String member:project.contributors)if(actor(w,member).place().equals(project.place)&&!actor(w,member).activity().equals("walk")) {
                    boolean detail=Personality.of(state(w,member)).sensitivity()>=65;
                    String text=detail?"我亲眼看见，参与的「"+project.title+"」真的做出来了，连细节都跟当初说的差不多。":"我亲眼看见，参与的「"+project.title+"」真的做出来了。";
                    memory(w,member,r.id,"observed",at,project.id,text,List.of(),9);
                }
            }
        } else if(p.action().equals("rest")||p.action().equals("sleep")){r.energy=clamp(r.energy+18);r.mood="松弛";}
        else if(p.action().equals("observe")){r.curiosity=clamp(r.curiosity-16);r.social=clamp(r.social-5);}
        else if(p.action().equals("tend")){CafeService.finishTending(w,r,p.targetId(),at);}
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
            r.desiredAction=action;r.plan=new Plan("p-"+(++w.eventSequence),"travel",place,target,reason,at,at.plusSeconds(12));
            r.revision++;r.thought=reason;
            TownPlaces.release(w,r.id); // stepping away frees up the spot right away, not 12 seconds from now
            replaceActor(w,r.id,"street","walk","准备去"+placeName(place)+"："+reason,r.plan.endsAt());
        } else schedule(w,r,action,place,target,reason,at,duration);
    }
    private static void schedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        // Wanting to rest at the cafe specifically (as opposed to at home) is where a coffee/water
        // request is actually made - CafeService owns everything from here; this line only starts the
        // chain. The resident's own energy recovery below is unrelated and unchanged either way, so a
        // request that never gets fulfilled cannot strand anyone at low energy.
        if(action.equals("rest")&&place.equals("cafe")&&!r.id.equals(CafeService.OWNER))CafeService.request(w,r,at);
        r.plan=new Plan("p-"+(++w.eventSequence),action,place,target,reason,at,at.plusSeconds(duration));r.revision++;r.thought=reason;
        String label=switch(action){case "create","help"->"动手准备"+(project(w,target)==null?"手上的小事":"「"+project(w,target).title+"」");case "study"->"在窗边复习，想守住一点安静";case "invite"->reason;case "sleep"->"睡着了，给明天留一点精神";case "rest"->"捧着杯子歇一会儿";case "celebrate"->"想请大家看看一起做出来的东西";case "wait"->reason;case "tend"->"回到吧台，照应一下柜台前的人";default->reason;};
        replaceActor(w,r.id,place,action,label,r.plan.endsAt());
        TownPlaces.Outcome outcome=TownPlaces.claim(w,r.id,place,preferredKind(action,place),at);
        if(outcome==TownPlaces.Outcome.WAITING) {
            // Every spot here is taken: stand by a moment instead of being placed on top of someone.
            String waitReason="这里现在坐满了，先在旁边等一等";
            r.plan=new Plan("p-"+(++w.eventSequence),"wait",place,target,waitReason,at,at.plusSeconds(12));r.thought=waitReason;
            replaceActor(w,r.id,place,"wait",waitReason,r.plan.endsAt());
        }
    }
    /** Which kind of position best fits this action, when it matters; null means any open spot will do. */
    private static String preferredKind(String action,String place) {
        return switch(action) {
            case "study"->"seat";
            case "create","help","celebrate"->"table";
            case "sleep","rest"->TownPlaces.isHome(place)?"bed":null;
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
    private static void startConversation(CompanionWorld w,ResidentState a,ResidentState b,Project p,Instant at) {
        p.invitationHistory.put(pairKey(a.id,b.id),at);
        Conversation c=new Conversation();c.id="c-"+(++w.eventSequence);c.place=actor(w,a.id).place();c.topicId=p.id;c.status="active";c.participantIds.add(a.id);c.participantIds.add(b.id);c.startedAt=at;c.updatedAt=at;
        if(w.modelConversationsEnabled&&(w.modelRetryAfter==null||!at.isBefore(w.modelRetryAfter))){
            c.mode="model";c.nextSpeakerId=a.id;w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
            replaceActor(w,a.id,c.place,"talk","想和"+actor(w,b.id).name()+"聊聊，正在组织语言",at.plusSeconds(45));
            replaceActor(w,b.id,c.place,"talk","停下手里的事，等对方开口",at.plusSeconds(45));
            event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"叫住了"+actor(w,b.id).name()+"。",p.id);
            while(w.conversations.size()>24)w.conversations.removeFirst();return;
        }
        String invitation=switch(p.objectKind){case "poster"->"如果让你留下一种颜色，你会选什么？";case "flowers"->"这里的新芽，能不能也在你窗前住下来？";case "tea"->"一壶茶可以有几种泡法，想不想试试你的那一种？";default->"要不要带一本读到一半的书来，不一定非得读完才分享？";};
        // A's own private fondness for b (never visible to b, and never sent to anyone else's model
        // context - see ResidentDirector.perspective()) can let itself show once, the first time it
        // is high enough; after that it does not keep repeating the same tell every single time.
        boolean fond=a.relationships.getOrDefault(b.id,40)>55;
        boolean alreadyShown=Boolean.TRUE.equals(a.affectionExpressed.get(b.id));
        if(fond&&!alreadyShown)a.affectionExpressed.put(b.id,true);
        String opener=fond&&!alreadyShown?"想到你上次说的话了。":"要是你现在方便，";
        c.turns.add(new Turn(a.id,"我在琢磨「"+p.title+"」。"+opener+invitation,at));
        w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
        replaceActor(w,a.id,c.place,"talk",c.turns.getFirst().text(),at.plusSeconds(36));replaceActor(w,b.id,c.place,"talk","停下手里的事，听听对方",at.plusSeconds(36));
        var shared=a.knownProjects.get(p.id);if(shared!=null)b.knownProjects.put(p.id,new ProjectKnowledge(shared.id(),shared.place(),shared.status(),shared.progress(),at,a.id));
        memory(w,b.id,a.id,"heard",at,p.id,actor(w,a.id).name()+"当面告诉我，正在准备「"+p.title+"」。",ownEvidence(w,a.id,p.id),7);
        event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"叫住了"+actor(w,b.id).name()+"，聊起「"+p.title+"」。",p.id);
        while(w.conversations.size()>24)w.conversations.removeFirst();
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
            text=reluctant?(b.id.equals("student")?"我还得复习。大家聚在一起会不会太吵？如果能留一块安静的地方，我才想帮忙。":"我现在有点累，怕随口答应了又做不好。能不能先做一小部分？")
                :b.id.equals("artist")?"我想画得像真的有人住在这里，不只是漂亮。让每个人留一个自己的颜色，怎么样？"
                :b.id.equals("gardener")?"可以呀。不过花不是装饰完就不管的，能不能也给愿意照顾它的人留个位置？":"我可以帮你整理一下。先做小一点，别让准备本身太累。";
            b.mood=reluctant?"有些犹豫":"被需要";relation(a,b,reluctant?-3:4);
        } else if(n==2) {
            speaker=a.id;
            if(!c.accepted && b.energy<32) {
                text="那今天先不约你了，你安心歇着。这件事可以慢慢来，别因为我不好意思拒绝。";
                a.mood="更体谅了";
            } else if(!c.accepted) {
                text="你说得对，我刚才只顾着自己的兴奋。那就留出安静角，先做一点，累了随时停。";
                p.description="听过"+actor(w,b.id).name()+"的顾虑，改成小规模准备，并给安静和休息留位置。";
                a.mood="重新想过了";c.accepted=true;
                event(w,at,"change_of_mind",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"因为"+actor(w,b.id).name()+"的顾虑，改了「"+p.title+"」的做法。",p.id);
            } else text="这个主意比我一个人想的好。你按自己的做法来，我们不用做成一模一样的东西。";
        } else if(n==3) {
            speaker=b.id;
            if(!c.accepted) {
                text="谢谢你理解。这次我先休息，等精神好一点再来看看。";
                c.turns.add(new Turn(speaker,text,at));c.stage++;c.updatedAt=at;relation(a,b,2);
                a.revision++;b.revision++;
                memory(w,a.id,b.id,"heard",at,p.id,actor(w,b.id).name()+"说今天想先休息，没有答应参与。",ownEvidence(w,a.id,p.id),6);
                event(w,at,"declined",c.place,List.of(a.id,b.id),actor(w,b.id).name()+"婉拒了这一次邀请，"+actor(w,a.id).name()+"决定不催促。",p.id);
                replaceActor(w,speaker,c.place,"talk",text,at.plusSeconds(12));return;
            }
            text="那我愿意试试。等手上这一点收好，我就去帮你。";
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
    private static void reflect(CompanionWorld w,ResidentState r,Instant at) {
        if(Duration.between(r.lastReflectionAt,at).getSeconds()<120)return;
        List<Memory> recent=w.memories.stream().filter(m->m.ownerId().equals(r.id)&&m.at().isAfter(r.lastReflectionAt)&&!m.sourceType().equals("reflection")).toList();
        if(recent.stream().mapToInt(Memory::importance).sum()<22)return;
        Memory salient=recent.stream().max(Comparator.comparingInt(Memory::importance).thenComparing(Memory::at)).orElseThrow();
        Project topic=project(w,salient.topicId());
        String title=topic==null?"刚才那件小事":"「"+topic.title+"」";
        Memory encounter=recent.stream().filter(m->m.topicId().equals(salient.topicId())&&!m.sourceId().equals(r.id)&&IDS.contains(m.sourceId())).findFirst().orElse(null);
        String partner=encounter==null?"邻居":actor(w,encounter.sourceId()).name();
        String thought;
        if(salient.text().contains("真的做出来"))thought=title+"做出来以后，我才发现，大家不同的做法可以留在同一件东西里。下次还想给别人的主意留个位置。";
        else if(recent.stream().anyMatch(m->m.text().contains("没有答应")))thought="聊到"+title+"时，"+partner+"没有答应。我提醒自己，关系好也不代表对方随时有空。";
        else if(encounter!=null&&encounter.sourceType().equals("heard"))thought="这次商量"+title+"，"+partner+"愿意把自己的顾虑说出来。我开始觉得，听完这些话比急着把人拉进来更重要。";
        else if(encounter!=null)thought="看到"+partner+"为"+title+"动手以后，我对一起做事多了一点信心。这是我从这次经历里得到的感觉。";
        else thought="亲手做了"+title+"的一部分，我才知道光有主意还不够。下一步想把需要别人帮忙的地方说得更具体。";
        var evidence=recent.stream().filter(m->Objects.equals(m.topicId(),salient.topicId())).sorted(Comparator.comparingInt(Memory::importance).reversed()).limit(3).map(Memory::id).toList();
        memory(w,r.id,r.id,"reflection",at,salient.topicId(),thought,evidence,8);r.thought=thought;r.lastReflectionAt=at;
    }
    private static void newWish(CompanionWorld w,ResidentState r,Instant at) {
        boolean unfinished=w.projects.stream().anyMatch(p->p.ownerId.equals(r.id)&&!p.status.equals("celebrating"));
        if(unfinished)return;
        Project previous=w.projects.stream().filter(p->p.ownerId.equals(r.id)&&p.completedAt!=null).max(Comparator.comparing(p->p.completedAt)).orElse(null);
        if(previous==null||Duration.between(previous.completedAt,at).getSeconds()<240)return;
        int variation=Math.floorMod((w.id+r.id+at.atZone(ZoneId.of(w.timezone)).toLocalDate()+w.projects.size()).hashCode(),3);
        String title=switch(r.id){case "owner"->List.of("雨天的一壶分享茶","给晚归邻居的留言杯垫","带一本书来换一段故事").get(variation);case "student"->List.of("把难题讲给邻居听","安静角的互助便签","今天只读一页的小书签").get(variation);case "artist"->List.of("画下邻居最喜欢的一扇窗","一张有四种颜色的地图","给花盆画一个新名字").get(variation);default->List.of("一人认领一株新芽","把雨水留给明天的花","交换一种照顾植物的办法").get(variation);};
        String kind=r.id.equals("artist")?"poster":r.id.equals("gardener")?"flowers":r.id.equals("owner")?"tea":"books";
        propose(w,r,TownPlaces.isHome(actor(w,r.id).place())?"cafe":actor(w,r.id).place(),title,kind,
            "上次和邻居一起做成了「"+previous.title+"」，想把那一点默契接着用下去。",ownEvidence(w,r.id,previous.id),at);
    }
    public static boolean proposeDecision(CompanionWorld w,String id,long residentRevision,long intentRevision,String place,String title,String objectKind,String reason,List<String> evidence,Instant now) {
        ResidentState r=state(w,id);
        if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision||!TownPlaces.places().contains(place)||TownPlaces.isHome(place))return false;
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
    public static boolean applyDecision(CompanionWorld w,String residentId,long residentRevision,long intentRevision,String place,String action,String target,String reason,String speech,List<String> evidence,Instant now) {
        ResidentState r=state(w,residentId);if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision)return false;
        // The model still speaks of "home" generically; the resident's own home is what that resolves to.
        String resolvedPlace="home".equals(place)?TownPlaces.homeOf(residentId):place;
        if(!TownPlaces.places().contains(resolvedPlace)||TownPlaces.isHome(resolvedPlace)&&!resolvedPlace.equals(TownPlaces.homeOf(residentId))||!Set.of("observe","create","help","invite","rest","study").contains(action))return false;
        if(reason==null||reason.isBlank()||reason.length()>160||speech!=null&&speech.length()>180)return false;
        if(evidence==null||evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        if(Set.of("create","help").contains(action)){Project p=project(w,target);if(p==null||!knows(w,r.id,p.id)||!p.place.equals(resolvedPlace)||Set.of("ready","celebrating").contains(p.status))return false;}
        if(action.equals("invite")&&(target==null||state(w,target)==null||!actor(w,target).place().equals(actor(w,r.id).place())))return false;
        // Model may enrich this resident's current speaking turn; never invent the other party's reply.
        Conversation c=activeConversation(w,residentId);
        if(c!=null) {
            if(speech==null||speech.isBlank()||!actor(w,r.id).place().equals(c.place))return false;
            c.turns.add(new Turn(r.id,speech,now));c.updatedAt=now;
            for(String listener:c.participantIds)if(!listener.equals(r.id)&&actor(w,listener).place().equals(c.place))
                memory(w,listener,r.id,"heard",now,c.topicId,actor(w,r.id).name()+"当面说：“"+speech+"”",evidence,6);
            replaceActor(w,r.id,c.place,"talk",speech,now.plusSeconds(24));
        } else moveOrSchedule(w,r,action,resolvedPlace,target,reason,now,60);
        if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,r.goal,reason,evidence,7);
        w.modelStatus="模型刚为"+actor(w,r.id).name()+"补充了一个念头";w.revision++;r.revision++;
        event(w,now,"thought",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"想了想："+reason,target);
        return true;
    }
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
    public static List<Memory> retrieve(CompanionWorld w,String id,Instant now) {
        ResidentState r=state(w,id);
        return w.memories.stream().filter(m->m.ownerId().equals(id)).sorted(Comparator.comparingDouble((Memory m)->
            m.importance()+8.0/(1+Math.max(0,Duration.between(m.at(),now).toMinutes())/10.0)+(Objects.equals(m.topicId(),r.goal)?8:0)).reversed()).limit(10).toList();
    }
    public static ResidentState state(CompanionWorld w,String id){return w.residentStates.stream().filter(r->r.id.equals(id)).findFirst().orElse(null);}
    // The avatar is not in w.residents (that list stays exactly the four NPCs the frontend already
    // renders); "self" resolves to w.avatar instead so any resident-facing lookup still finds it.
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
    static void replaceActor(CompanionWorld w,String id,String place,String activity,String label,Instant until){for(int i=0;i<w.residents.size();i++){Actor a=w.residents.get(i);if(a.id().equals(id))w.residents.set(i,new Actor(id,a.name(),a.role(),place,activity,label,a.x(),a.y(),until));}}
    private static void project(CompanionWorld w,String id,String title,String kind,String place,String owner,String object,String description,int needed){Project p=new Project();p.id=id;p.title=title;p.kind=kind;p.place=place;p.ownerId=owner;p.objectKind=object;p.description=description;p.status="idea";p.needed=needed;p.members.add(owner);w.projects.add(p);}
    static String memory(CompanionWorld w,String owner,String source,String type,Instant at,String topic,String text,List<String> evidence,int importance){
        if(type.equals("reflection")){Memory existing=w.memories.stream().filter(m->m.ownerId().equals(owner)&&m.sourceType().equals(type)&&m.text().equals(text)).findFirst().orElse(null);if(existing!=null)return existing.id();}
        String id="m2-"+(++w.eventSequence);w.memories.add(new Memory(id,owner,source,type,at,text,topic,evidence,importance));while(w.memories.size()>200) {
            Set<String> referenced=new HashSet<>();
            for(Memory m:w.memories)if(m.evidenceIds()!=null)referenced.addAll(m.evidenceIds());
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
            if(a.place().equals("home"))replaceActor(w,r.id,TownPlaces.homeOf(r.id),a.activity(),a.label(),a.until());
            if(r.positionId==null||TownPlaces.position(w,r.positionId)==null)TownPlaces.claim(w,r.id,actor(w,r.id).place(),null,now);
        }
        if(w.avatar!=null) {
            if(w.avatar.place().equals("home"))w.avatar=new Actor(w.avatar.id(),w.avatar.name(),w.avatar.role(),TownPlaces.homeOf("self"),w.avatar.activity(),w.avatar.label(),w.avatar.x(),w.avatar.y(),w.avatar.until());
            ResidentState self=state(w,"self");
            if(self.positionId==null||TownPlaces.position(w,self.positionId)==null)TownPlaces.claim(w,"self",w.avatar.place(),null,now);
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
