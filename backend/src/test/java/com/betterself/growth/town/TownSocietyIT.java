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

    private long userIdOf(String email) {
        return jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
    }

    private int countFacts(long userId) {
        return jdbc.queryForObject("select count(*) from town_fact where town_user_id = ?", Integer.class, userId);
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
