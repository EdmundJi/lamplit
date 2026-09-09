package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;
import static com.betterself.growth.town.companion.domain.ResidentSimulation.*;

/**
 * Who lives here, and what they start out with.
 *
 * <p>Split out of {@link ResidentSimulation} so that adding a neighbour is a change to one file
 * about people, not a change inside the loop that moves everyone's day forward. Everything here runs
 * once per world (or once per hand-authored arrival) and then never again: the initial four
 * residents, their homes, their opening plans, and the small shared history that gives them
 * something to already know about each other.
 *
 * <p>{@link #addResident} stays the only way a new resident appears. Nothing in the simulation calls
 * it - there is no recruitment, no applicants, no "the town needed someone so one showed up". A
 * neighbour exists because we wrote them, which is what docs/04-decisions.md means by 新增居民由我们
 * 手动设计.
 */
public final class ResidentSeed {
    private ResidentSeed() {}

    static final List<String> IDS=List.of("owner","student","artist","gardener");
    /** Old worlds used a public-project id as the entire life of a resident.  Keep that id as a
     * possible thread, but seed a resident-owned purpose so private work, rest and a change of life
     * have somewhere durable to live. */
    static void reconcileLife(CompanionWorld w,Instant at){
        if(w.cafeOperatorId==null||state(w,w.cafeOperatorId)==null)w.cafeOperatorId="owner";
        for(ResidentState r:w.residentStates){
            if(r.id.equals("self"))continue;
            if(r.occupation==null||r.occupation.isBlank())r.occupation=defaultOccupation(r.id);
            if(r.lifeIntent==null)r.lifeIntent=lifeIntent(w,r,r.goal,r.occupation,"active",at);
            if(r.careerIntent==null)r.careerIntent=lifeIntent(w,r,null,r.occupation+"。我知道总得找到能长期做下去的事，但也允许自己先歇一歇、慢慢想。","active",at);
            if(r.lifeIntent.status==null)r.lifeIntent.status="active";
            if(!r.sleepScheduleSeeded){r.usualSleepMinute=23*60;r.usualWakeMinute=7*60;r.sleepScheduleSeeded=true;}
        }
    }
    static String defaultOccupation(String id){return switch(id){case "owner"->"经营咖啡馆，也想留下自己的时间";case "student"->"备考，也在摸索以后想过怎样的日子";case "artist"->"画画、接零散的创作活";case "gardener"->"照看花草和邻里的小事";default->"找一件能长期做下去的事";};}
    public static void initialize(CompanionWorld w,Instant now) {
        if(w.simulationVersion>=2)return;
        long initialRevision=w.revision;
        w.simulationVersion=4; w.residentStates.clear();w.projects.clear();w.objects.clear();w.conversations.clear();
        CafeService.reconcileSchedule(w,now);
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
            r.occupation=switch(r.id){case "owner"->"经营咖啡馆，也想留下自己的时间";case "student"->"备考，也在摸索以后想过怎样的日子";case "artist"->"画画、接零散的创作活";default->"照看花草和邻里的小事";};
            r.lifeIntent=lifeIntent(w,r,null,r.occupation+"。我知道总得找到能长期做下去的事，但也允许自己先歇一歇、慢慢想。","active",past);
            r.careerIntent=lifeIntent(w,r,null,r.occupation+"。我知道总得找到能长期做下去的事，但也允许自己先歇一歇、慢慢想。","active",past);
            r.lastSocialAt=past.minusSeconds(120);r.lastReflectionAt=past;r.energyUpdatedAt=past;r.usualSleepMinute=23*60;r.usualWakeMinute=7*60;r.sleepScheduleSeeded=true;r.revision=1;
            for(String other:IDS)if(!other.equals(r.id))r.relationships.put(other,other.equals("owner")?62:38+Math.floorMod((w.id+r.id+other).hashCode(),20));
            w.residentStates.add(r);
            Project own=w.projects.get(i);r.knownProjects.put(own.id,new ProjectKnowledge(own.id,own.place,own.status,own.progress,past,r.id));
            memory(w,r.id,"history","seed",past.minusSeconds(86400),r.goal,r.thought,List.of(),7);
            boolean open="open".equals(w.cafeStatus);
            String place=open?(i<3?"cafe":"garden"):TownPlaces.homeOf(r.id);
            String action=open?(i==1?"study":"observe"):switch(r.id){case "student"->"study";case "artist"->"make";case "gardener"->"work";default->"rest";};
            String reason=open?(i==1?"先守住今天的复习时间":"看看邻居手上的事，再决定从哪里开始"):switch(r.id){case "student"->"在自己桌前看书";case "artist"->"在家收一收没画完的线稿";case "gardener"->"在家整理种子和工具";default->"店还没开，先在家歇一会儿";};
            schedule(w,r,action,place,null,reason,past,open?36+i*7:1800);
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
        w.modelConversationsEnabled=modelMode;
        if("open".equals(w.cafeStatus)){
            ResidentState owner=state(w,"owner"),artist=state(w,"artist");
            schedule(w,owner,"observe","cafe",null,"等知夏看看桌上还没定稿的招贴",now,42);
            schedule(w,artist,"observe","cafe",null,"想先问清小聚想让人记住什么",now,48);
            owner.lastSocialAt=now.minusSeconds(120);artist.lastSocialAt=now.minusSeconds(120);
            w.conversations.stream().filter(c->c.status.equals("active")).forEach(c->c.status="ended");
            startConversation(w,owner,artist,w.projects.get(0),now);
            event(w,now,"arrival","street",List.of(),"小街的日子早就开始了。咖啡馆里，一张招贴还没有定稿。",null);
        } else event(w,now,"arrival","street",List.of(),"小街安静下来。咖啡馆已经打烊，居民们各自在家。",null);
        w.revision=initialRevision;
    }
    public static boolean addResident(CompanionWorld w,String id,String name,String role,String occupation,Instant now){
        if(id==null||!id.matches("[a-z][a-z0-9-]{1,30}")||name==null||name.isBlank()||role==null||role.isBlank()||occupation==null||occupation.isBlank()||state(w,id)!=null||w.residents.stream().anyMatch(a->a.id().equals(id)))return false;
        TownPlaces.seed(w);TownPlaces.addHome(w,id);
        ResidentState r=new ResidentState();r.id=id;r.energy=65;r.social=55;r.curiosity=55;r.mood="刚搬来，还在认路";r.occupation=occupation;r.thought="先熟悉这里，也想找一件能长期做下去的事";r.lastSocialAt=now;r.lastReflectionAt=now;r.energyUpdatedAt=now;r.usualSleepMinute=23*60;r.usualWakeMinute=7*60;r.sleepScheduleSeeded=true;r.revision=1;r.lifeIntent=lifeIntent(w,r,null,occupation,"active",now);r.careerIntent=lifeIntent(w,r,null,occupation,"active",now);
        for(ResidentState other:w.residentStates)if(!other.id.equals("self")){r.relationships.put(other.id,40);other.relationships.put(id,40);}
        w.residentStates.add(r);w.residents.add(new Actor(id,name,role,TownPlaces.homeOf(id),"rest","刚搬来，先在住处整理东西",180,260,now.plusSeconds(60)));
        memory(w,id,id,"seed",now,null,"我刚搬到这条街，想慢慢把“"+occupation+"”过成能维持生活的事，也先允许自己休息和认识邻居。",List.of(),7);
        schedule(w,r,"rest",TownPlaces.homeOf(id),null,"刚搬来，先在住处整理东西",now,1200);return true;
    }
    // The avatar is not in w.residents (that list stays exactly the four NPCs the frontend already
    // renders); "self" resolves to w.avatar instead so any resident-facing lookup still finds it.
}
