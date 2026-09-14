package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;
import static com.betterself.growth.town.companion.domain.ResidentSimulation.*;

/**
 * Who lives here, and what they start out with.
 *
 * <p>Split out of {@link ResidentSimulation} so that adding a neighbour is a change to one file
 * about people, not a change inside the loop that moves everyone's day forward. Everything here runs
 * once per world (or once per hand-authored arrival) and then never again: the six starting
 * residents, their homes, their opening plans, and the small shared history that gives them
 * something to already know about each other - plus {@link #passersBy}, the one thing here that
 * keeps running on every advance rather than once, because a delivery or a dog walk is not a life
 * event with a beginning a resident can already know about.
 *
 * <p>{@link #addResident} stays the only way a new resident appears. Nothing in the simulation calls
 * it - there is no recruitment, no applicants, no "the town needed someone so one showed up". A
 * neighbour exists because we wrote them, which is what docs/04-decisions.md means by 新增居民由我们
 * 手动设计. 周野 and 阿满 (see {@link #initialize}) are two more instances of exactly that: hand-placed
 * at genesis rather than recruited, one with his own new home, one sharing 知夏's.
 */
public final class ResidentSeed {
    private ResidentSeed() {}

    static final List<String> IDS=List.of("owner","student","artist","gardener");
    /** Old worlds used a public-project id as the entire life of a resident.  Keep that id as a
     * possible thread, but seed a resident-owned purpose so private work, rest and a change of life
     * have somewhere durable to live. */
    static void reconcileLife(CompanionWorld w,Instant at){
        reconcileSecondVersion(w,at);
        if(w.cafeOperatorId==null||state(w,w.cafeOperatorId)==null)w.cafeOperatorId="owner";
        for(ResidentState r:w.residentStates){
            if(r.id.equals("self"))continue;
            if(r.occupation==null||r.occupation.isBlank())r.occupation=defaultOccupation(r.id);
            if(r.lifeIntent==null)r.lifeIntent=lifeIntent(w,r,r.goal,r.occupation,"active",at);
            if(r.careerIntent==null)r.careerIntent=lifeIntent(w,r,null,r.occupation+"。我知道总得找到能长期做下去的事，但也允许自己先歇一歇、慢慢想。","active",at);
            if(r.lifeIntent.status==null)r.lifeIntent.status="active";
            if(!r.sleepScheduleSeeded){r.usualSleepMinute=23*60;r.usualWakeMinute=7*60;r.sleepScheduleSeeded=true;}
            ResidentDuties.plant(w,r,at);ResidentDuties.settleIn(w,r,at);
        }
        passersBy(w,at);
    }
    static String defaultOccupation(String id){return switch(id){case "owner"->"经营咖啡馆，也想留下自己的时间";case "student"->"备考，也在摸索以后想过怎样的日子";case "artist"->"画画、接零散的创作活";case "gardener"->"照看花草和邻里的小事";case "fixer"->FIXER_OCCUPATION;case "weaver"->WEAVER_OCCUPATION;default->"找一件能长期做下去的事";};}
    private static final String FIXER_OCCUPATION="以前在别处开过一家修理铺，铺子没了，先搬来住着看看：修自行车、家具、灯具都接";
    private static final String WEAVER_OCCUPATION="做手工——缝补、编织，接一点零散的活，和知夏合住";

    /**
     * The three-layer personality text (本我/超我/自我) plus two narrative notes docs/04-decisions.md's
     * draft asks every resident to carry: which detail of a shared moment this resident tends to keep
     * ({@code memoryBias}) and under what repeated real experience their 超我 command is expected to
     * loosen ({@code looseningNote}, deliberately never pre-scripted further than the condition
     * itself - see 04's "允许往坏了长"). Text, never scores: {@link Personality}'s four numbers
     * already cover the numeric axis this record does not touch, and this is never a gate or a
     * threshold - its only job is to shape what a resident writes into memory and what they avoid
     * saying, the same way the docs describe it.
     *
     * <p>There is deliberately no field on {@link ResidentState} to hold this - unlike {@code
     * occupation} it never changes per world and needs no self-healing, so ResidentDirector simply
     * calls {@link #narrative} fresh every {@code perspective()} and copies the text into its own
     * {@code ResidentMind.Context.PersonaView}, leaving CompanionWorld.java (another agent's file this
     * wave) untouched.
     */
    public record PersonalityNarrative(String wantSelf,String oughtSelf,String actingSelf,String memoryBias,String looseningNote) {}

