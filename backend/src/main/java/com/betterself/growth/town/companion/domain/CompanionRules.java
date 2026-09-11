package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

public final class CompanionRules {
    private CompanionRules() {}
    /** "sleep" is here because the user could not previously say it: seven expressible intents and not
     * one of them was going to bed, so the only way the avatar's night could ever be spent was however
     * the autopilot below decided to spend it. */
    public static final Set<String> KINDS = Set.of("focus", "rest", "walk", "home", "flowers", "water", "thought", "sleep");
    private static final String[] IDS={"owner","student","artist","gardener"};
    private static final String[] NAMES={"阿禾","小川","知夏","青叔"};
    private static final String[] ROLES={"咖啡馆店主","备考邻居","插画师","园艺爱好者"};

    public static CompanionWorld join(String id, String name, String timezone, Instant now) {return join(id,name,timezone,now,false);}
    public static CompanionWorld join(String id, String name, String timezone, Instant now,boolean modelConversations) {
        CompanionWorld w=new CompanionWorld();w.modelConversationsEnabled=modelConversations; w.id=id; w.name=name; w.timezone=timezone;
        w.joinedAt=now; w.updatedAt=now; w.lastEncounterSlot=now.getEpochSecond()/300;
        w.avatar=actor("self",name,"新邻居","street","walk","提着行李，看看这条小街",now.plusSeconds(30));
        ResidentSimulation.ensureAvatarState(w);
        for(int i=0;i<4;i++) w.residents.add(resident(i,now,timezone));
        remember(w,"owner","history","seed",now.minusSeconds(86400),"reading-night","我想在咖啡馆办一场读书晚会，知夏答应帮忙画海报。");
        remember(w,"artist","owner","seed",now.minusSeconds(86400),"reading-night","阿禾邀请我画读书晚会的海报，我已经开始构思。");
        remember(w,"student","history","seed",now.minusSeconds(86400),"study-habit","我常在咖啡馆靠窗的位置备考，阿禾会留一杯温水。");
        remember(w,"gardener","history","seed",now.minusSeconds(86400),"garden-habit","花园里新开的花适合送给朋友，我还没听说最近有什么聚会。");
        diary(w,now,"搬进小街了。咖啡馆就在住处旁边，邻居们已经有各自的日子。先慢慢认识这里。");
        environment(w,now); w.revision=1; ResidentSeed.initialize(w,now); return w;
    }

    public static void advance(CompanionWorld w, Instant now) {
        if(!now.isAfter(w.updatedAt)) return;
        long gap=Duration.between(w.updatedAt,now).getSeconds();
        w.offlineSummary=null;
        if(gap>1800) {
            w.offlineSummary="离开期间大家照常生活。只保留回来时的一小段近况，欢迎回来。";
            diary(w,now,w.offlineSummary);
        }
        if(w.focus!=null && !now.isBefore(w.focus.endsAt())) {
            Instant ended=w.focus.endsAt(); w.focus=null;
            finishActive(w,"专注时间到了，先伸个懒腰。Todo 留给你确认。", "done");
            diary(w,ended,"安静地专注了一会儿。时间到了，接下来喝水休息；任务是否完成，仍由你决定。");
            String place="open".equals(w.cafeStatus)?"cafe":TownPlaces.homeOf("self");
            w.avatar=actor("self",w.name,"小街住民",place,"water","喝杯水，松松肩膀",now.plusSeconds(60));
            placeAvatar(w,now);
        }
        if(w.focus==null && !now.isBefore(w.avatar.until())) {
            finishActive(w,"已经照着这个念头做了。", "done");
            Intent next=w.intents.stream().filter(i->i.status.equals("pending")).findFirst().orElse(null);
            if(next!=null) start(w,next,now); else w.avatar=autonomous(w,now);
        }
        environment(w,now); ResidentSimulation.advance(w,now);
        environment(w,now); w.updatedAt=now; w.revision++;
    }

