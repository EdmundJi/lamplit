package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.DaySegment;
import com.betterself.growth.town.companion.domain.CompanionWorld.Duty;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 初始基因：每个人平常的日子大概是什么样。
 *
 * <p>Smallville gives every one of its 25 agents a seed paragraph naming their work, where it happens
 * and who they do it with, and each morning the agent turns that into a plan that walks them around
 * the map. Our residents had the occupation sentence and nothing downstream of it: a day plan of
 * three free-text lines nobody ever read back, and one shared 07:00-23:00 clock. A 25-person town
 * measured idle in its own beds at 10:39.
 *
 * <p>So each resident is planted with a handful of usual stretches - roughly when, where, doing what,
 * for whom - that interlock (bread reaches the counter, honey reaches the baker, notices reach the
 * board) so that the work itself gives people reasons to cross paths. This is belief, not schedule:
 * nothing here moves anybody. The morning day plan (their own model) reads it, and the decision loop
 * offers "what I planned for now" as a cue with its option named. Everything these seeds make likely
 * is a planted expectation and belongs on the blind-scan list, never counted as emergence.
 */
public final class ResidentDuties {
    private ResidentDuties(){}

    /** Actions a day plan may name. Each already exists as an ordinary decision action. Not "tend":
     * that one is only ever offered against a real waiting order, so it cannot be planned ahead. */
    public static final Set<String> PLAN_ACTIONS=Set.of(
        "work","make","study","read","observe","rest","help","open_cafe",
        "tend_plants","exercise","read_notice","cook","repair","create");

    record Seed(int wakeMinute,int sleepMinute,List<Duty> duties,String memory) {}
    /** Bumped whenever the table changes, so a world planted from an older table gets the new
     * duties and clocks on its next tick. v2 added lunch in a public place for almost everybody. */
    static final int VERSION=2;

    private static Duty duty(String from,String to,String place,String action,String what,String toId){
        Duty d=new Duty();d.startMinute=minute(from);d.endMinute=minute(to);d.place=place;d.action=action;d.what=what;d.toId=toId;return d;
    }
    private static Seed seed(String wake,String sleep,String memory,Duty... duties){
        return new Seed(minute(wake),minute(sleep),List.of(duties),memory);
    }