    private static final Map<String,PersonalityNarrative> NARRATIVES = Map.of(
        "owner", new PersonalityNarrative(
            "想被需要。开店不只是生计，是“有人推门进来找我”这件事本身。",
            "我不能让人等。还有一条更紧的——我不该让人看出我在意。",
            "用忙碌代替表达：心里不舒服时去擦桌子、理杯子，而不是说出来。话不少，但说的都是别人的事。",
            "记谁等了多久、谁没喝完；不记自己当时正在做什么。",
            "一次次为柜台放下自己的事之后，「我不能让人等」可能翻成「他们其实没那么需要我」——是失望，不是偷懒。"),
        "student", new PersonalityNarrative(
            "想要一个不会突然变形的世界。备考是抓手，不是目的。",
            "我必须自己核对过才能说，所以从不喊“我们不过是数据”，只报具体的时刻。",
            "低调内敛：话短，先给证据后给结论，被打断就闭嘴。憋不住时是“你昨天下午三点到四点在哪”这种突然的具体提问。",
            "记时间、记谁在场、记原话；几乎不记情绪。",
            "如果一次次说出来没人信，「必须核对过才能说」可能变成「说了也没用」——从此只记不说，那才是真正的孤立。"),
        "artist", new PersonalityNarrative(
            "想被看见。不是被夸，是被看懂。",
            "我不能让人失望，所以立刻答应——答应本身就是安抚对方的动作。",
            "能言善辩：擅长把“没做完”讲成“还在长”。真做完时反而突然怕拿出来。",
            "记语气、记对方脸上的表情、记自己被打断的那一下；事实细节反而模糊。",
            "某次没答应、而对方并没有崩溃，律令会松一点；反过来答应了又搞砸并被当面点破，可能翻成「那我干脆什么都不答应」。"),
        "gardener", new PersonalityNarrative(
            "想要一件不会跟自己讨价还价的事。植物不会。",
            "麻烦别人是不体面的，所以什么都自己扛。",
            "话少，动手多：用东西代替话——递一株苗、顺手修个东西。别人问他好不好，答“还行”，然后转头去干活。",
            "记东西和状态（谁的花盆空着、哪块砖松了）；几乎不记谁说了什么。",
            "真的扛不动一次，而有人接住了——「麻烦别人不体面」第一次动摇。"),
        "fixer", new PersonalityNarrative(
            "想要事情“对得上”。看到不对的就难受。",
            "几乎不压制自己，这既是他的特点，也是他会伤到人的地方；真要说有一条——拐弯抹角是不尊重人。",
            "直接问、直接说。说完就过去了，不记仇，也不觉得刚才说重了。",
            "记事实对不对得上（谁说过什么、后来发生了什么）；不记别人的反应——所以他会反复伤人而不自知。",
            "某次直言之后有人明显躲了他很多天，「拐弯抹角是不尊重人」第一次动摇。他会直接问小川“你上次说的那个是什么意思”，会当面对知夏说“你答应的那张画呢”——这四个人压着的东西，需要有人说破。"),
        "weaver", new PersonalityNarrative(
            "想要所有人都好好的。她的舒适来自“场面没有裂缝”。",
            "我不能是那个把气氛弄僵的人。",
            "也是能言善辩那一类，但用在调和上——抢在冲突之前说话，替别人找台阶，把别人的话翻译得柔和一点。",
            "记谁和谁之间的温度变化、记自己有没有把场面圆过去；不记事情本身的对错。",
            "一次次替别人圆场，却发现没人注意到她自己怎么样，可能来一次突然的、不像她的爆发——这条最有戏剧性，必须来自累积，不能预写。周野说破，阿满圆场：这一对是这条街新的日常张力来源。")
    );

