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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 信件三轨（LONG/NOTE/INVITE）+ 事件请柬 + 树洞笔友的端到端验收。
 *
 * <p>三条最要紧的性质：
 * <ol>
 *   <li>请柬按 affinity 降序送达（M4-4）；</li>
 *   <li>树洞往来完全不碰 {@code town_fact}/{@code town_npc_knowledge}（M4-8，plan §3.4 第 6 条）；</li>
 *   <li>写信当天不会有回信，隔一天才有（M4-8「慢是特性」）。</li>
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
class TownSocialFlowIT {

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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TownSocietyService society;
    @Autowired TownEventService events;
    @Autowired TownConfidantService confidant;

    @Test
    void invitationsGoOutInAffinityOrderAndDeliverOneLetterToThePlayer() throws Exception {
        Session owner = register("town-event@example.test");
        long userId = userIdOf("town-event@example.test");
        society.roster(userId); // 落地 18 个 NPC

        LocalDate today = LocalDate.now();
        Long eventId = null;
        LocalDate hostedOn = null;
        for (int day = 0; day < 60 && eventId == null; day++) {
            LocalDate candidateDate = today.plusDays(day);
            events.runNightly(userId, candidateDate);
            eventId = jdbc.query("select id from town_event where town_user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, userId);
            if (eventId != null) {
                hostedOn = candidateDate;
            }
        }
        assertThat(eventId).as("30 天模拟已经证明这组人设迟早会有人办活动").isNotNull();

        String hostCode = jdbc.queryForObject(
            "select host_npc_code from town_event where id = ?", String.class, eventId);

        List<Map<String, Object>> invitations = jdbc.queryForList(
            "select recipient_kind, recipient_ref, delivered_at from town_invitation "
                + "where event_id = ? order by delivered_at asc", eventId);
        assertThat(invitations).isNotEmpty();

        // 独立于"到底是谁办的活动"，重新按亲密度算一遍期望顺序，和实际送达顺序比对。
        List<String> expectedOrder = invitations.stream()
            .map(row -> (String) row.get("recipient_ref"))
            .sorted((a, b) -> {
                double affinityA = affinityToHost(userId, hostCode, a);
                double affinityB = affinityToHost(userId, hostCode, b);
                int cmp = Double.compare(affinityB, affinityA);
                return cmp != 0 ? cmp : a.compareTo(b);
            })
            .toList();
        List<String> actualOrder = invitations.stream().map(row -> (String) row.get("recipient_ref")).toList();
        assertThat(actualOrder).as("亲密度高的先收到").isEqualTo(expectedOrder);

        // 玩家收到的是一封 INVITE 信，且全镇只应该有这一封（不重复发信给同一场活动）。
        Integer inviteLetters = jdbc.queryForObject(
            "select count(*) from town_letter where recipient_user_id = ? and kind = 'INVITE'",
            Integer.class, userId);
        assertThat(inviteLetters).isEqualTo(1);

        MvcResult inbox = mvc.perform(get("/api/v1/town/letters").cookie(owner.access()))
            .andExpect(status().isOk()).andReturn();
        assertThat(body(inbox)).contains("\"kind\":\"INVITE\"").contains("\"unreadCount\":1");

        // 幂等：同一天重跑不产生第二场活动、不重发第二轮请柬——只重跑"已经办过"的那一天，
        // 不能重跑整个区间（那样会让原本还没轮到的日子也生出新的一场，误伤这条断言）。
        int eventCountBefore = countEvents(userId);
        int invitationCountBefore = invitations.size();
        events.runNightly(userId, hostedOn);
        events.runNightly(userId, hostedOn);
        assertThat(countEvents(userId)).isEqualTo(eventCountBefore);
        Integer invitationCountAfter = jdbc.queryForObject(
            "select count(*) from town_invitation where event_id = ?", Integer.class, eventId);
        assertThat(invitationCountAfter).isEqualTo(invitationCountBefore);
    }

    @Test
    void confidantThreadNeverTouchesFactsOrKnowledgeAndRepliesOnlyTheDayAfter() throws Exception {
        Session owner = register("town-confidant@example.test");
        long userId = userIdOf("town-confidant@example.test");
        society.roster(userId);

        int factsBefore = countFacts(userId);
        int knowledgeBefore = countKnowledge(userId);

        mvc.perform(post("/api/v1/town/confidant").cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"今天有点累，不知道该跟谁说。\"}"))
            .andExpect(status().isOk());

        // (1) 硬隔离：写一封树洞信前后，town_fact / town_npc_knowledge 行数纹丝不动。
        assertThat(countFacts(userId)).isEqualTo(factsBefore);
        assertThat(countKnowledge(userId)).isEqualTo(knowledgeBefore);

        LocalDate today = LocalDate.now();
        // (2) 当天跑夜间 job 不该有回信——"隔天"是硬约束，不是"下次 job 跑过就有"。
        confidant.runNightly(userId, today);
        assertThat(unreadLetterCount(userId, owner)).isZero();

        // (3) 隔一天，回信才出现，且仍然不碰 fact/knowledge。
        confidant.runNightly(userId, today.plusDays(1));
        assertThat(countFacts(userId)).isEqualTo(factsBefore);
        assertThat(countKnowledge(userId)).isEqualTo(knowledgeBefore);

        MvcResult inbox = mvc.perform(get("/api/v1/town/letters").cookie(owner.access()))
            .andExpect(status().isOk()).andReturn();
        String inboxBody = body(inbox);
        assertThat(inboxBody).contains("\"kind\":\"LONG\"").contains("\"senderKind\":\"CONFIDANT\"")
            .contains("\"senderName\":\"树洞笔友\"").contains("\"unreadCount\":1");

        String letterId = jdbc.queryForObject(
            "select public_id from town_letter where recipient_user_id = ? and kind = 'LONG'",
            String.class, userId);
        mvc.perform(post("/api/v1/town/letters/" + letterId + "/read").cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isOk());
        assertThat(unreadLetterCount(userId, owner)).isZero();

        // 重复标记已读是幂等的，不报错。
        mvc.perform(post("/api/v1/town/letters/" + letterId + "/read").cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isOk());

        // 重跑同一天的夜间 job 不会重复回信（天然幂等：已经存在一条更晚的 IN 记录）。
        confidant.runNightly(userId, today.plusDays(1));
        Integer longLetters = jdbc.queryForObject(
            "select count(*) from town_letter where recipient_user_id = ? and kind = 'LONG'",
            Integer.class, userId);
        assertThat(longLetters).isEqualTo(1);
    }