    static final Map<String,Seed> SEEDS=Map.ofEntries(
        Map.entry("owner",seed("06:45","23:00","只要还有人推门进来，这一天就没白开。老宋的面包九点送到，苏晚帮我看着柜台，这些我都记在心里。",
            duty("08:30","09:00","cafe","open_cafe","开门前把柜台摆好",null),
            duty("11:00","14:00","cafe","work","招呼客人、看店",null),
            duty("20:30","21:00","cafe","work","打烊前收拾桌子",null))),
        Map.entry("student",seed("07:00","23:30","咖啡馆一开门，窗边那个位子就是我的。下午去学堂复习，老谭知道的老事比书上细。",
            duty("09:00","11:00","cafe","study","在窗边看书",null),
            duty("14:00","17:00","academy","study","去学堂复习，问老谭历史题","scholar"),
            duty("11:30","12:30","cafe","rest","在咖啡馆吃午饭",null))),
        Map.entry("artist",seed("09:00","01:00","招贴画得好不好，我先拿给阿禾看一眼，阿禾脸上的表情比嘴上说的准。阿满常等我说完才睡。",
            duty("10:00","13:00","garden","make","在花园写生，收集小街的颜色",null),
            duty("16:00","16:30","cafe","observe","把招贴草稿拿给阿禾看","owner"),
            duty("20:00","22:00","home","make","在家画画，常忘了时间",null),
            duty("13:00","14:00","cafe","rest","去咖啡馆吃点东西","owner"))),
        Map.entry("gardener",seed("05:00","21:30","植物不等人，天一亮我就去花园。阿橘跟着我干活，手脚越来越像样了，我没说破。",
            duty("05:30","08:00","garden","tend_plants","浇水、翻地、照看花圃",null),
            duty("08:00","09:30","garden","work","带阿橘一起干活","botanist"),
            duty("16:00","16:30","garden","tend_plants","把育好的秧苗留给来换花盆的人",null),
            duty("12:00","13:00","garden","rest","在花园树荫下吃午饭","botanist"))),
        Map.entry("fixer",seed("07:00","23:00","陆敏中午会把账念给我听一遍，对不上我就直说。阿满的工具够不够，我也顺手看一眼。",
            duty("09:00","12:30","shop","work","在铺子里修自行车、家具、灯具",null),
            duty("14:00","16:30","shop","work","接着修，看看阿满缺不缺工具","weaver"),
            duty("12:30","13:30","cafe","rest","去咖啡馆吃午饭",null))),
        Map.entry("weaver",seed("06:30","23:30","周野要的工具，我尽量当天就找齐。知夏画画到很晚，我睡前总要过去问一句今天顺不顺。",
            duty("09:00","12:00","shop","make","在铺子里缝补、编织",null),
            duty("16:00","16:30","shop","work","把周野问起的工具找齐","fixer"),
            duty("20:00","20:30","home","rest","陪知夏说说话","artist"),
            duty("12:00","13:00","cafe","rest","去咖啡馆吃午饭","tailor"))),
        Map.entry("barista",seed("07:00","23:30","老宋的面包一到，我就知道今天该忙了。有些话我在阿禾面前没敢说完，回家跟小吉念叨一句。",
            duty("09:00","12:00","cafe","work","接老宋的面包，帮着看柜台","owner"),
            duty("12:00","13:30","cafe","work","煮咖啡，算上午的账","owner"),
            duty("19:30","20:00","home","rest","把白天没敢说的点子讲给小吉听","waiter"))),
        Map.entry("waiter",seed("08:30","00:30","东西迟一会儿送到，我不觉得算耽误谁。苏晚睡前跟我念叨的那些点子，我一般都还记得。",
            duty("09:30","12:00","cafe","work","端茶送水、收桌子","owner"),
            duty("16:00","18:00","cafe","work","收摊前把桌子擦一遍","owner"),
            duty("12:30","13:30","cafe","rest","忙完午市在店里吃饭","barista"))),
        Map.entry("tutor",seed("07:30","00:00","教得顺不顺，我下课都想找老谭说一句。他从不笑我，还会回我几句他当年怎么应付的。",
            duty("08:30","10:00","home","study","在家备课，重看没讲透的地方",null),
            duty("15:00","17:00","academy","work","在学堂给孩子补功课",null),
            duty("17:00","17:30","academy","observe","把今天课上得顺不顺说给老谭听","scholar"),
            duty("12:00","13:00","academy","rest","在学堂吃自带的午饭","scholar"))),
        Map.entry("botanist",seed("05:30","22:30","青叔没说破我自己那块试验田，我也没主动提。晚饭做好，我总先给大雷留一份。",
            duty("06:00","08:00","garden","tend_plants","侍弄自己那块试验田",null),
            duty("08:00","10:00","garden","work","给青叔打下手，翻地浇水","gardener"),
            duty("18:00","18:30","home","cook","用花园带回的菜做晚饭","trainer"),
            duty("12:00","13:00","garden","rest","跟青叔在花园吃午饭","gardener"))),
        Map.entry("trainer",seed("06:00","22:00","石头傍晚总来陪学员对练，我省不少力气。老程会提醒我哪个学员的肩不能太用力。",
            duty("06:30","08:00","gym","exercise","开晨练课",null),
            duty("17:00","19:00","gym","work","带傍晚训练课","boxer"),
            duty("12:00","13:00","garden","rest","去花园吃午饭晒晒太阳","boxer"))),
        Map.entry("boxer",seed("06:30","23:00","大雷傍晚带课，我搭把手对练。器械被占着的时候我直接开口，说完接着练。",
            duty("07:00","09:00","gym","exercise","练拳",null),
            duty("17:00","19:00","gym","exercise","陪学员对练","trainer"),
            duty("12:00","13:00","garden","rest","跟大雷去花园吃午饭","trainer"))),
        Map.entry("yogi",seed("05:00","22:00","天没亮透就开始的那节课最安静，我最喜欢。心里烦的时候我不说，转头才跟信得过的人提一句。",
            duty("05:30","06:30","gym","exercise","教晨间瑜伽",null),
            duty("19:30","20:00","gym","work","关灯前把垫子收好",null),
            duty("12:00","12:45","garden","rest","在花园角落安静吃饭",null))),
        Map.entry("messenger",seed("06:00","23:00","消息传到我这儿就该往下传。我先说给老白听，让他写得像样一点，再往外走。",
            duty("06:30","08:30","street","observe","沿街打听今天的消息",null),
            duty("08:30","09:00","board","read_notice","到公告板把消息说给老白","scribe"),
            duty("12:00","13:00","board","rest","在广场边吃边听消息",null))),
        Map.entry("clerk",seed("07:00","22:30","账对不上我睡不着，所以中午都念给周野听一遍。谁借了什么、还没还，我记得清楚。",
            duty("08:00","09:30","shop","work","开门前把账本核对一遍",null),
            duty("12:00","12:30","shop","work","把上午的进出账念给周野听","fixer"),
            duty("18:00","18:30","shop","work","收摊前对一遍账",null),
            duty("12:30","13:30","cafe","rest","去咖啡馆吃午饭",null))),
        Map.entry("librarian",seed("06:30","23:00","老谭要的那本旧书，我一般都能替他翻出来。被打断的时候，我先把手上这页看完再抬头。",
            duty("07:00","09:00","academy","work","整理书角，办续借还书",null),
            duty("09:00","11:00","academy","read","在书角看书，答学生问题",null),
            duty("16:00","16:30","academy","work","把老谭要找的旧书翻出来","scholar"),
            duty("11:30","12:30","academy","rest","在书角吃午饭","scholar"))),
        Map.entry("baker",seed("04:30","20:30","面包不到火候我不拿出去。天没亮在家里烤第一炉，咖啡馆开门就送去交给苏晚。老陶留的蜜我会用上。",
            duty("05:00","07:30","home","cook","天没亮在家灶上烤第一炉面包",null),
            duty("09:00","09:30","cafe","work","把新鲜面包送到咖啡馆交给苏晚","barista"),
            duty("15:00","16:30","cafe","work","在后厨烤傍晚的面包",null),
            duty("12:00","13:00","home","rest","午后在家歇一会儿",null))),
        Map.entry("beekeeper",seed("06:30","22:30","蜜多的时候，我一半留给春杏摆摊，一半留给老宋做面包。东西是流动的，我不太记是谁的。",
            duty("07:00","09:00","garden","work","看蜂箱，收今天的蜜",null),
            duty("09:30","10:00","garden","work","送一罐蜜给春杏摆摊","florist"),
            duty("18:30","19:00","home","rest","把剩下的蜜留给老宋","baker"),
            duty("12:00","13:00","garden","rest","在蜂箱边吃午饭","florist"))),
        Map.entry("florist",seed("05:30","22:00","老陶的蜜我总顺手帮着卖。谁替谁多留了一会儿，我看在眼里，从不说破。",
            duty("06:00","10:00","garden","tend_plants","侍弄花草，摆开今天的花摊",null),
            duty("10:00","10:30","garden","work","把老陶的蜜摆到花摊边","beekeeper"),
            duty("15:00","16:00","garden","observe","摆弄花草，看看谁来了花园",null),
            duty("12:00","12:45","garden","rest","在花摊边吃午饭","beekeeper"))),
        Map.entry("scribe",seed("07:30","00:00","阿信带来的消息，我誊抄的时候总要顺一顺措辞，不然对不起看的人。字不能潦草。",
            duty("09:00","10:30","board","work","把阿信带来的消息誊成告示","messenger"),
            duty("15:00","16:30","board","work","帮人写信、念告示",null),
            duty("12:00","13:00","board","rest","在广场边吃午饭","messenger"))),
        Map.entry("scholar",seed("08:00","23:00","时安下课总来找我说两句，我也爱讲当年的事给他听。顾雁替我翻旧书，我记在心里。",
            duty("09:00","11:00","academy","read","坐在惯坐的位置讲古",null),
            duty("17:00","17:30","academy","observe","听时安说课，回他几句","tutor"),
            duty("11:30","12:30","academy","rest","在学堂和顾雁一起吃饭","librarian"))),
        Map.entry("tailor",seed("07:00","22:30","布料够不够，我下午总要跟阿满对一句。东西该被好好对待，不是凑合用就行。",
            duty("09:00","12:00","shop","make","缝缝补补，不接急活",null),
            duty("14:00","16:00","shop","make","接着做手上的活",null),
            duty("16:00","16:30","shop","work","跟阿满对一下布料还剩多少","weaver"),
            duty("12:00","13:00","cafe","rest","去咖啡馆吃午饭","weaver"))),
        Map.entry("masseur",seed("06:00","22:00","大雷傍晚开课前，我总要提醒他一句谁的肩不能使劲。自己的旧伤我不提，提了显得讨照顾。",
            duty("09:00","12:00","gym","work","帮人推拿、正骨",null),
            duty("15:00","17:00","gym","work","接着帮人推拿",null),
            duty("17:00","17:30","gym","observe","提醒大雷哪个学员的肩不能使劲","trainer"),
            duty("12:00","13:00","garden","rest","去花园吃午饭","trainer"))),
        Map.entry("broker",seed("06:30","23:30","秋姨那儿的消息我下午总要问一句。谁欠我一个人情、谁的活儿是我牵的线，我记得清楚。",
            duty("08:00","11:00","board","work","在公告板帮人对活儿、说合人手",null),
            duty("15:00","16:00","board","observe","跟秋姨换个消息","trader"),
            duty("12:00","13:00","board","rest","在广场边吃午饭","trader"))),
        Map.entry("trader",seed("06:00","22:00","换东西必须对等，我从不含糊。定叔下午常来问消息，我们顺手对一下账，谁也不欠谁。",
            duty("07:00","10:00","street","work","推车沿街换东西",null),
            duty("10:00","12:00","board","work","回广场对账、找补",null),
            duty("15:00","16:00","board","observe","跟定叔换个消息","broker"),
            duty("12:00","13:00","board","rest","在广场边吃午饭","broker")))
    );

