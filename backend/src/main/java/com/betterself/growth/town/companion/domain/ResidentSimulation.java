package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/** Small event-driven society. Plans persist; only completed actions change physical objects. */
public final class ResidentSimulation {
    private ResidentSimulation() {}
    private static final List<String> IDS=List.of("owner","student","artist","gardener");
    private static final Set<String> PLACES=Set.of("home","cafe","street","garden");
    public static void initialize(CompanionWorld w,Instant now) {
        if(w.simulationVersion>=2)return;
        long initialRevision=w.revision;
        w.simulationVersion=2; w.residentStates.clear();w.projects.clear();w.objects.clear();w.conversations.clear();
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
        for(Conversation c:new ArrayList<>(w.conversations))if(c.status.equals("active"))continueConversation(w,c,at);
        for(ResidentState r:w.residentStates) {
            perceive(w,r,at);
            boolean resting=r.plan!=null&&Set.of("rest","sleep").contains(r.plan.action());
            r.energy=clamp(r.energy+(resting?1.8:-.16));r.social=clamp(r.social-.16);r.curiosity=clamp(r.curiosity+.18);
            if(activeConversation(w,r.id)!=null)continue;
            if(r.plan!=null&&!at.isBefore(r.plan.endsAt())){Plan completed=r.plan;complete(w,r,at);if(r.plan==completed)r.plan=null;}
            if(r.plan==null)choose(w,r,at);
        }
        for(ResidentState r:w.residentStates) {
            if(activeConversation(w,r.id)!=null||r.plan==null||Set.of("travel","sleep","rest").contains(r.plan.action())||Duration.between(r.lastSocialAt,at).getSeconds()<55)continue;
            Actor a=actor(w,r.id);
            ResidentState partner=w.residentStates.stream().filter(other->!other.id.equals(r.id)&&activeConversation(w,other.id)==null
                && other.plan!=null&&!Set.of("travel","sleep","rest").contains(other.plan.action())
                && actor(w,other.id).place().equals(a.place())&&Duration.between(other.lastSocialAt,at).getSeconds()>=55
                && w.projects.stream().anyMatch(p->canInvite(w,r,other,p,at)))
                .max(Comparator.comparingDouble(other->r.relationships.getOrDefault(other.id,40)+(100-other.social)*.3)).orElse(null);
            if(partner==null||a.place().equals("home"))continue;
            Project topic=w.projects.stream().filter(p->canInvite(w,r,partner,p,at))
                .max(Comparator.comparingInt(p->(!knows(w,partner.id,p.id)?50:0)+(p.ownerId.equals(r.id)?20:0)+(p.id.equals(r.goal)?15:0))).orElse(null);
            if(topic!=null){startConversation(w,r,partner,topic,at);break;}
        }
        for(ResidentState r:w.residentStates){reflect(w,r,at);newWish(w,r,at);}
    }
    private static void choose(CompanionWorld w,ResidentState r,Instant at) {
        int hour=at.atZone(ZoneId.of(w.timezone)).getHour();
        boolean quietNight=hour<6||hour>=23;
        boolean nightOwl=r.id.equals("owner")||r.id.equals("artist");
        if(r.energy<28||quietNight&&!nightOwl&&hour!=5){moveOrSchedule(w,r,"sleep","home",null,"先睡一会儿，明天还想把自己的小事做好",at,100);return;}
        if(r.energy<46){moveOrSchedule(w,r,"rest","cafe",null,"先喝口热水，别把想做的事变成负担",at,45);return;}
        Project goal=project(w,r.goal);
        if(goal==null||knownStatus(r,goal.id).equals("celebrating")) {
            goal=w.projects.stream().filter(p->knows(w,r.id,p.id)&&!knownStatus(r,p.id).equals("celebrating"))
                .min(Comparator.comparingInt(p->r.knownProjects.getOrDefault(p.id,new ProjectKnowledge(p.id,p.place,"idea",0,at,r.id)).progress()-(p.members.contains(r.id)?35:0))).orElse(null);
            if(goal!=null){r.goal=goal.id;r.thought="自己的事告一段落了，想看看能不能帮上"+actor(w,goal.ownerId).name()+"。";}
        }
        if(goal!=null&&knownStatus(r,goal.id).equals("ready")) {
            moveOrSchedule(w,r,"celebrate",knownPlace(r,goal),goal.id,"去看看大家一起做出来的"+goal.title,at,48);return;
        }
        if(r.social<50||goal!=null&&goal.contributors.size()<goal.needed&&r.knownProjects.getOrDefault(goal.id,new ProjectKnowledge(goal.id,goal.place,"idea",0,at,r.id)).progress()>=45) {
            ResidentState friend=w.residentStates.stream().filter(o->!o.id.equals(r.id)&&!actor(w,o.id).place().equals("home")
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
                int gain=first?25:12;
                // A communal project cannot finish through one resident's repeated work alone.
                project.progress=Math.min(project.contributors.size()<project.needed?75:100,project.progress+gain);
                project.status=project.progress==100?"ready":"active";
                project.description=actor(w,r.id).name()+"刚完成了一小部分；"+(project.progress==100?"已经可以一起看看了。":"还想听听别人的想法。");
                w.objects.removeIf(o->Objects.equals(o.projectId(),project.id));
                w.objects.add(new WorldObject("project-"+project.id,project.objectKind,project.place,project.title,project.progress==100?"finished":"progress-"+project.progress,project.id));
                String text=actor(w,r.id).name()+"在"+placeName(project.place)+"为「"+project.title+"」添了一笔"+(first?"，留下了自己的做法。":"。");
                String evidence=memory(w,r.id,r.id,"observed",at,project.id,text,List.of(),7);
                event(w,at,"contribution",project.place,List.of(r.id),text,project.id);
                for(ResidentState other:w.residentStates)if(!other.id.equals(r.id)&&actor(w,other.id).place().equals(project.place)&&!actor(w,other.id).activity().equals("walk"))
                    memory(w,other.id,r.id,"observed",at,project.id,"我看见"+text,List.of(evidence),6);
                r.energy=clamp(r.energy-4);r.curiosity=clamp(r.curiosity-10);r.mood=first?"有点得意":"踏实";
                if(project.progress==100){project.completedAt=at;event(w,at,"ready",project.place,new ArrayList<>(project.contributors),"「"+project.title+"」准备好了，和最初一个人的想法已经不太一样。",project.id);}
            }
        } else if(p.action().equals("celebrate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&project.status.equals("ready")) {
                project.status="celebrating";r.mood="开心";r.social=clamp(r.social+20);
                event(w,at,"celebration",project.place,new ArrayList<>(project.contributors),actor(w,r.id).name()+"招呼大家来看「"+project.title+"」。这一次，桌边多了几个熟悉的位置。",project.id);
                for(String member:project.contributors)if(actor(w,member).place().equals(project.place)&&!actor(w,member).activity().equals("walk"))memory(w,member,r.id,"observed",at,project.id,"我亲眼看见，参与的「"+project.title+"」真的做出来了。",List.of(),9);
            }
        } else if(p.action().equals("rest")||p.action().equals("sleep")){r.energy=clamp(r.energy+18);r.mood="松弛";}
        else if(p.action().equals("observe")){r.curiosity=clamp(r.curiosity-16);r.social=clamp(r.social-5);}
    }
    private static void moveOrSchedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        if(!actor(w,r.id).place().equals(place)) {
            r.desiredAction=action;r.plan=new Plan("p-"+(++w.eventSequence),"travel",place,target,reason,at,at.plusSeconds(12));
            r.revision++;r.thought=reason;
            replaceActor(w,r.id,"street","walk","准备去"+placeName(place)+"："+reason,r.plan.endsAt());
        } else schedule(w,r,action,place,target,reason,at,duration);
    }
    private static void schedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        r.plan=new Plan("p-"+(++w.eventSequence),action,place,target,reason,at,at.plusSeconds(duration));r.revision++;r.thought=reason;
        String label=switch(action){case "create","help"->"动手准备"+(project(w,target)==null?"手上的小事":"「"+project(w,target).title+"」");case "study"->"在窗边复习，想守住一点安静";case "invite"->reason;case "sleep"->"睡着了，给明天留一点精神";case "rest"->"捧着杯子歇一会儿";case "celebrate"->"想请大家看看一起做出来的东西";default->reason;};
        replaceActor(w,r.id,place,action,label,r.plan.endsAt());
    }
    private static boolean canInvite(CompanionWorld w,ResidentState a,ResidentState b,Project p,Instant at) {
        if(!knows(w,a.id,p.id)||knownStatus(a,p.id).equals("celebrating")||p.members.contains(b.id))return false;
        Instant previous=p.invitationHistory.get(pairKey(a.id,b.id));
        if(previous==null)previous=w.conversations.stream().filter(c->c.topicId.equals(p.id)&&c.participantIds.contains(a.id)&&c.participantIds.contains(b.id)).map(c->c.startedAt).max(Comparator.naturalOrder()).orElse(null);
        return previous==null||Duration.between(previous,at).getSeconds()>=900;
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
        c.turns.add(new Turn(a.id,"我在琢磨「"+p.title+"」。"+(a.relationships.getOrDefault(b.id,40)>55?"想到你上次说的话了。":"要是你现在方便，")+invitation,at));
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
        propose(w,r,actor(w,r.id).place().equals("home")?"cafe":actor(w,r.id).place(),title,kind,
            "上次和邻居一起做成了「"+previous.title+"」，想把那一点默契接着用下去。",ownEvidence(w,r.id,previous.id),at);
    }
    public static boolean proposeDecision(CompanionWorld w,String id,long residentRevision,long intentRevision,String place,String title,String objectKind,String reason,List<String> evidence,Instant now) {
        ResidentState r=state(w,id);
        if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision||!PLACES.contains(place)||place.equals("home"))return false;
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
        if(!PLACES.contains(place)||!Set.of("observe","create","help","invite","rest","study").contains(action))return false;
        if(reason==null||reason.isBlank()||reason.length()>160||speech!=null&&speech.length()>180)return false;
        if(evidence==null||evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        if(Set.of("create","help").contains(action)){Project p=project(w,target);if(p==null||!knows(w,r.id,p.id)||!p.place.equals(place)||Set.of("ready","celebrating").contains(p.status))return false;}
        if(action.equals("invite")&&(target==null||state(w,target)==null||!actor(w,target).place().equals(actor(w,r.id).place())))return false;
        // Model may enrich this resident's current speaking turn; never invent the other party's reply.
        Conversation c=activeConversation(w,residentId);
        if(c!=null) {
            if(speech==null||speech.isBlank()||!actor(w,r.id).place().equals(c.place))return false;
            c.turns.add(new Turn(r.id,speech,now));c.updatedAt=now;
            for(String listener:c.participantIds)if(!listener.equals(r.id)&&actor(w,listener).place().equals(c.place))
                memory(w,listener,r.id,"heard",now,c.topicId,actor(w,r.id).name()+"当面说：“"+speech+"”",evidence,6);
            replaceActor(w,r.id,c.place,"talk",speech,now.plusSeconds(24));
        } else moveOrSchedule(w,r,action,place,target,reason,now,60);
        if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,r.goal,reason,evidence,7);
        w.modelStatus="模型刚为"+actor(w,r.id).name()+"补充了一个念头";w.revision++;r.revision++;
        event(w,now,"thought",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"想了想："+reason,target);
        return true;
    }
    private static void perceive(CompanionWorld w,ResidentState r,Instant now) {
        Actor a=actor(w,r.id);if(a.activity().equals("walk")||a.activity().equals("sleep"))return;
        for(Project p:w.projects)if(p.place.equals(a.place())&&(p.progress>0||knows(w,r.id,p.id))) {
            r.knownProjects.put(p.id,new ProjectKnowledge(p.id,p.place,p.status,p.progress,now,r.id));
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
    public static Actor actor(CompanionWorld w,String id){return w.residents.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow();}
    public static Project project(CompanionWorld w,String id){return w.projects.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);}
    public static boolean knows(CompanionWorld w,String id,String topic){return w.memories.stream().anyMatch(m->m.ownerId().equals(id)&&Objects.equals(m.topicId(),topic)&&!m.sourceType().equals("reflection"));}
    public static Conversation activeConversation(CompanionWorld w,String id){return w.conversations.stream().filter(c->c.status.equals("active")&&c.participantIds.contains(id)).findFirst().orElse(null);}
    static void relation(ResidentState a,ResidentState b,int delta){a.relationships.compute(b.id,(k,v)->Math.max(0,Math.min(100,(v==null?40:v)+delta)));b.relationships.compute(a.id,(k,v)->Math.max(0,Math.min(100,(v==null?40:v)+delta)));}
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
    private static String placeName(String place){return switch(place){case "cafe"->"咖啡馆";case "garden"->"花园";case "home"->"住处";default->"小街";};}
}