    public static void submit(CompanionWorld w, Intent intent, Instant now) {
        if(w.intents.stream().anyMatch(i->i.id.equals(intent.id))) return;
        w.intents.add(intent); w.intentRevision++;
        if("explicit".equals(intent.priority)) {
            finishActive(w,"已让位给你的新安排。", "cancelled"); w.focus=null;
            start(w,intent,now);
        } else if(w.focus==null && !now.isBefore(w.avatar.until())) start(w,intent,now);
        w.revision++;
    }
    public static void cancel(CompanionWorld w, String id, Instant now) {
        for(Intent i:w.intents) if(i.id.equals(id) && Set.of("active","pending").contains(i.status)) {
            boolean active=i.status.equals("active"); i.status="cancelled"; w.intentRevision++; i.feedback="这个念头已放下。";
            if(active) { w.focus=null; w.avatar=autonomous(w,now); }
            w.revision++; return;
        }
    }
    private static void start(CompanionWorld w, Intent i, Instant now) {
        String action=resolvedAction(i);
        i.status="active"; i.feedback="focus".equals(action)?"去咖啡馆坐好，陪你专注。":"好，就去做这件事。";
        String place=avatarPlace(w,action);
        String label=avatarLabel(action);
        Instant until=now.plusSeconds(action.equals("focus")?i.durationMinutes*60L:120);
        if(action.equals("focus")) w.focus=new Focus(i.taskId,now,until);
        w.avatar=actor("self",w.name,"小街住民",place,action,label,until);
        placeAvatar(w,now);
        if(i.text!=null)i.feedback="记下这个念头了："+i.text+"。现在"+label+"。";
        diary(w,now,label+"。");
    }
    /** {@code i.resolvedKind} overrides {@code i.kind} whenever the user's own free text was resolved
     * into something more specific (see {@code CompanionService.resolve}) - the one place this mapping
     * happens, shared by {@link #start} and {@link #lastRealIntent}'s continuation so the two never
     * quietly disagree about what an intent actually asked for. */
    private static String resolvedAction(Intent i){return i.resolvedKind==null?i.kind:i.resolvedKind;}
    private static String avatarPlace(CompanionWorld w,String action){
        return switch(action){case "home","rest","sleep"->TownPlaces.homeOf("self");case "flowers"->"garden";case "walk","ponder"->"street";default->"open".equals(w.cafeStatus)?"cafe":TownPlaces.homeOf("self");};
    }
    private static String avatarLabel(String action){
        return switch(action){case "sleep"->"回到住处，躺下睡了";case "focus"->"在公共书桌安静专注";case "visit"->"去咖啡馆看看邻居在做什么";case "study"->"找个位置，安静读几页书";case "ponder"->"在街边想一想，还没决定怎么实现这个念头";case "home"->"回到住处，整理今天";case "rest"->"靠一会儿，让脑子放空";case "flowers"->"看看刚开的花";case "water"->"喝一杯温水";default->"沿着小街慢慢散步";};
    }
    /** The avatar shares the same location/position model as the four NPCs: claim a spot for it at
     * its current place so residents can perceive it there and, when a spot is scarce, contend for
     * it the same way they would with each other.
     * <p>Only when the current activity actually needs one, exactly like {@link ResidentSimulation}'s
     * own {@code schedule()} does for every resident ("能站的地方都能去", docs/04): {@code claim()} is
     * willing to hand out ANY position at the place once asked with a null kind, which used to mean a
     * plain "drink some water" reclaimed a random spot at the place - the student's window seat traded
     * for the shared long table - every time the old clock-driven autopilot happened to land there. */
    private static void placeAvatar(CompanionWorld w, Instant now) {
        ResidentSimulation.ensureAvatarState(w);
        String kind=ResidentSimulation.avatarPreferredKind(w.avatar.activity(),w.avatar.place());
        if(kind==null){TownPlaces.release(w,"self",now);return;}
        TownPlaces.claim(w,"self",w.avatar.place(),kind,now);
    }
    private static void finishActive(CompanionWorld w,String feedback,String status) {
        for(Intent i:w.intents) if(i.status.equals("active")){i.status=status;i.feedback=feedback;}
    }
    /** The user's own real arrangement, most recently. Never "cancelled" - that one was explicitly
     * taken back - but "done" counts: it is the last thing this person actually decided to do, and
     * there is nothing more recent to prefer over it. */
    private static Intent lastRealIntent(CompanionWorld w){
        return w.intents.stream().filter(i->!"cancelled".equals(i.status))
            .max(Comparator.comparing(i->i.createdAt)).orElse(null);
    }
    /** Same order of magnitude {@link ResidentSimulation#applyDecision}'s own duration table already
     * uses for the closest resident equivalent (study/observe/rest) - a continuation is a stretch of
     * the day, not a two-minute clock tick. */
    private static long continuationSeconds(String action){
        return switch(action){
            case "focus","study","visit","flowers"->1800;
            case "rest","home"->1200;
            case "walk","ponder"->900;
            default->300;
        };
    }
    /** What the avatar does with no explicit instruction pending - the fallback every resident also
     * has, except the avatar's version cannot be either of the two shapes a resident's can be.
     * <p>The residents' own pure-rule fallback ({@link ResidentSimulation}'s {@code awaitDecision})
     * only ever answers "暂时没有新的安排" - which is exactly why residents barely move at all in a
     * rule-only run (see docs/04). Copied straight across, the avatar would stop being erratic and
     * simply stop, which is not the same thing as being alive.
     * <p>The other shape, a {@code ResidentMind} that decides for the avatar the way one decides for
     * an NPC, would make the model guess what the user is doing - a second paid call spent inventing a
     * life the user already has.
     * <p>So the base is the same as a resident's (sleep window, the same duration table an actual
     * decision would use, the same seat-claiming rule), and the one thing that is genuinely different
     * is the source of the plan: a resident's {@code dayPlan} is written by its own model; the avatar's
     * is simply the user's own last real request, from {@link #lastRealIntent} - {@link CompanionWorld#intents}
     * IS the user's day, already on file, for free. */
    private static Actor autonomous(CompanionWorld w,Instant now) {
        ResidentSimulation.ensureAvatarState(w);
        String action; long durationSeconds;
        // One clock for whether anyone is asleep (see withinUsualSleepWindow's own note on the bug two
        // hardcoded night windows caused): sleep always wins over whatever the user last asked for,
        // exactly as it would for any resident.
        if(ResidentSimulation.withinUsualSleepWindow(w,"self",now)) {
            action="sleep";
            durationSeconds=ResidentSimulation.sleepDurationSeconds(w,ResidentSimulation.state(w,"self"),now);
        } else {
            Intent last=lastRealIntent(w);
            action=last==null?null:resolvedAction(last);
            durationSeconds=continuationSeconds(action==null?"":action);
        }
        boolean quiet=action==null;
        String place=quiet?("open".equals(w.cafeStatus)?"cafe":TownPlaces.homeOf("self")):avatarPlace(w,action);
        String label=quiet?"想着自己的事，不着急做什么":avatarLabel(action);
        String activity=quiet?"thought":action;
        if(!w.avatar.activity().equals(activity)) diary(w,now,label+"。");
        Actor a=actor("self",w.name,"小街住民",place,activity,label,now.plusSeconds(durationSeconds));
        w.avatar=a; placeAvatar(w,now); return a;
    }
    private static Actor resident(int i,Instant now,String timezone) {
        return actor(IDS[i],NAMES[i],ROLES[i],i==3?"garden":"cafe","observe","正在过自己的日子",now.plusSeconds(40));
    }
    private static Actor actor(String id,String name,String role,String place,String activity,String label,Instant until){
        // Legacy per-place pixel hint, kept only for older clients; the new position model below
        // carries no coordinates at all - ids are all the frontend gets for anything more specific.
        double x=TownPlaces.isHome(place)?180:switch(place){case "cafe"->520;case "garden"->800;default->430;};
        return new Actor(id,name,role,place,activity,label,x,place.equals("street")?430:260,until);
    }
    private static void environment(CompanionWorld w,Instant now){
        var local=now.atZone(ZoneId.of(w.timezone)); int h=local.getHour();
        w.period=h<6||h>=23?"night":h<12?"morning":h<18?"afternoon":"evening";
        w.weather=Math.floorMod(local.toLocalDate().toEpochDay()+w.id.hashCode(),5)==0?"rain":"sunny";
    }
    private static void diary(CompanionWorld w,Instant now,String text){w.diary.add(new Entry("d-"+w.revision+"-"+w.diary.size()+"-"+now.toEpochMilli(),now,text)); while(w.diary.size()>80)w.diary.removeFirst();}
    private static void remember(CompanionWorld w,String owner,String source,String type,Instant now,String topic,String text){w.memories.add(new Memory("m-"+w.memories.size()+"-"+now.toEpochMilli(),owner,source,type,now,text,topic));while(w.memories.size()>100)w.memories.removeFirst();}
}