    /** Plants the seed once. A save from before duties existed gets them on its next tick. */
    static void plant(CompanionWorld w,ResidentState r,Instant at){
        if(r.dutiesVersion>=VERSION||"self".equals(r.id))return;
        Seed seed=SEEDS.get(r.id);
        if(seed==null)return;
        boolean firstTime=!r.dutiesSeeded;
        r.dutiesSeeded=true;r.dutiesVersion=VERSION;
        // Someone who has since chosen another life keeps it: an emptied routine is their answer.
        if(!firstTime&&r.duties.isEmpty())return;
        r.duties=new ArrayList<>();
        for(Duty d:seed.duties().stream().sorted(java.util.Comparator.comparingInt(x->x.startMinute)).toList()){Duty copy=new Duty();copy.startMinute=d.startMinute;copy.endMinute=d.endMinute;copy.place=place(r.id,d.place);copy.action=d.action;copy.what=d.what;copy.toId=d.toId;r.duties.add(copy);}
        r.usualWakeMinute=seed.wakeMinute();r.usualSleepMinute=seed.sleepMinute();r.sleepScheduleSeeded=true;
        if(firstTime)ResidentSimulation.memory(w,r.id,"history","seed",at.minusSeconds(86400),"work",seed.memory(),List.of(),7);
    }

