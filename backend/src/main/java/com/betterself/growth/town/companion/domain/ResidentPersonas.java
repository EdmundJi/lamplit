package com.betterself.growth.town.companion.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The nineteen new residents docs/01-requirements.md's 第二版「人」 asks for, alongside the six that
 * stay exactly as they are in {@link ResidentSeed} - every finding in docs hangs off those six names,
 * so re-rolling them would throw away the comparison points. This file is pure static data: no
 * mutation of {@link CompanionWorld}, no call into {@link ResidentSimulation}, and it is never invoked
 * from {@link ResidentSeed#initialize}. Wiring these nineteen into an actual running world (their
 * {@code TownPlaces} homes, their {@code Actor}/{@code ResidentState} entries) is a separate, later
 * step - see this class's own javadoc sections below for exactly what each accessor hands the wiring
 * step, and the class does none of that wiring itself, on purpose: docs/01's 节奏 is 地基先行，人口暂时
 * 不动, and another batch is reshaping memory on the existing six concurrently.
 *
 * <h2>The trap this batch was told to watch for</h2>
 * docs/01: 「同一个模型一次性写出 25 个人，很可能把一整套一致的社会预期埋进去」. Nineteen personas from
 * one author is exactly that risk, so this file deliberately does NOT give everyone the same shape of
 * flaw. Some genuinely do not return what they borrow ({@link #of "beekeeper"}), and do not feel bad
 * about it. Some are unreliable about time and shrug it off ({@link #of "waiter"}). Some take the good
 * seat and quietly rationalize it as necessary rather than admit it is comfort ({@link #of "scholar"}).
 * Some are wrong about themselves in a way their own {@code memoryBias} guarantees they cannot see
 * ({@link #of "tutor"}'s self-image runs worse than his actual teaching; {@link #of "broker"} believes
 * his own line about not working an angle). The self-audit of what still leaked into more than half of
 * them anyway - because no single author fully escapes this - is written up in the batch's report, not
 * in this file's javadoc: this class should not grade its own homework.
 *
 * <h2>Housing and occupation, per docs/01 第二版「人」/「世界」</h2>
 * Residents are split by life stage: {@link LifeStage#YOUNG} residents share a flat with two or three
 * others (a free, daily source of repeated interaction - "你每天都得和同一个人分一个厨房"); {@link
 * LifeStage#OLDER} residents live alone or, in exactly two cases, paired up with one other older
 * resident. {@link #households()} gives the grouping - keyed by a nominal "host" id, exactly the shape
 * {@code TownPlaces.addHome}/{@code TownPlaces.addFlatmate} already expect (host gets {@code addHome},
 * everyone else in the list gets {@code addFlatmate(w, id, hostId)}), so wiring this in later is
 * mechanical rather than a redesign.
 *
 * <p>Occupations are spread across the six public buildings docs/01 第二版「世界」names (cafe, garden,
 * academy, gym, board, shop): each of the six ends up with exactly four people once the six existing
 * residents already anchored at one (owner/cafe, gardener/garden, student/academy, fixer+weaver/shop)
 * are counted alongside these nineteen - see {@link #occupationClusters()}. 知夏 (artist) is the one
 * existing resident whose occupation was never tied to a building and stays that way; this batch does
 * not retrofit her.
 *
 * <h2>Initial relationships, per docs/01 「初始关系按住所／职业聚类，其余互不相识」</h2>
 * {@link #initialRelationships()} is the actual authored graph: every edge in it comes from a shared
 * household or a shared occupation cluster, and nothing else - two residents who share neither a home
 * nor a building are strangers by omission, not by an explicit zero. The report accompanying this batch
 * says exactly what density that produces. A pair with an edge is not always the same number in both
 * directions: a handful are hand-authored asymmetric on purpose (a housemate who never quite regards
 * the person who doesn't return his tools as warmly as the reverse; a broker whose surface friendliness
 * outpaces his private regard) so the "asymmetry rate" metric has something real to measure, rather
 * than diluting to zero the way 300 identical mutual-40s would (the exact failure docs/01 names).
 */
public final class ResidentPersonas {
    private ResidentPersonas() {}

    /** Which of two housing shapes docs/01 第二版「人」 assigns this resident: {@link #YOUNG} residents
     * share a flat with two or three others; {@link #OLDER} residents live alone or, in exactly two
     * households here, paired with one other older resident. Never a numeric field, never read by
     * ResidentSimulation - it exists only so {@link #households()} has a non-arbitrary reason behind
     * who lives with whom. */
    public enum LifeStage { YOUNG, OLDER }

    /** One new resident, fully authored: id (same slug shape {@code ResidentSeed.addResident} already
     * validates), a Chinese name, a short role tag (the same kind of short phrase {@code
     * ResidentSeed.newcomer}'s {@code role} parameter takes), a one-sentence occupation description
     * (the same voice as {@code ResidentSeed.defaultOccupation}), which of the six public buildings
     * that occupation centers on, a life stage, the id of the household this resident belongs to (see
     * {@link #households()} - a resident living alone is their own household id), and the three-layer
     * personality text in {@link ResidentSeed.PersonalityNarrative}'s exact shape. */
    public record NewResident(String id, String name, String role, String occupation, String building,
                               LifeStage lifeStage, String householdId, ResidentSeed.PersonalityNarrative narrative) {}

    private static NewResident r(String id, String name, String role, String occupation, String building,
                                  LifeStage stage, String householdId, ResidentSeed.PersonalityNarrative narrative) {
        return new NewResident(id, name, role, occupation, building, stage, householdId, narrative);
    }

    private static final List<NewResident> ALL = List.of(
        // ---- Household "barista": young flat sharing barista/waiter/tutor - a cafe pair plus a
        // tutor who lives with them for no reason but that a room was free, so not every housemate
        // shares an occupation cluster with the others. ----
        r("barista", "苏晚", "咖啡馆帮工", "在阿禾的咖啡馆帮工，煮咖啡、算账，也悄悄想着有一天自己也开一间。", "cafe", LifeStage.YOUNG, "barista",
            new ResidentSeed.PersonalityNarrative(
                "想让人看出这杯咖啡里有她自己的判断，不只是照阿禾教的做。",
                "我不该抢阿禾的风头——这是他的店，不是我的。",
                "想法先小声提一句，没人接话就自己收回去，转头跟室友吐槽刚才没敢说完的那句。",
                "记一个点子最先是谁说的、后来算在了谁头上；当时的气氛和结果反而记不清。",
                "如果有一次她收回的点子后来被人当面认出来是她先说的，「不该抢风头」这条可能第一次松动。")),
        r("waiter", "小吉", "咖啡馆跑堂", "在咖啡馆端茶送水、收拾桌子，谁催他也不太当回事。", "cafe", LifeStage.YOUNG, "barista",
            new ResidentSeed.PersonalityNarrative(
                "想要日子松快一点，不想被谁的钟表追着走。",
                "东西最后送到就算数，迟一会儿不算耽误谁。",
                "迟到会说一句“来了来了”，语气很轻，转头就忘了刚才让人等了多久。",
                "记自己迟到的理由，说得出口的那种；对方等了多久、脸色如何，几乎不记。",
                "如果真有一次拖延让人吃了实实在在的亏，不只是等——「送到就算数」这条早就松掉的底线，可能第一次绷紧一点点，也可能只是他又找到了新的理由。")),
        r("tutor", "时安", "学院代课", "在学院给孩子补功课，自己也没考过什么正经文凭。", "academy", LifeStage.YOUNG, "barista",
            new ResidentSeed.PersonalityNarrative(
                "想证明自己教得比别人以为的好——连他自己都不太确定这算不算真本事。",
                "在学生和家长面前不能说“不会”，那样显得不专业。",
                "被问倒时会换个说法把问题绕过去，回家才翻书查，第二天装作原本就知道。",
                "记自己被问倒的那些瞬间，几乎不记讲得顺的部分——所以他总觉得自己教得比实际差。",
                "如果被当面拆穿说错了一次，却发现没人真的因此看轻他，「不能说不会」这条可能第一次松动。")),

        // ---- Household "botanist": young flat sharing botanist/trainer/boxer/yogi - three of the
        // four also share the gym as a workplace, which is why several of their relationship edges
        // below carry a double reason instead of one. ----
        r("botanist", "阿橘", "花园帮手", "在花园给青叔打下手，自己也偷偷种了一小片没告诉他的试验田。", "garden", LifeStage.YOUNG, "botanist",
            new ResidentSeed.PersonalityNarrative(
                "想有一小块自己说了算的地，不必事事听青叔的经验。",
                "长辈的话不能当面反驳，哪怕书上写的不一样。",
                "嘴上应着“好嘞”，转头按自己的办法悄悄试，做错了也不声张。",
                "记后来到底谁的办法活了下来；争论本身、谁说了什么，反而记不住。",
                "如果她的办法几次都真的做对了却从没被承认过，「不能当面反驳」可能松成直接把结果摆出来，不再绕弯子。")),
        r("trainer", "大雷", "健身房教练", "在健身房带课，力气是真的，招牌笑容也是真的。", "gym", LifeStage.YOUNG, "botanist",
            new ResidentSeed.PersonalityNarrative(
                "想被当成“厉害的人”，不只是“卖力气的人”。",
                "教练不能在学员面前示弱，自己做不到的事不能说。",
                "被问起某个动作做不到时说“今天不在状态”，转身私下加练，说起学员的进步总先归到自己教得好。",
                "记自己哪次差点被看穿；学员是怎么一步步练上来的这段过程，几乎不记。",
                "如果有一次被当面拆穿“你自己也做不到”却没引来嘲笑，「不能示弱」这条可能第一次松开一点。")),
        r("boxer", "石头", "健身房陪练", "在健身房练拳、陪人对练，说话不绕弯子。", "gym", LifeStage.YOUNG, "botanist",
            new ResidentSeed.PersonalityNarrative(
                "想赢，哪怕只是和自己比。",
                "我不欠谁的耐心——先来后到不该我让。",
                "器械被占着就直接开口要求换手，说完转身接着练，不太看对方脸色。",
                "记谁占了多久、拖沓了几次；对方为什么占着、是不是有急事，几乎不记。",
                "如果有一次他要换手的人恰好是他真在意的人，「先来后到不该我让」这条可能第一次没坚持住。")),
        r("yogi", "阿棠", "健身房教瑜伽", "在健身房教一小时瑜伽课，剩下的时间大多留给自己。", "gym", LifeStage.YOUNG, "botanist",
            new ResidentSeed.PersonalityNarrative(
                "想要真正的安静，不是装出来的那种。",
                "我不能表现出不耐烦，那样显得没修养。",
                "心里已经很烦躁时说话反而更慢更轻，事后才跟最信得过的人吐槽刚才那个人。",
                "记自己当时的身体反应——手心出汗、肩膀绷紧；对方具体说了什么，反而记不清。",
                "如果“没修养”这顶帽子被人当面说穿——指出她其实很烦——这层平静的门面可能第一次裂开。")),

        // ---- Household "messenger": young flat sharing messenger/clerk/librarian - three different
        // buildings, so this flat's edges below are entirely housing-only, none doubled by a shared
        // workplace. ----
        r("messenger", "阿信", "跑腿送信", "在公告板广场附近帮人跑腿、传话，消息比谁都灵通。", "board", LifeStage.YOUNG, "messenger",
            new ResidentSeed.PersonalityNarrative(
                "想成为第一个知道消息的人。",
                "消息传到我这儿就该往下传，攥着不说才是小气。",
                "转述时会不自觉添一点细节，让故事更好听，他不觉得这是撒谎，只是“让它更像真的发生过”。",
                "记自己是从谁那儿第一个听说的、又传给了几个人；原话反而记不准。",
                "如果有一次添的细节被当面戳穿说“你瞎编的”，「该往下传」这条判断可能会收紧一点点，也可能只是换个人接着传。")),
        r("clerk", "陆敏", "商店伙计", "在商店记账、看店，东西进出都要过一遍她的账本。", "shop", LifeStage.YOUNG, "messenger",
            new ResidentSeed.PersonalityNarrative(
                "想让账目和东西都对得上，一分不差。",
                "借出去的东西必须记清楚，还回来也要当面点清楚，不然算谁的。",
                "嘴上说“不用还、小事”，心里其实在默数，对方拖久了会用玩笑的语气提一句，次数多了语气就不再是玩笑。",
                "记谁借了什么、什么时候借的、还没还清；对方当时的心情和理由，几乎不记。",
                "如果有一次真心不计较之后关系反而更好，「必须记清楚」这条可能第一次松动成“这次就算了”。")),
        r("librarian", "顾雁", "学院书角", "在学院守着一个小书角，借书、还书，大半时间自己也在看。", "academy", LifeStage.YOUNG, "messenger",
            new ResidentSeed.PersonalityNarrative(
                "想要一段完整不被打断的时间，读完一整本书那种完整。",
                "别人来问问题不能表现出不耐烦，这是守书角的人该有的样子。",
                "被打断时会先把手上那页看完再抬头，答得比平时短一点，对方通常察觉不到。",
                "记自己被打断在哪一页、哪一句；对方问的问题内容反而记不清。",
                "如果打断变得频繁到她真的错过了一段很要紧的内容，「不能表现出不耐烦」可能第一次压不住，冒出一句短促的“等一下”。")),

        // ---- Household "baker": two older men sharing a home out of practicality, not particular
        // fondness - deliberately not warmed up the way 知夏/阿满 were in ResidentSeed. ----
        r("baker", "老宋", "咖啡馆烤面包", "在咖啡馆后厨烤面包，手艺是他这辈子唯一较真的事。", "cafe", LifeStage.OLDER, "baker",
            new ResidentSeed.PersonalityNarrative(
                "想让自己做的东西配得上“手艺”两个字，不是“过得去”就行。",
                "东西不到火候就不能拿出去，哪怕客人等急了也不能糊弄。",
                "被催的时候不解释，只是把炉门关得更响一点，照旧按自己的节奏来。",
                "记东西的成色、火候对不对；谁催了、谁等急了，几乎不记。",
                "真的因为等太久丢了一个熟客之后，「不到火候不能拿出去」这条可能第一次松成“差不多就行了”。")),
        r("beekeeper", "老陶", "花园养蜂", "在花园角落养了几箱蜂，蜂比人省心。", "garden", LifeStage.OLDER, "baker",
            new ResidentSeed.PersonalityNarrative(
                "想被由着来，不想被谁的时间表管着。",
                "东西用得顺手，不代表非要立刻还——东西是流动的，不是谁的。",
                "借来的东西用完随手放在自己顺手的地方，想不起来是谁的，提醒了才想起来，也不觉得理亏。",
                "记东西好不好用、顺不顺手；东西是从谁那儿借来的，几乎不记。",
                "如果因为这样真让同住的老宋动了气、场面僵了好几天，「东西是流动的」这条可能第一次被他自己怀疑——但怀疑不代表会改。")),

        // ---- Household "florist": an older couple, warmed up like 知夏/阿满 in ResidentSeed, but for
        // a different reason - they chose each other, not just a spare room. ----
        r("florist", "春杏", "花园卖花", "在花园侍弄花草也卖一点，谁家有喜事她比谁都上心。", "garden", LifeStage.OLDER, "florist",
            new ResidentSeed.PersonalityNarrative(
                "想看到身边的人凑成对、日子热闹起来。",
                "别人的心事我不该主动问，更不该说出去。",
                "会用一盆花当由头制造巧遇，从不直接点破，自己也不觉得这算“管”。",
                "记谁看谁的眼神多停了一下、谁替谁多留了一会儿；那天具体在忙什么反而记不住。",
                "如果一次“顺手促成”弄巧成拙、明显让人不高兴，「不该管」可能真正立住；但也可能只是换种更隐蔽的方式接着来。")),
        r("scribe", "老白", "代写告示", "在公告板广场帮人写字、念告示，字比人挑剔。", "board", LifeStage.OLDER, "florist",
            new ResidentSeed.PersonalityNarrative(
                "想让公告板上的字配得上“说清楚”三个字。",
                "话可以说得难听，但字不能写得潦草——那是对看的人不负责任。",
                "帮人誊抄时会不动声色地改掉措辞，不解释，也不问对方乐不乐意。",
                "记别人原话里的措辞漏洞、错别字；对方想表达的心情，几乎不记。",
                "如果有一次被当面质问“我又没让你改”，「不动声色地改」这条可能第一次收手，变得先问一句。")),

        // ---- Solo households: five older residents living alone. ----
        r("scholar", "老谭", "学院讲古", "在学院给人讲这条街和这个镇子从前的事，没人给他发过聘书。", "academy", LifeStage.OLDER, "scholar",
            new ResidentSeed.PersonalityNarrative(
                "想有人认真听他讲那些没人在意的旧事，也想坐在他惯坐的那个位置——好像位置不对，故事都讲不顺。",
                "讲古时不能被打断，位置也不该让——离了那个角度，故事就散了。",
                "有人明显更需要那个位置时，他会说“就讲一小段”，然后照旧不挪窝。",
                "记谁认真听完了、谁中途走开；自己占着位置这件事本身，从不记。",
                "如果有一次让出位置后，故事反而讲得更好，「离了那个角度就散了」这条可能第一次被他自己怀疑。")),
        r("tailor", "绣娘", "商店缝补", "在商店角落缝缝补补，什么活儿都接，只是不接急活。", "shop", LifeStage.OLDER, "tailor",
            new ResidentSeed.PersonalityNarrative(
                "想让东西被好好对待，不只是被凑合用。",
                "我不能当面说“你自己不爱惜”，那样太伤人。",
                "收到破得离谱的衣服会多问一句“这件常穿吗”，语气平常，心里其实已经有判断，从不说出口。",
                "记东西坏成什么样、大概是什么原因坏的；对方是谁、说了什么理由，几乎不记。",
                "如果同一个人一次次这样，某次可能没绷住，说了一句“你是不是压根没打算爱惜它”。")),
        r("masseur", "老程", "健身房推拿", "在健身房帮人推拿、正骨，自己的旧伤从不提。", "gym", LifeStage.OLDER, "masseur",
            new ResidentSeed.PersonalityNarrative(
                "想有一天不用靠别人的疼痛证明自己还有用。",
                "自己的旧伤不能提，提了显得像是在讨照顾。",
                "别人问起他走路的姿势，他说“老毛病，没事”，然后转头去问对方哪里不舒服。",
                "记别人身体的细节——哪只肩、哪条腿；自己的状况选择性遗忘，连自己都说不准哪天更疼。",
                "如果疼到真的做不动一次示范、当场露了怯，「不能提」这条可能第一次绷不住。")),
        r("broker", "定叔", "公告板牵线", "在公告板广场帮人对上活儿和人手，谁需要什么先问他准没错。", "board", LifeStage.OLDER, "broker",
            new ResidentSeed.PersonalityNarrative(
                "想手里握着点别人用得着的东西——那是他在这条街上的分量。",
                "我这是帮大家牵线，不是为自己捞好处。",
                "撮合的时候总先想一句“这事对我有没有用”，嘴上说出来的却是“就是随手的事”。",
                "记谁欠他一个人情、谁的活儿是他牵的线；对方后来过得好不好，几乎不记。",
                "如果有一次明摆着他从中占了便宜、被人当面说破，「不是为自己捞好处」这条自我认识可能第一次裂开——但更可能是他矢口否认。")),
        r("trader", "秋姨", "推车换货", "推着车在公告板广场附近换东西，从不让自己吃亏，也不让自己占便宜。", "board", LifeStage.OLDER, "trader",
            new ResidentSeed.PersonalityNarrative(
                "想什么都不欠人、也不被人欠——扯平了才舒服。",
                "换东西必须对等，占了便宜要主动找补，吃了亏也要说出来，不能憋着。",
                "换东西时当场把对等的东西讲清楚，不留“人情”这种说不清的账；对方想欠着，她会主动提醒。",
                "记谁欠她东西没找补、她欠了谁没找补；交换时的气氛几乎不记。",
                "如果有一次“找补”被对方明确拒绝，说“这就是送你的，别算了”，「必须对等」这条可能第一次被动摇。"))
    );

    private static final Map<String, NewResident> BY_ID =
        ALL.stream().collect(Collectors.toMap(NewResident::id, x -> x, (a, b) -> a, LinkedHashMap::new));

    /** All nineteen, in authoring order. */
    public static List<NewResident> all() { return ALL; }

    /** One new resident by id, or {@code null} - never one of the six existing residents or "self",
     * which stay {@link ResidentSeed}'s. */
    public static NewResident of(String id) { return BY_ID.get(id); }

    public static List<String> ids() { return ALL.stream().map(NewResident::id).toList(); }

    /** Household id -> every member, host first. The host is exactly who should get {@code
     * TownPlaces.addHome}; every other id in the list is a flatmate who should get {@code
     * TownPlaces.addFlatmate(w, id, hostId)} - the same mechanism ResidentSeed already uses for 阿满
     * sharing 知夏's home, just applied to bigger flats. A single-element list is a resident who lives
     * alone. Ten households cover all nineteen: two four-person flats, one three-person flat (twice),
     * two older pairs, and five solo older residents - docs/01's 「年轻人几个合租一屋...年长的独居或两
     * 人同住」. */
    public static Map<String, List<String>> households() {
        Map<String, List<String>> h = new LinkedHashMap<>();
        h.put("barista", List.of("barista", "waiter", "tutor"));
        h.put("botanist", List.of("botanist", "trainer", "boxer", "yogi"));
        h.put("messenger", List.of("messenger", "clerk", "librarian"));
        h.put("baker", List.of("baker", "beekeeper"));
        h.put("florist", List.of("florist", "scribe"));
        h.put("scholar", List.of("scholar"));
        h.put("tailor", List.of("tailor"));
        h.put("masseur", List.of("masseur"));
        h.put("broker", List.of("broker"));
        h.put("trader", List.of("trader"));
        return Map.copyOf(h);
    }

    /** Public building -> every resident (the relevant existing ones included) whose occupation
     * centers on it, per docs/01 第二版「世界」's six public buildings. Each list is exactly four long -
     * 24 of the 25 residents land in one of these six; 知夏 (artist) is the one existing resident this
     * batch leaves unassigned, matching how her occupation was already written in ResidentSeed. */
    public static Map<String, List<String>> occupationClusters() {
        return Map.of(
            "cafe", List.of("owner", "barista", "baker", "waiter"),
            "garden", List.of("gardener", "florist", "beekeeper", "botanist"),
            "academy", List.of("student", "tutor", "librarian", "scholar"),
            "shop", List.of("fixer", "weaver", "clerk", "tailor"),
            "gym", List.of("trainer", "masseur", "boxer", "yogi"),
            "board", List.of("messenger", "scribe", "broker", "trader")
        );
    }

    /** Directed initial-relationship edges, on {@code ResidentState.relationships}' own 0-100 scale:
     * id -&gt; (otherId -&gt; starting regard). Built from nothing but {@link #households()} and
     * {@link #occupationClusters()} - a pair absent from both directions here is a stranger, not a
     * zero, per docs/01 「初始关系按住所／职业聚类，其余互不相识」. See the class javadoc for why a few
     * of these are hand-authored asymmetric rather than every edge sharing one mutual number. */
    public static Map<String, Map<String, Integer>> initialRelationships() {
        Map<String, Map<String, Integer>> rel = new LinkedHashMap<>();
        // Household cliques - young flats warmer than a same-day introduction, older pairs cooler
        // unless the household javadoc above says otherwise.
        clique(rel, List.of("barista", "waiter", "tutor"), 46);
        clique(rel, List.of("botanist", "trainer", "boxer", "yogi"), 46);
        clique(rel, List.of("messenger", "clerk", "librarian"), 46);
        clique(rel, List.of("baker", "beekeeper"), 40);
        clique(rel, List.of("florist", "scribe"), 58);
        // Occupation cliques - colleagues who just started sharing a building.
        clique(rel, List.of("owner", "barista", "baker", "waiter"), 39);
        clique(rel, List.of("gardener", "florist", "beekeeper", "botanist"), 39);
        clique(rel, List.of("student", "tutor", "librarian", "scholar"), 39);
        clique(rel, List.of("fixer", "weaver", "clerk", "tailor"), 39);
        clique(rel, List.of("trainer", "masseur", "boxer", "yogi"), 39);
        clique(rel, List.of("messenger", "scribe", "broker", "trader"), 39);
        // A pair who is both housemates and colleagues starts a little warmer than either reason
        // alone would produce.
        edge(rel, "barista", "waiter", 48); edge(rel, "waiter", "barista", 48);
        edge(rel, "boxer", "yogi", 46); edge(rel, "yogi", "boxer", 46);
        // Hand-authored asymmetry: these nine pairs are the ones this batch actually gives a
        // different number in each direction, tied to a specific trait in the two personas involved
        // rather than picked at random.
        edge(rel, "baker", "beekeeper", 36); // 老宋 minds that 老陶 never quite returns a borrowed tool.
        edge(rel, "broker", "messenger", 34); edge(rel, "messenger", "broker", 41);
        edge(rel, "broker", "scribe", 34); edge(rel, "scribe", "broker", 39);
        edge(rel, "broker", "trader", 34); edge(rel, "trader", "broker", 40);
        edge(rel, "scholar", "student", 44); edge(rel, "student", "scholar", 39);
        edge(rel, "scholar", "tutor", 44); edge(rel, "tutor", "scholar", 37);
        edge(rel, "scholar", "librarian", 44); edge(rel, "librarian", "scholar", 38);
        edge(rel, "trainer", "boxer", 48); edge(rel, "boxer", "trainer", 44);
        edge(rel, "trainer", "yogi", 47); edge(rel, "yogi", "trainer", 43);
        Map<String, Map<String, Integer>> frozen = new LinkedHashMap<>();
        rel.forEach((id, edges) -> frozen.put(id, Map.copyOf(edges)));
        return Map.copyOf(frozen);
    }

    private static void clique(Map<String, Map<String, Integer>> rel, List<String> members, int value) {
        for (String a : members) for (String b : members) if (!a.equals(b)) edge(rel, a, b, value);
    }

    private static void edge(Map<String, Map<String, Integer>> rel, String from, String to, int value) {
        rel.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(to, value);
    }
}
