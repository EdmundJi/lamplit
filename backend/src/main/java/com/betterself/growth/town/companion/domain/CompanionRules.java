package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

public final class CompanionRules {
    private CompanionRules() {}
    public static final Set<String> KINDS = Set.of("focus", "rest", "walk", "home", "flowers", "water", "thought");
    private static final String[] IDS={"owner","student","artist","gardener"};
    private static final String[] NAMES={"阿禾","小川","知夏","青叔"};
    private static final String[] ROLES={"咖啡馆店主","备考邻居","插画师","园艺爱好者"};

    public static CompanionWorld join(String id, String name, String timezone, Instant now) {return join(id,name,timezone,now,false);}
    public static CompanionWorld join(String id, String name, String timezone, Instant now,boolean modelConversations) {
        CompanionWorld w=new CompanionWorld();w.modelConversationsEnabled=modelConversations; w.id=id; w.name=name; w.timezone=timezone;
        w.joinedAt=now; w.updatedAt=now; w.lastEncounterSlot=now.getEpochSecond()/300;
        w.avatar=actor("self",name,"新邻居","street","walk","提着行李，看看这条小街",now.plusSeconds(30));
        for(int i=0;i<4;i++) w.residents.add(resident(i,now,timezone));
        remember(w,"owner","history","seed",now.minusSeconds(86400),"reading-night","我想在咖啡馆办一场读书晚会，知夏答应帮忙画海报。");
        remember(w,"artist","owner","seed",now.minusSeconds(86400),"reading-night","阿禾邀请我画读书晚会的海报，我已经开始构思。");
        remember(w,"student","history","seed",now.minusSeconds(86400),"study-habit","我常在咖啡馆靠窗的位置备考，阿禾会留一杯温水。");
        remember(w,"gardener","history","seed",now.minusSeconds(86400),"garden-habit","花园里新开的花适合送给朋友，我还没听说最近有什么聚会。");
        diary(w,now,"搬进小街了。咖啡馆就在住处旁边，邻居们已经有各自的日子。先慢慢认识这里。");
        environment(w,now); w.revision=1; ResidentSimulation.initialize(w,now); return w;
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
            w.avatar=actor("self",w.name,"小街住民","cafe","water","喝杯水，松松肩膀",now.plusSeconds(60));
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
        String action=i.resolvedKind==null?i.kind:i.resolvedKind;
        i.status="active"; i.feedback="focus".equals(action)?"去咖啡馆坐好，陪你专注。":"好，就去做这件事。";
        String place=switch(action){case "home","rest"->"home";case "flowers"->"garden";case "walk","ponder"->"street";default->"cafe";};
        String label=switch(action){case "focus"->"在公共书桌安静专注";case "visit"->"去咖啡馆看看邻居在做什么";case "study"->"找个位置，安静读几页书";case "ponder"->"在街边想一想，还没决定怎么实现这个念头";case "home"->"回到住处，整理今天";case "rest"->"靠一会儿，让脑子放空";case "flowers"->"看看刚开的花";case "water"->"喝一杯温水";default->"沿着小街慢慢散步";};
        Instant until=now.plusSeconds(action.equals("focus")?i.durationMinutes*60L:120);
        if(action.equals("focus")) w.focus=new Focus(i.taskId,now,until);
        w.avatar=actor("self",w.name,"小街住民",place,action,label,until);
        if(i.text!=null)i.feedback="记下这个念头了："+i.text+"。现在"+label+"。";
        diary(w,now,label+"。");
    }
    private static void finishActive(CompanionWorld w,String feedback,String status) {
        for(Intent i:w.intents) if(i.status.equals("active")){i.status=status;i.feedback=feedback;}
    }
    private static Actor autonomous(CompanionWorld w,Instant now) {
        int hour=now.atZone(ZoneId.of(w.timezone)).getHour();
        int phase=(int)((now.getEpochSecond()/120)%8);
        String action=(hour<7||hour>=23)?"home":switch(phase){case 0->"water";case 1->"rest";case 2->"walk";case 3->"flowers";default->"study";};
        String place=switch(action){case "home","rest"->"home";case "flowers"->"garden";case "walk","ponder"->"street";default->"cafe";};
        String label=switch(action){case "home"->"回家休息，灯光轻轻暗下来";case "rest"->"歇一小会儿";case "water"->"记得给自己倒杯水";case "walk"->"出门透透气";case "flowers"->"在花园看看新叶";default->"翻开书，安静读上几页";};
        if(!w.avatar.activity().equals(action)) diary(w,now,label+"。");
        return actor("self",w.name,"小街住民",place,action,label,now.plusSeconds(120));
    }
    private static Actor resident(int i,Instant now,String timezone) {
        return actor(IDS[i],NAMES[i],ROLES[i],i==3?"garden":"cafe","observe","正在过自己的日子",now.plusSeconds(40));
    }
    private static Actor actor(String id,String name,String role,String place,String activity,String label,Instant until){
        double x=switch(place){case "home"->180;case "cafe"->520;case "garden"->800;default->430;};
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
