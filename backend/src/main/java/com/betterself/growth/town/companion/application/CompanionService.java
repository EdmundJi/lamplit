package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class CompanionService {
    private final WorldStore store;
    private final Clock clock;
    private final ResidentDirector director;
    public CompanionService(WorldStore store,Clock clock,ResidentDirector director){this.store=store;this.clock=clock;this.director=director;}
    public record View(boolean joined, CompanionWorld world) {}
    public record Join(String name,String timezone) {}
    public record Command(String id,String kind,String priority,String taskId,Integer durationMinutes,String text) {
        public Command(String id,String kind,String priority,String taskId,Integer durationMinutes){this(id,kind,priority,taskId,durationMinutes,null);}
    }
    public View get(long userId){return view(store.read(userId));}
    public View join(long userId,Join command){
        String name=command==null||command.name()==null||command.name().isBlank()?"我":command.name().strip();
        if(name.length()>24)throw invalid("名字最多 24 个字");
        String timezone=command==null||command.timezone()==null?store.timezone(userId):command.timezone();
        try{ZoneId.of(timezone);}catch(Exception e){throw invalid("请选择有效时区");}
        Instant now=clock.instant();
        var world=store.update(userId,()->CompanionRules.join(UUID.randomUUID().toString(),name,timezone,now,director.enabled()),w->w);director.consider(userId,world);return view(world);
    }
    public View advance(long userId){var world=store.update(userId,null,w->{w.modelConversationsEnabled=director.enabled();CompanionRules.advance(w,clock.instant());return w;});director.consider(userId,world);return view(world);}
    public View submit(long userId,Command c){
        if(c==null||c.id()==null||!c.id().matches("[A-Za-z0-9_-]{8,80}")||!CompanionRules.KINDS.contains(c.kind()==null?"":c.kind())||!Set.of("explicit","passing").contains(c.priority()==null?"":c.priority()))throw invalid("请提供有效的念头和请求编号");
        if(c.kind().equals("thought")&&(c.text()==null||c.text().isBlank()||c.text().length()>200))throw invalid("念头请写在 200 字以内");
        if(!c.kind().equals("focus")&&c.taskId()!=null)throw invalid("只有专注安排需要关联 Todo");
        int minutes=c.durationMinutes()==null?25:c.durationMinutes();
        if(minutes<1||minutes>180)throw invalid("专注时长应为 1 至 180 分钟");
        if(c.kind().equals("focus")&&(c.taskId()==null||!store.ownsTask(userId,c.taskId())))throw new ApiException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","请选择你自己的有效 Todo");
        return view(store.update(userId,null,w->{
            var previous=w.intents.stream().filter(i->i.id.equals(c.id())).findFirst().orElse(null);
            if(previous!=null){
                if(!previous.kind.equals(c.kind())||!previous.priority.equals(c.priority())||!Objects.equals(previous.taskId,c.taskId())||previous.durationMinutes!=minutes||!Objects.equals(previous.text,c.text()))throw new ApiException(HttpStatus.CONFLICT,"INTENT_ID_REUSED","这个请求编号已用于另一个念头");
                return w;
            }
            if(w.intents.stream().filter(i->i.status.equals("pending")).count()>=20)throw invalid("先让小人实现或放下几个念头吧");
            Instant now=clock.instant(); CompanionRules.advance(w,now);
            CompanionWorld.Intent intent=new CompanionWorld.Intent(c.id(),c.kind(),c.priority(),c.kind().equals("focus")?c.taskId():null,minutes,now);
            intent.text=c.text();if(c.kind().equals("thought"))intent.resolvedKind=resolve(c.text());
            CompanionRules.submit(w,intent,now);return w;
        }));
    }
    public View cancel(long userId,String id){return view(store.update(userId,null,w->{CompanionRules.cancel(w,id,clock.instant());return w;}));}
    private static String resolve(String text) {
        if(text.matches(".*(花|园|树|叶|种).*"))return "flowers";
        if(text.matches(".*(回家|住处|回去).*"))return "home";
        if(text.matches(".*(水|渴|喝).*"))return "water";
        if(text.matches(".*(休息|累|睡|歇).*"))return "rest";
        if(text.matches(".*(咖啡|书会|聚会|阿禾|邻居).*"))return "visit";
        if(text.matches(".*(学习|读书|复习|看书).*"))return "study";
        if(text.matches(".*(走|逛|散步|街|透气).*"))return "walk";
        return "ponder";
    }
    private static View view(CompanionWorld w){return new View(w!=null,w);}
    private static ApiException invalid(String text){return new ApiException(HttpStatus.BAD_REQUEST,"COMPANION_INVALID",text);}
}
