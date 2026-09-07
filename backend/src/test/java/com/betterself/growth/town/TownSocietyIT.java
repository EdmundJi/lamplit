package com.betterself.growth.town;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限知与传播这条链路的端到端验收（plan M1-3 / M1-4 / M1-8）。
 *
 * <p>三条性质是这个案子的立身之本，坏了就等于整个设计没做：
 * <ol>
 *   <li>玩家的事实落库时已经模糊化——{@code town_fact} 里查不到任何阿拉伯数字；</li>
 *   <li>没人在场的时候，只有小助知道，而且它那条是 {@code no_relay}；</li>
 *   <li>因此小助的 talking-points 恒为空——它对你说话，但不对外传话。</li>
 * </ol>
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *",
    "app.town.society-cron=0 0 0 1 1 *",
    "app.security.access-token-ttl=P35D"
})
@AutoConfigureMockMvc
class TownSocietyIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
        registry.add("app.security.jwt-secret", () -> "test-only-secret-at-least-thirty-two-bytes");
        registry.add("app.security.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.security.secure-cookies", () -> false);
    }

    private static final Pattern DIGIT = Pattern.compile("\\d");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TownSocietyService society;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    QwenRetellGenerator retellGenerator;

    @Autowired TownNpcProvisioner provisioner;
    @Autowired TownMoodService moodService;
    @Autowired TownDailyProduction daily;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired java.time.Clock clock;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void firstRosterBootstrapsSixOwnedPublicPlansWithoutLlmOrBroadcast() throws Exception {
        Session owner = register("town-first-day-public-plans@example.test");
        long id = userIdOf("town-first-day-public-plans@example.test");
        org.mockito.Mockito.clearInvocations(retellGenerator);
        var response = mvc.perform(get("/api/v1/town/npcs").cookie(owner.access()))
            .andExpect(status().isOk()).andReturn();
        var first = mapper.readTree(body(response)).path("data").path("npcs");
        var factIds = new java.util.HashSet<String>();
        var individualLines = new java.util.HashSet<String>();
        for (var npc : first) {
            var points = npc.path("talkingPoints");
            if (npc.path("layer").asInt() == 2) {
                assertThat(points.size()).isEqualTo(1);
                String factId = points.get(0).path("factId").asText();
                factIds.add(factId);
                individualLines.add(points.get(0).path("text").asText().replace(npc.path("displayName").asText(), ""));
                assertThat(jdbc.queryForObject("""
                    select count(*) from town_fact f join town_npc_knowledge k on k.fact_id=f.id
                    where f.public_id=? and f.town_user_id=? and f.subject_kind='NPC'
                      and f.kind='NPC_DAY_PLAN' and k.npc_code=f.subject_ref and k.npc_code=?
                      and k.learned_from='SELF' and k.hops=0 and k.retold_text is not null
                    """,Integer.class,factId,id,npc.path("code").asText())).isEqualTo(1);
            } else {
                assertThat(points.size()).as("bootstrap is not a town-wide broadcast").isZero();
            }
        }
        assertThat(factIds).hasSize(6);
        assertThat(individualLines).hasSize(6);
        assertThat(jdbc.queryForObject("select count(*) from town_fact where town_user_id=?",Integer.class,id)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from town_npc_knowledge where town_user_id=?",Integer.class,id)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from town_fact where town_user_id=? and subject_kind='PLAYER'",Integer.class,id)).isZero();
        // Repeated/concurrent GETs neither mint new identities nor refresh salience after decay.
        jdbc.update("update town_npc_knowledge set salience=0.2 where town_user_id=?",id);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> society.roster(id));
            var b = executor.submit(() -> society.roster(id));
            a.get(); b.get();
        }
        var again = society.roster(id);
        assertThat(again.npcs().stream().flatMap(n -> n.talkingPoints().stream()).map(TownSocietyService.TalkingPoint::factId).toList())
            .containsExactlyInAnyOrderElementsOf(factIds);
        assertThat(jdbc.queryForObject("select max(salience) from town_npc_knowledge where town_user_id=?",Double.class,id)).isEqualTo(0.2);
        assertThat(jdbc.queryForObject("select count(*) from town_npc_knowledge where town_user_id=?",Integer.class,id)).isEqualTo(6);
        org.mockito.Mockito.verify(retellGenerator,org.mockito.Mockito.never()).retell(org.mockito.ArgumentMatchers.anyList());
    }

    private void freezeAt(long userId, LocalDate date, java.util.Set<String> cafeResidents) throws Exception {
        moodService.ensureDay(userId,date);
        tx.executeWithoutResult(status -> daily.claim(userId,date,"EVENT"));
        for(String code:jdbc.queryForList("select npc_code from town_npc where town_user_id=?",String.class,userId)) {
            var plan=new TownDayPlan.DayPlan(date,List.of(new TownDayPlan.Errand(
                cafeResidents.contains(code)?"cafe":"home","sit",0,1440,1,"RHYTHM")),List.of());
            jdbc.update("update town_npc_mood set day_plan=cast(? as json) where town_user_id=? and npc_code=? and local_date=?",
                mapper.writeValueAsString(plan),userId,code,java.sql.Date.valueOf(date));
        }
    }

    @Test
    void recognizesReportedPublicEntrancesAndRealInteriorKeysButNotTownOrHome() throws Exception {
        register("town-presence-tags@example.test");
        long id = userIdOf("town-presence-tags@example.test");
        LocalDate date = daily.today(id);
        freezeAt(id,date,java.util.Set.of());
        var plan = new TownDayPlan.DayPlan(date,List.of(
            new TownDayPlan.Errand("home","idle",0,360,1,"RHYTHM"),
            new TownDayPlan.Errand("academy","reading",360,420,1,"RHYTHM"),
            new TownDayPlan.Errand("gym","idle",420,480,1,"RHYTHM"),
            new TownDayPlan.Errand("cafe","sit",480,540,1,"RHYTHM"),
            new TownDayPlan.Errand("park","sit",540,600,1,"RHYTHM"),
            new TownDayPlan.Errand("plaza","sit",600,660,1,"RHYTHM"),
            new TownDayPlan.Errand("home","idle",660,1440,1,"RHYTHM")),List.of());
        jdbc.update("update town_npc_mood set day_plan=cast(? as json) where town_user_id=? and npc_code='KE_YUN' and local_date=?",
            mapper.writeValueAsString(plan),id,java.sql.Date.valueOf(date));
        String[][] reports = {{"interior:academy-study","370"},{"town:academy","371"},
            {"interior:public-gym","430"},{"town:gym","431"},{"interior:cafe-interior","490"},
            {"town:cafe","491"},{"town","492"},{"unknown","493"},{"interior:home-living-room","494"},
            {"town:home","495"},{"town:park","550"},{"town:plaza","610"}};
        for (String[] report : reports) {
            var instant = date.atStartOfDay(daily.zone(id)).plusMinutes(Integer.parseInt(report[1])).toInstant();
            new TownPresenceService(jdbc,tx,java.time.Clock.fixed(instant,java.time.ZoneOffset.UTC))
                .report(id,new TownPresenceService.PresenceCommand(100.0,100.0,"down",report[0]));
        }
        society.runNightly(id,date);
        assertThat(jdbc.queryForList("""
            select f.kind from town_fact f join town_npc_knowledge k on k.fact_id=f.id
            where f.town_user_id=? and f.kind like 'PUBLIC_VISIT_%' and k.npc_code='KE_YUN'
            """,String.class,id)).containsExactlyInAnyOrder("PUBLIC_VISIT_ACADEMY","PUBLIC_VISIT_GYM",
                "PUBLIC_VISIT_CAFE","PUBLIC_VISIT_PARK","PUBLIC_VISIT_PLAZA");
        assertThat(jdbc.queryForObject("select count(*) from town_fact where town_user_id=? and kind like 'PUBLIC_VISIT_%'",Integer.class,id)).isEqualTo(5);
    }

    @Test
    void actualPresenceSamplesSurviveLatestPositionAndOnlyAuthorizeVisibleVisits() throws Exception {
        register("town-sample-proof@example.test");
        long id=userIdOf("town-sample-proof@example.test");
        LocalDate date=daily.today(id);
        freezeAt(id,date,java.util.Set.of("KE_YUN","LU_XIA","TOWNIE_01"));
        var sampleClock=java.time.Clock.fixed(date.atTime(10,10).atZone(daily.zone(id)).toInstant(),java.time.ZoneOffset.UTC);
        var presence=new TownPresenceService(jdbc,tx,sampleClock);
        presence.report(id,new TownPresenceService.PresenceCommand(100.0,100.0,"down","cafe"));
        presence.report(id,new TownPresenceService.PresenceCommand(100.0,100.0,"down","cafe"));
        presence.report(id,new TownPresenceService.PresenceCommand(100.0,100.0,"down","home"));
        assertThat(jdbc.queryForObject("select count(*) from town_presence_sample where user_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(presence.latest(id).scene()).isEqualTo("home");
        society.runNightly(id,date);
        var visible=jdbc.queryForList("""
            select distinct f.kind from town_npc_knowledge k join town_fact f on f.id=k.fact_id
            where k.town_user_id=? and k.npc_code<>'GUIDE' and f.subject_kind='PLAYER'
            """,String.class,id);
        assertThat(visible).containsExactly("PUBLIC_VISIT_CAFE");
        assertThat(jdbc.queryForObject("""
            select count(*) from town_npc_knowledge k join town_fact f on f.id=k.fact_id
            join town_npc n on n.town_user_id=k.town_user_id and n.npc_code=k.npc_code
            where k.town_user_id=? and n.layer=3 and f.subject_kind='PLAYER'
            """,Integer.class,id)).isZero();
    }

    @Test
    void latestPresenceAloneCannotInventHistoricalWitnesses() throws Exception {
        register("town-no-fake-trajectory@example.test");
        long id=userIdOf("town-no-fake-trajectory@example.test");
        LocalDate date=daily.today(id);
        freezeAt(id,date,java.util.Set.of("KE_YUN","LU_XIA"));
        jdbc.update("insert into town_presence(user_id,x,y,facing,scene,updated_at) values (?,100,100,'down','cafe',?)",
            id,Timestamp.from(date.atTime(10,10).atZone(daily.zone(id)).toInstant()));
        society.runNightly(id,date);
        assertThat(jdbc.queryForList("""
            select distinct k.npc_code from town_npc_knowledge k join town_fact f on f.id=k.fact_id
            where k.town_user_id=? and f.subject_kind='PLAYER'
            """,String.class,id)).containsExactly("GUIDE");
    }

    @Test
    void regardEntersProductionOnlyAsAThirdPartyGuessNotAnOwnersDisclosure() throws Exception {
        register("town-regard-witness@example.test");
        long id=userIdOf("town-regard-witness@example.test");
        LocalDate date=daily.today(id);
        freezeAt(id,date,java.util.Set.of("KE_YUN","LU_XIA","WEN_QING"));
        assertThat(jdbc.queryForObject("select count(*) from town_bond where town_user_id=? and regard>0",Integer.class,id)).isPositive();
        jdbc.update("update town_bond set regard=0,regard_kind=null where town_user_id=?",id);
        jdbc.update("""
            insert into town_bond(public_id,town_user_id,a_kind,a_ref,b_kind,b_ref,affinity,regard,regard_kind)
            values ('01JREGARDPRODUCTION0000001',?,'NPC','KE_YUN','NPC','LU_XIA',0.1,0.9,'CRUSH')
            on duplicate key update regard=0.9,regard_kind='CRUSH'
            """,id);
        society.runNightly(id,date);
        var witnesses=jdbc.queryForList("""
            select k.npc_code from town_npc_knowledge k join town_fact f on f.id=k.fact_id
            where k.town_user_id=? and f.kind='REGARD_GUESS' and k.hops=0 and k.no_relay=0
            """,String.class,id);
        assertThat(witnesses).containsExactly("WEN_QING");
        assertThat(jdbc.queryForObject("""
            select count(*) from town_npc_knowledge k join town_fact f on f.id=k.fact_id
            where k.town_user_id=? and f.kind='REGARD_GUESS' and k.npc_code='KE_YUN'
            """,Integer.class,id)).isZero();
        var json=mapper.writeValueAsString(society.roster(id));
        assertThat(json).doesNotContain("regard", "CRUSH", "确实暗恋");
        assertThat(json).contains("affinityToNpcs");
        assertThat(jdbc.queryForObject("select coalesce(sum(used),0) from town_initiative_budget where user_id=?",Integer.class,id)).isZero();
    }

    @Test
    void blursPlayerFactsKeepsTheGuideSilentAndServesPerNpcTalkingPoints() throws Exception {
        Session owner = register("town-society@example.test");
        long userId = userIdOf("town-society@example.test");

        // 名册接口本身就会把 18 个 NPC 落地，所以第一次进镇就有人。
        MvcResult roster = mvc.perform(get("/api/v1/town/npcs").cookie(owner.access()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(roster)).contains("\"initiativeBudget\"");
        assertThat(jdbc.queryForObject("select count(*) from town_npc where town_user_id = ?", Integer.class, userId))
            .isEqualTo(18);

        LocalDate today = LocalDate.now();

        // 深夜独自在家完成任务：这就是 M1-4 说的「深夜无人时」。在家永远不构成目击（sawPlayer
        // 把 HOME 整个排除），所以这个场景是确定的，不依赖当天恰好没有夜猫子在街上。
        jdbc.update("""
            insert into town_presence (user_id, x, y, facing, scene, updated_at)
            values (?, 100, 100, 'down', 'home', ?)
            on duplicate key update scene = values(scene), updated_at = values(updated_at)
            """, userId, Timestamp.valueOf(today.atTime(3, 0)));

        society.runNightly(userId, today);

        // (1) 模糊化：玩家事实里一个数字都不许有。
        List<String> playerTexts = jdbc.queryForList("""
            select json_unquote(json_extract(payload, '$.text')) from town_fact
            where town_user_id = ? and subject_kind = 'PLAYER'
            """, String.class, userId);
        assertThat(playerTexts).isNotEmpty();
        for (String text : playerTexts) {
            assertThat(DIGIT.matcher(text).find())
                .as("blurred player fact must not leak a raw number: %s", text)
                .isFalse();
        }

        // (2) 深夜无人目击：玩家的事只有小助知道，且不外传。
        List<String> knowers = jdbc.queryForList("""
            select distinct k.npc_code from town_npc_knowledge k join town_fact f on f.id = k.fact_id
            where k.town_user_id = ? and f.subject_kind = 'PLAYER'
            """, String.class, userId);
        assertThat(knowers).containsExactly("GUIDE");

        Integer relayable = jdbc.queryForObject("""
            select count(*) from town_npc_knowledge k join town_fact f on f.id = k.fact_id
            where k.town_user_id = ? and f.subject_kind = 'PLAYER' and k.no_relay = 0
            """, Integer.class, userId);
        assertThat(relayable).isZero();

        // (3) 因此小助没有任何可播的话——全知，但不是消息源。
        MvcResult guide = mvc.perform(get("/api/v1/town/npc/GUIDE/talking-points").cookie(owner.access()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(guide)).contains("\"points\":[]");

        // 镇上没有的人要 404，而不是给一份空名单冒充存在。
        mvc.perform(get("/api/v1/town/npc/NOBODY/talking-points").cookie(owner.access()))
            .andExpect(status().isNotFound());

        // 重跑幂等，而且不只是事实不翻倍：衰减、传播、亲密度这三步都是累积的，再跑一次不等于
        // 「这一天又发生了一遍」，而是凭空多出一轮。所以要连 knowledge 条数和 meet_count 一起钉。
        int factsBefore = countFacts(userId);
        Integer knowledgeBefore = jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ?", Integer.class, userId);
        Integer meetsBefore = jdbc.queryForObject(
            "select coalesce(max(meet_count), 0) from town_bond where town_user_id = ?", Integer.class, userId);

        society.runNightly(userId, today);
        society.runNightly(userId, today);

        assertThat(countFacts(userId)).isEqualTo(factsBefore);
        assertThat(jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ?", Integer.class, userId))
            .isEqualTo(knowledgeBefore);
        assertThat(jdbc.queryForObject(
            "select coalesce(max(meet_count), 0) from town_bond where town_user_id = ?", Integer.class, userId))
            .as("meet_count must count days, not how many times the job was triggered")
            .isEqualTo(meetsBefore);
    }

    @Test
    void witnessedDaytimeVisitLetsOtherNpcsLearnAndRetellIt() throws Exception {
        Session owner = register("town-society-day@example.test");
        long userId = userIdOf("town-society-day@example.test");
        society.roster(userId);

        LocalDate today = LocalDate.now();
        // 白天在学院露面：那个时段有人在学院的 NPC 就该看见。
        jdbc.update("""
            insert into town_presence (user_id, x, y, facing, scene, updated_at)
            values (?, 100, 100, 'down', 'academy', ?)
            on duplicate key update scene = values(scene), updated_at = values(updated_at)
            """, userId, Timestamp.valueOf(today.atTime(10, 30)));

        society.runNightly(userId, today);

        // NPC 自己的事一定会产生、会被传开——这保证了长镜头里有人有话可说。
        Integer npcFacts = jdbc.queryForObject(
            "select count(*) from town_fact where town_user_id = ? and subject_kind = 'NPC'",
            Integer.class, userId);
        assertThat(npcFacts).isPositive();

        // 走样文本必须写进去了，否则前端相遇时无话可播。
        Integer retold = jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ? and retold_text is not null",
            Integer.class, userId);
        assertThat(retold).isPositive();

        // 转述文本同样不许泄漏数字。
        for (String text : jdbc.queryForList(
            "select retold_text from town_npc_knowledge where town_user_id = ? and retold_text is not null",
            String.class, userId)) {
            assertThat(DIGIT.matcher(text).find()).as("retold text leaked a number: %s", text).isFalse();
        }

        // §3.4 第 5 条：三层背景居民不持有本镇玩家的事实——目击拿不到，顺着传闻也传不进来。
        Integer layerThreeKnowers = jdbc.queryForObject("""
            select count(*) from town_npc_knowledge k
            join town_fact f on f.id = k.fact_id
            join town_npc n on n.town_user_id = k.town_user_id and n.npc_code = k.npc_code
            where k.town_user_id = ? and f.subject_kind = 'PLAYER' and n.layer >= 3
            """, Integer.class, userId);
        assertThat(layerThreeKnowers)
            .as("background residents must not hold this town's player facts")
            .isZero();

        // 小助全程不进传播网络：不只是"它知道的不外传"，而是没有任何一条 knowledge 是从它那儿
        // 听来的。只过滤它已有的 knowledge 不够——它会在相遇里听到新的一条然后成为下一手的
        // 消息源，那样"全知但不八卦"就破了。
        Integer fromGuide = jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ? and learned_from = 'GUIDE'",
            Integer.class, userId);
        assertThat(fromGuide).as("nobody may ever learn anything from the guide").isZero();

        // 限知的可观测形态：不是所有人都知道同样的事。
        List<Integer> perNpc = jdbc.queryForList("""
            select count(*) from town_npc_knowledge where town_user_id = ? group by npc_code
            """, Integer.class, userId);
        assertThat(perNpc).isNotEmpty();
        assertThat(perNpc.stream().distinct().count())
            .as("every NPC knowing exactly the same number of things would mean 限知 isn't working")
            .isGreaterThan(1);
    }

    @Test
    void initiativeBudgetPersistsAcrossRequestsAndSaturatesAtTheLimit() throws Exception {
        // 护栏 A：之前 used 只在前端内存里，刷新即归零。这里钉住它现在是真的落库的——
        // 连续调用会累加，且顶到 DAILY_INITIATIVE_LIMIT 之后不再往上涨（幂等的饱和状态）。
        Session owner = register("town-initiative@example.test");

        mvc.perform(get("/api/v1/town/npcs").cookie(owner.access())).andExpect(status().isOk());

        for (int i = 1; i <= TownSocietyService.DAILY_INITIATIVE_LIMIT; i++) {
            MvcResult result = mvc.perform(post("/api/v1/town/initiative/consume")
                    .cookie(owner.access(), owner.csrf())
                    .header("X-CSRF-Token", owner.csrf().getValue()))
                .andExpect(status().isOk())
                .andReturn();
            assertThat(body(result)).contains("\"used\":" + i);
        }

        // 顶到上限之后，roster() 读到的 used 与再消费一次都必须停在 limit，不再往上涨。
        MvcResult overLimit = mvc.perform(post("/api/v1/town/initiative/consume")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(overLimit)).contains("\"used\":" + TownSocietyService.DAILY_INITIATIVE_LIMIT);

        MvcResult roster = mvc.perform(get("/api/v1/town/npcs").cookie(owner.access()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(roster)).contains(
            "\"initiativeBudget\":{\"limit\":" + TownSocietyService.DAILY_INITIATIVE_LIMIT
                + ",\"used\":" + TownSocietyService.DAILY_INITIATIVE_LIMIT + "}");
    }

    private long userIdOf(String email) {
        return jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
    }

    private int countFacts(long userId) {
        return jdbc.queryForObject("select count(*) from town_fact where town_user_id = ?", Integer.class, userId);
    }

    /**
     * 集成接线：今天有活动的人，当天行程里必须多出一条 priority=2 的安排（M7-3 验收）。
     *
     * <p>这条同时钉死了整晚流水线的顺序——{@code TownEventService} 必须排在社会模拟之前，
     * 否则夜里推相遇时看不见这场活动，而白天 {@code roster()} 看得见，两份日程就分了叉，
     * plan §3.5 那条「两边算出来的必须是同一份」立刻不成立。
     */
    @Test
    void anEventTodayShowsUpAsAHighPriorityErrandInTheHostsDayPlan() throws Exception {
        Session owner = register("town-event-plan@example.test");
        long userId = userIdOf("town-event-plan@example.test");
        provisioner.ensurePopulated(userId);

        String host = jdbc.queryForObject(
            "select npc_code from town_npc where town_user_id = ? and layer = 2 order by npc_code limit 1",
            String.class, userId);
        LocalDate today = LocalDate.now();
        jdbc.update("""
            insert into town_event
                (public_id, town_user_id, host_npc_code, kind, venue, dimension, starts_at, ends_at, created_at)
            values (?, ?, ?, 'GATHERING', 'plaza', null, ?, ?, ?)
            """, "01JEVENTPLAN0000000000000A", userId, host,
            Timestamp.valueOf(today.atTime(18, 0)), Timestamp.valueOf(today.atTime(20, 0)),
            Timestamp.valueOf(LocalDateTime.now()));

        MvcResult roster = mvc.perform(get("/api/v1/town/npcs").cookie(owner.access()))
            .andExpect(status().isOk())
            .andReturn();

        // 只看这一个 NPC 的那段 JSON，免得别人的行程也含 "plaza" 造成误判。
        String all = body(roster);
        int start = all.indexOf("\"code\":\"" + host + "\"");
        assertThat(start).as("host %s must appear in the roster", host).isGreaterThan(-1);
        int end = all.indexOf("\"code\":\"", start + 1);
        String hostJson = end > start ? all.substring(start, end) : all.substring(start);

        assertThat(hostJson).contains("\"origin\":\"EVENT\"");
        assertThat(hostJson).contains("\"priority\":2");
        assertThat(hostJson).contains("\"place\":\"plaza\"");
    }

    /**
     * 迁徙是跨小镇的一次写，所以「今天才搬来的人」当晚不参与模拟——否则 B 镇那一晚算出什么
     * 取决于 A、B 两个用户谁先被 job 扫到，而整个限知模型的地基是同一天能重算出同一条链。
     *
     * <p>但他当天就该在街上看得见：{@code roster()} 里有他，{@code town_npc_knowledge} 里没有他。
     */
    @Test
    void anNpcThatArrivedTodayIsVisibleButSitsOutTonightsSimulation() throws Exception {
        register("town-arrival@example.test");
        long userId = userIdOf("town-arrival@example.test");
        society.roster(userId);

        String newcomer = jdbc.queryForObject(
            "select npc_code from town_npc where town_user_id = ? and layer = 3 order by npc_code limit 1",
            String.class, userId);
        LocalDate today = LocalDate.now();
        // 造一条"今晚刚从别的镇搬来"的迁徙记录——判断依据是它，不是建号时就写好的 settled_at。
        jdbc.update("""
            insert into town_migration
                (public_id, npc_code, from_user_id, to_user_id, paired_with_npc_code, moved_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """, "01JARRIVEDTODAY000000000A", "TOWNIE_10", userId, userId, newcomer,
            Timestamp.valueOf(today.atTime(2, 0)), Timestamp.valueOf(LocalDateTime.now()));
        jdbc.update("update town_npc set settled_at = ? where town_user_id = ? and npc_code = ?",
            Timestamp.valueOf(today.atTime(2, 0)), userId, newcomer);

        society.runNightly(userId, today);

        assertThat(jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ? and npc_code = ?",
            Integer.class, userId, newcomer))
            .as("today's arrival must not join tonight's propagation")
            .isZero();
        assertThat(society.roster(userId).npcs().stream().map(TownSocietyService.NpcView::code))
            .as("but he is on the street from day one")
            .contains(newcomer);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private record Session(Cookie access, Cookie csrf) {
    }
}