    /** Public so {@code application.ResidentDirector} can read it straight into {@code
     * ResidentMind.Context} without CompanionWorld.java growing any persona fields - see this class's
     * {@link PersonalityNarrative} javadoc. Returns {@code null} for an id this batch did not author
     * text for (the avatar "self", or any future manually-added resident) rather than guessing. */
    public static PersonalityNarrative narrative(String residentId){
        PersonalityNarrative six=NARRATIVES.get(residentId);
        if(six!=null)return six;
        // The nineteen live in their own file (frozen source, same record type) - resolved here so
        // ResidentDirector.personaView keeps asking one question and never has to know which batch a
        // resident came from.
        var newcomer=ResidentPersonas.of(residentId);
        return newcomer==null?null:newcomer.narrative();
    }
    public static void initialize(CompanionWorld w,Instant now) {
        if(w.simulationVersion>=2)return;
        long initialRevision=w.revision;
        w.simulationVersion=5; w.residentStates.clear();w.projects.clear();w.objects.clear();w.conversations.clear();
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
        w.objects.add(new WorldObject("flowerbed","flowers","garden","等待移栽的新芽","growing",null));
        // Two hand-authored newcomers (docs/04-decisions.md: 新增居民由我们手动设计, never the
        // simulation itself). 周野 gets a new home of his own, next to 青叔's garden; 阿满 shares 知夏's
        // home as a flat-mate - her own bed and her own desk inside home-artist, not a new building
        // (TownPlaces.addFlatmate keeps those genuinely separate, unlike the old four-in-one-bed bug).
        TownPlaces.addHome(w,"fixer");
        newcomer(w,"fixer","周野","修东西的人",FIXER_OCCUPATION,past);
        TownPlaces.addFlatmate(w,"weaver","artist");
        ResidentState weaver=newcomer(w,"weaver","阿满","做手工的人",WEAVER_OCCUPATION,past);
        // Judgement call: they already agreed to move in together before this world's story opens,
        // so their mutual regard starts a little warmer than the flat "just met" 40 a newcomer gets
        // with everyone else - the brief did not specify a number, only that they are flat-mates.
        weaver.relationships.put("artist",55);state(w,"artist").relationships.put("weaver",55);
        // The shop is docs/01-requirements.md 第二版「世界」's own pick for "所有权/借/赠最自然的来源
        // 地", and these two owners are not arbitrary: 周野 used to run a repair shop (FIXER_OCCUPATION)
        // and 阿满 does handicraft (WEAVER_OCCUPATION) - each owns exactly the kind of thing their own
        // occupation would actually keep at hand. Decorative, free-text objects (docs/01's own split -
        // "物件按会不会被争分两类"), lend/gift-eligible through Lending because they now carry an owner.
        w.objects.add(new WorldObject("shop-toolkit","tool","shop","一套用了很久的工具箱","螺丝刀缺了一把",null,"fixer"));
        w.objects.add(new WorldObject("shop-thread-box","tool","shop","一个装零碎线头和布片的木盒","盖子有点合不严",null,"weaver"));
        populateTheTwentyFive(w,past);
        // Six people on one street, and every one of these is a thing somebody wants OTHER people
        // for - they are pinned on the board by the front door, not kept in a drawer.
        // Without this, "knows" (which reads a non-reflection memory carrying the topic) was true
        // only of your own, so nobody could ever put a hand on anybody else's thing - not through a
        // model decision, which validates against knownProjects, and not through a habit either. A
        // measured rule-only run showed exactly what that produces: one resident adding to his own
        // thing five times over two days, stopped at the solo cap for want of a second pair of hands
        // that could not possibly have arrived, because nobody else in town had ever heard of it.
        // Recorded as knowledge rather than as a remembered event each: knowing what the board says
        // is not the same as remembering an afternoon, and handing everyone four extra memories at
        // startup pushed the whole town past its own reflection-frequency guard for no good reason.
        // What they get is exactly what the board says and nothing more - the thing exists, whose it
        // is, where it happens, and that nobody has started it yet, which is true of all of them at
        // this moment. How any of them is getting on later is something they will have to see or be
        // told, through the ordinary paths.
        for(ResidentState r:w.residentStates){
            if("self".equals(r.id))continue;
            for(Project p:w.projects)
                if(!r.id.equals(p.ownerId)&&!r.knownProjects.containsKey(p.id))
                    r.knownProjects.put(p.id,new ProjectKnowledge(p.id,p.place,p.status,p.progress,past,"noticeboard"));
        }
        // The one memory nobody else has (docs/01-requirements.md's seeded anomaly for 小川): a
        // hand-placed FICTIONAL seed, not a real observed system event. It exists to be told,
        // believed, doubted or garbled by whoever he tells it to later - not as evidence that the
        // observability story described there is actually wired up yet.
        Instant anomalyAt=now.atZone(ZoneId.of(w.timezone)).toLocalDate().minusDays(1).atTime(15,30).atZone(ZoneId.of(w.timezone)).toInstant();
        memory(w,"student","student","observed",anomalyAt,"anomaly","昨天下午三点到四点之间，所有人都没有动过——一个都没有，连阿禾擦桌子的手都停在半空。我盯着表看了整整一个小时，谁也没告诉。",List.of(),9);
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
        TownPlaces.seed(w);
        w.revision=initialRevision;
    }