    @Test
    void writingAnEmptyLetterIsRejectedAndReadingAMissingLetterIs404() throws Exception {
        Session owner = register("town-confidant-edge@example.test");

        mvc.perform(post("/api/v1/town/confidant").cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"   \"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/town/letters/NOPE/read").cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private double affinityToHost(long userId, String hostCode, String recipientRef) {
        if ("PLAYER".equals(recipientRef)) {
            return jdbc.query(
                "select affinity from town_bond where town_user_id = ? and a_kind = 'PLAYER' and b_kind = 'NPC' and b_ref = ?",
                rs -> rs.next() ? rs.getDouble(1) : 0.0, userId, hostCode);
        }
        return jdbc.query(
            "select affinity from town_bond where town_user_id = ? and a_kind = 'NPC' and a_ref = ? and b_kind = 'NPC' and b_ref = ?",
            rs -> rs.next() ? rs.getDouble(1) : 0.0, userId, hostCode, recipientRef);
    }

    private int countEvents(long userId) {
        return jdbc.queryForObject("select count(*) from town_event where town_user_id = ?", Integer.class, userId);
    }

    private int countFacts(long userId) {
        return jdbc.queryForObject("select count(*) from town_fact where town_user_id = ?", Integer.class, userId);
    }

    private int countKnowledge(long userId) {
        return jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ?", Integer.class, userId);
    }

    private int unreadLetterCount(long userId, Session owner) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/town/letters").cookie(owner.access()))
            .andExpect(status().isOk()).andReturn();
        String json = body(result);
        int at = json.indexOf("\"unreadCount\":");
        if (at < 0) {
            return jdbc.queryForObject(
                "select count(*) from town_letter where recipient_user_id = ? and read_at is null",
                Integer.class, userId);
        }
        int start = at + "\"unreadCount\":".length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    private long userIdOf(String email) {
        return jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
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