    /** The stretch of today's own plan this resident is inside right now, if it is still open. */
    public static DaySegment currentSegment(CompanionWorld w,ResidentState r,Instant at){
        if(!hasTimedPlanFor(w,r,at))return null;
        int now=localMinute(w,at);
        return r.dayPlan.segments.stream()
            .filter(s->s.startMinute!=null&&s.endMinute!=null&&s.startMinute<=now&&now<s.endMinute&&"pending".equals(s.status))
            .findFirst().orElse(null);
    }
    /** The next planned stretch starting within the given minutes. */
    public static DaySegment nextSegment(CompanionWorld w,ResidentState r,Instant at,int withinMinutes){
        if(!hasTimedPlanFor(w,r,at))return null;
        int now=localMinute(w,at);
        return r.dayPlan.segments.stream()
            .filter(s->s.startMinute!=null&&s.startMinute>now&&s.startMinute-now<=withinMinutes&&"pending".equals(s.status))
            .findFirst().orElse(null);
    }
    /** Being where you said you would be, during that stretch, is what makes it done. Nothing else
     * counts and nothing is inferred - the missed ones become tomorrow's "没顾上" reflection. */
    static void markProgress(CompanionWorld w,ResidentState r,Instant at){
        DaySegment current=currentSegment(w,r,at);
        if(current==null||current.place==null)return;
        var actor=ResidentSimulation.actor(w,r.id);
        if(current.place.equals(actor.place())&&!Set.of("walk","sleep","away").contains(actor.activity()))current.status="done";
    }

    private static boolean hasTimedPlanFor(CompanionWorld w,ResidentState r,Instant at){
        return r!=null&&r.dayPlan!=null&&at!=null&&at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString().equals(r.dayPlan.day);
    }
    /** Walking minutes between two places, rounded up, for telling someone how soon to set out. */
    public static int walkMinutes(String from,String to){return (int)Math.ceil(ResidentSimulation.travelSeconds(from,to)/60.0);}
    /** "刚搬来" is how the newcomers are born, and it was meant for their first hours only. Read back
     * the next morning it became "still jet-lagged": two residents went to sleep at 11:17. */
    static void settleIn(CompanionWorld w,ResidentState r,Instant at){
        if(r.mood!=null&&r.mood.startsWith("刚搬来")&&w.joinedAt!=null&&java.time.Duration.between(w.joinedAt,at).toHours()>=6)r.mood="如常";
    }
    public static int localMinuteOf(CompanionWorld w,Instant at){return localMinute(w,at);}
    static int localMinute(CompanionWorld w,Instant at){ZonedDateTime local=at.atZone(ZoneId.of(w.timezone));return local.getHour()*60+local.getMinute();}
    /** "home" in a seed or a plan always means the resident's own home. */
    static String place(String residentId,String place){return "home".equals(place)?TownPlaces.homeOf(residentId):place;}
    public static String clock(int minute){return String.format("%02d:%02d",Math.floorDiv(minute,60)%24,Math.floorMod(minute,60));}
    /** "HH:mm" to minutes of day, or null when it is not a time. */
    public static Integer parseClock(String text){
        if(text==null||!text.matches("\\d{1,2}:\\d{2}"))return null;
        String[] parts=text.split(":");int h=Integer.parseInt(parts[0]),m=Integer.parseInt(parts[1]);
        return h>24||m>59||(h==24&&m>0)?null:h*60+m;
    }
    private static int minute(String text){Integer value=parseClock(text);if(value==null)throw new IllegalArgumentException(text);return value;}
}