    /** Additive migration for worlds created before the 25-resident map. It never clears lists,
     * replaces evolved relationships, or rewinds plans; only missing authored residents, homes,
     * furniture and starter objects are added. This is deliberately domain migration rather than a
     * SQL rewrite because the whole save is one JSON document and TownPlaces already uses the same
     * self-healing pattern. */
    private static void reconcileSecondVersion(CompanionWorld w,Instant at){
        if(w.simulationVersion>=5)return;
        TownPlaces.seed(w);
        if(state(w,"fixer")==null){TownPlaces.addHome(w,"fixer");newcomer(w,"fixer","周野","修东西的人",FIXER_OCCUPATION,at);}
        if(state(w,"weaver")==null){TownPlaces.addFlatmate(w,"weaver","artist");newcomer(w,"weaver","阿满","做手工的人",WEAVER_OCCUPATION,at);}
        populateTheTwentyFive(w,at);
        ensureOwnedObject(w,new WorldObject("shop-toolkit","tool","shop","shop-workroom","一套用了很久的工具箱","螺丝刀缺了一把",null,"fixer"));
        ensureOwnedObject(w,new WorldObject("shop-thread-box","tool","shop","shop-workroom","一个装零碎线头和布片的木盒","盖子有点合不严",null,"weaver"));
        TownPlaces.seed(w);
        w.simulationVersion=Math.max(w.simulationVersion,5);
    }
    private static void ensureOwnedObject(CompanionWorld w,WorldObject object){
        if(w.objects.stream().noneMatch(o->o.id().equals(object.id())))w.objects.add(object);
    }
    public static boolean addResident(CompanionWorld w,String id,String name,String role,String occupation,Instant now){
        if(id==null||!id.matches("[a-z][a-z0-9-]{1,30}")||name==null||name.isBlank()||role==null||role.isBlank()||occupation==null||occupation.isBlank()||state(w,id)!=null||w.residents.stream().anyMatch(a->a.id().equals(id)))return false;
        TownPlaces.seed(w);TownPlaces.addHome(w,id);
        newcomer(w,id,name,role,occupation,now);
        return true;
    }
    /** Shared body for a brand-new arrival: default state, a default relationship with everyone
     * already in the world, an Actor entry, a first memory of just having moved in, and an initial
     * "settling in at home" plan. Furniture is deliberately the caller's job, not this method's -
     * {@link TownPlaces#addHome} for someone getting a place of their own, {@link
     * TownPlaces#addFlatmate} for someone moving in with a resident already here - because those two
     * are not interchangeable (see TownPlaces) and this method has no way to guess which one is
     * right for a given id. */
    /** The nineteen of docs/01 第二版「人」, brought into a world that already holds the original six.
     * Their text, occupations, households and starting relationships are all frozen source in {@link
     * ResidentPersonas} - docs/04: 「运行时生成的 persona 是一个我们没控住的变量」 - and this method only
     * does the wiring.
     *
     * <p>Two things here are the whole point rather than plumbing. First, <b>homes come from
     * households</b>: the host of each gets {@code addHome} and everybody else in it {@code
     * addFlatmate}, so young residents genuinely share one flat (docs/01: 合住 is a free source of the
     * repeated interaction norms need - 「你每天都得和同一个人分一个厨房」) rather than each getting a
     * private box. Second, <b>nobody is acquainted by default</b>: {@code newcomer(..., false)} writes
     * no relationships at all, and the only edges that exist afterwards are the ones
     * {@link ResidentPersonas#initialRelationships()} authors from a shared household or a shared
     * building. Everyone else is a stranger by omission. That is the difference between the measured
     * 0.20 starting density and the 1.0 that mutual-40-with-everyone produces, and docs/04 names the
     * latter as the reason asymmetry and diffusion would both have had nothing to measure. */
    private static void populateTheTwentyFive(CompanionWorld w,Instant past){
        ResidentPersonas.households().forEach((host,members)->{
            TownPlaces.addHome(w,host);
            for(String member:members)if(!member.equals(host))TownPlaces.addFlatmate(w,member,host);
        });
        for(ResidentPersonas.NewResident person:ResidentPersonas.all())
            if(state(w,person.id())==null)newcomer(w,person.id(),person.name(),person.role(),person.occupation(),past,false);
        ResidentPersonas.initialRelationships().forEach((from,edges)->{
            ResidentState r=state(w,from);
            if(r!=null)edges.forEach(r.relationships::putIfAbsent);
        });
    }
    private static ResidentState newcomer(CompanionWorld w,String id,String name,String role,String occupation,Instant now){
        return newcomer(w,id,name,role,occupation,now,true);
    }
    /** {@code acquaintEveryone=false} is what the nineteen of docs/01 第二版「人」 use. The blanket
     * mutual 40 this helper hands out is fine for a town of six, and is precisely what docs/04 rules
     * out at twenty-five: 「现在每对居民都以双向 40 开局，扩到 25 人就是 300 对全部相识——关系不对称率
     * 会被稀释向 0，扩散也没有路径可言」. The nineteen instead get exactly the clustered graph
     * {@link ResidentPersonas#initialRelationships()} authors, applied once after everybody exists. */
    private static ResidentState newcomer(CompanionWorld w,String id,String name,String role,String occupation,Instant now,boolean acquaintEveryone){
        ResidentState r=new ResidentState();r.id=id;r.energy=65;r.social=55;r.curiosity=55;r.mood="刚搬来，还在认路";r.occupation=occupation;r.thought="先熟悉这里，也想找一件能长期做下去的事";r.lastSocialAt=now;r.lastReflectionAt=now;r.energyUpdatedAt=now;r.usualSleepMinute=23*60;r.usualWakeMinute=7*60;r.sleepScheduleSeeded=true;r.revision=1;r.lifeIntent=lifeIntent(w,r,null,occupation,"active",now);r.careerIntent=lifeIntent(w,r,null,occupation,"active",now);
        if(acquaintEveryone)for(ResidentState other:w.residentStates)if(!other.id.equals("self")){r.relationships.put(other.id,40);other.relationships.put(id,40);}
        w.residentStates.add(r);w.residents.add(new Actor(id,name,role,TownPlaces.homeOf(id),"rest","刚搬来，先在住处整理东西",180,260,now.plusSeconds(60)));
        memory(w,id,id,"seed",now,null,"我刚搬到这条街，想慢慢把“"+occupation+"”过成能维持生活的事，也先允许自己休息和认识邻居。",List.of(),7);
        schedule(w,r,"rest",TownPlaces.homeOf(id),null,"刚搬来，先在住处整理东西",now,1200);
        return r;
    }
    // The avatar is not in w.residents (that list stays exactly the six NPCs; see the class javadoc
    // for the two hand-authored arrivals joining the original four); "self" resolves to w.avatar
    // instead so any resident-facing lookup still finds it.

    /**
     * Background flavour, never a character: a delivery arriving in the morning, someone collecting
     * recycling, a neighbour walking a dog through the street. Purely a rules check on the clock -
     * no {@link ResidentState} of their own, no {@link ResidentSimulation.PortableAction}, no call
     * into {@code ResidentMind} - which is what keeps them genuinely free (see the task's "no model
     * calls, ever"). Called once per {@link #reconcileLife}, i.e. once per real {@code advance}, not
     * once per 6-second simulation tick, so a busy poll rate cannot spam it.
     *
     * <p>Each kind fires at most once per local calendar day, gated by whether today's occurrence
     * already exists in {@link CompanionWorld#events} (a duplicate is possible only if that capped
     * list has since evicted it - harmless for background flavour). Whoever actually happens to be on
     * the street at that hour gets a memory of it in their own words; an empty street means nobody
     * remembers it happened, which is the point of NPC 限知 - a passer-by is not broadcast into
     * anyone's head, only into whoever was there to see it.
     */
    private static void passersBy(CompanionWorld w,Instant at){
        ZonedDateTime local=at.atZone(ZoneId.of(w.timezone));
        passerby(w,at,local,8,"passerby_delivery","外卖员从街头快步走过，在咖啡馆门口放下一个包裹，转身又走了。");
        passerby(w,at,local,11,"passerby_recycling","收废品的人推着车慢慢走过，问了一声有没有旧报纸，没人应声，接着往前走了。");
        passerby(w,at,local,18,"passerby_dogwalk","一个不认识的邻居牵着狗从街这头走到那头，狗停下来嗅了嗅街边，又被牵着走远了。");
    }
    private static void passerby(CompanionWorld w,Instant at,ZonedDateTime local,int hour,String type,String text){
        if(local.getHour()!=hour)return;
        LocalDate today=local.toLocalDate();
        if(w.events.stream().anyMatch(e->type.equals(e.type())&&e.at().atZone(local.getZone()).toLocalDate().equals(today)))return;
        event(w,at,type,"street",List.of(),text,null);
        for(ResidentState r:w.residentStates){
            if(r.id.equals("self"))continue;
            if("street".equals(actor(w,r.id).place()))memory(w,r.id,"passerby","observed",at,"street-life",text,List.of(),3);
        }
    }
}
