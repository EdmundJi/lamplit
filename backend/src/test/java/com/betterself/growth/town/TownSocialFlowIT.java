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

    @Autowired TownNpcProvisioner provisioner;
    @Autowired TownMoodService moods;
    @Autowired TownNoteService notes;
    @Autowired TownDailyProduction daily;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.betterself.growth.shared.id.PublicIdGenerator ids;
    @Autowired TownLetterService letterService;
    @Autowired java.time.Clock clock;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void unreadBadgeCountsOnlyDeliveredUnreadLettersAndNeverReturnsBodies() throws Exception {
        Session owner=register("town-unread-count@example.test");
        Session other=register("town-unread-other@example.test");
        long id=userIdOf("town-unread-count@example.test");
        var now=java.time.LocalDateTime.now(clock);
        letterService.deliver(id,"NPC","KE_YUN","NOTE","已经送到的小笺",now.minusMinutes(1));
        letterService.deliver(id,"CONFIDANT",null,"LONG","已读私密正文",now.minusMinutes(1));
        String readId=jdbc.queryForObject("select public_id from town_letter where recipient_user_id=? and kind='LONG'",String.class,id);
        letterService.markRead(id,readId);
        letterService.deliver(id,"CONFIDANT",null,"LONG","隔天才会送到的私密正文",now.plusDays(1));
        var response=mvc.perform(get("/api/v1/town/letters/unread").cookie(owner.access()))
            .andExpect(status().isOk()).andReturn();
        var data=mapper.readTree(body(response)).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.path("unreadCount").asInt()).isEqualTo(1);
        assertThat(body(response)).doesNotContain("正文","sender","letters","body");
        var otherResponse=mvc.perform(get("/api/v1/town/letters/unread").cookie(other.access()))
            .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(body(otherResponse)).path("data").path("unreadCount").asInt()).isZero();
        mvc.perform(get("/api/v1/town/letters/unread")).andExpect(status().isUnauthorized());
        String noteId=jdbc.queryForObject("select public_id from town_letter where recipient_user_id=? and kind='NOTE'",String.class,id);
        letterService.markRead(id,noteId);
        assertThat(letterService.unreadCount(id).unreadCount()).isZero();
    }

    @Test
    void dailyMoodPlanAndEventDecisionSurviveConcurrentRetriesAndAffinityChanges() throws Exception {
        Session owner = register("town-daily-production@example.test");
        long id = userIdOf("town-daily-production@example.test");
        var before = society.roster(id);
        LocalDate date = daily.today(id);
        assertThat(jdbc.queryForObject("select count(*) from town_npc_mood where town_user_id=? and local_date=?",
            Integer.class,id,java.sql.Date.valueOf(date))).isEqualTo(18);
        assertThat(before.npcs().stream().map(n -> n.mood().valence()).distinct().count()).isGreaterThan(5);
        jdbc.update("update town_bond set affinity=0.99 where town_user_id=? and a_kind='PLAYER'",id);
        jdbc.update("""
            update town_npc set rhythm=cast('{"wakeMinute":420,"sleepMinute":1320,"errands":[]}' as json) where town_user_id=?
            """,id);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> events.runNightly(id,date));
            var b = executor.submit(() -> events.runNightly(id,date));
            a.get(); b.get();
        }
        var after = society.roster(id);
        assertThat(after.npcs().stream().map(TownSocietyService.NpcView::dayPlan).toList())
            .isEqualTo(before.npcs().stream().map(TownSocietyService.NpcView::dayPlan).toList());
        assertThat(after.npcs().stream().map(TownSocietyService.NpcView::mood).toList())
            .isEqualTo(before.npcs().stream().map(TownSocietyService.NpcView::mood).toList());
        assertThat(jdbc.queryForObject("select count(*) from town_daily_production where town_user_id=? and local_date=? and stage='EVENT'",
            Integer.class,id,java.sql.Date.valueOf(date))).isEqualTo(1);
        String json = body(mvc.perform(get("/api/v1/town/npcs").cookie(owner.access())).andExpect(status().isOk()).andReturn());
        assertThat(json).doesNotContain("regard", "CRUSH", "ADMIRE", "RIVAL");
    }

    @Test
    void eventsEndpointIsOwnerScopedAndIncludesExplicitPersonalActivityState() throws Exception {
        Session owner = register("town-events-contract@example.test");
        Session other = register("town-events-other@example.test");
        long id = userIdOf("town-events-contract@example.test");
        society.roster(id);
        LocalDate date = daily.today(id);
        jdbc.update("delete from town_invitation where town_user_id=?",id);
        jdbc.update("delete from town_event where town_user_id=?",id);
        jdbc.update("""
            insert into town_event(public_id,town_user_id,host_npc_code,kind,venue,dimension,starts_at,ends_at)
            values (?,?,'KE_YUN','GATHERING','plaza','KNOWLEDGE',?,?)
            """,ids.next(),id,java.sql.Timestamp.valueOf(date.atTime(18,0)),java.sql.Timestamp.valueOf(date.atTime(20,0)));
        var result = mvc.perform(get("/api/v1/town/events").cookie(owner.access())).andExpect(status().isOk()).andReturn();
        var event = mapper.readTree(body(result)).path("data").get(0);
        var names = new java.util.HashSet<String>();
        event.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("publicId","kind","venue","hostName","startsAt","endsAt","dimension","phase","response","attendedAt","serverTime");
        assertThat(event.path("startsAt").asText()).endsWith("+08:00");
        assertThat(body(mvc.perform(get("/api/v1/town/events").cookie(other.access())).andExpect(status().isOk()).andReturn()))
            .doesNotContain(event.path("publicId").asText());
        mvc.perform(get("/api/v1/town/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void noteProducerIsSparseIdempotentAndDoesNotReadSecrets() throws Exception {
        register("town-notes-production@example.test");
        long id = userIdOf("town-notes-production@example.test");
        LocalDate date = daily.today(id);
        for (int i=0;i<6;i++) {
            LocalDate day=date.plusDays(i);
            moods.ensureDay(id,day);
            notes.runNightly(id,day);
            notes.runNightly(id,day);
        }
        var bodies=jdbc.queryForList("select body from town_letter where recipient_user_id=? and kind='NOTE'",String.class,id);
        assertThat(bodies).hasSize(2).allSatisfy(body -> assertThat(body).contains("不用回信").doesNotContain("任务", "暗恋"));
        assertThat(countFacts(id)).isZero();
        assertThat(countKnowledge(id)).isZero();
    }

    @Test
    void confidantCorrelatesEachLetterAndConcurrentRepliesNeverHoldAnLlmTransaction() throws Exception {
        register("town-confidant-atomic@example.test");
        long id = userIdOf("town-confidant-atomic@example.test");
        confidant.write(id,"只有树洞知道的秘密甲");
        confidant.write(id,"只有树洞知道的秘密乙");
        LocalDate date=daily.today(id);
        jdbc.update("update town_confidant_thread set written_at=? where user_id=?",
            java.sql.Timestamp.from(date.minusDays(1).atStartOfDay(daily.zone(id)).toInstant()),id);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        TownConfidantReplyGenerator generator = requests -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(requests).hasSize(2);
            try { barrier.await(10,java.util.concurrent.TimeUnit.SECONDS); }
            catch(Exception ex) { throw new RuntimeException(ex); }
            return requests.stream().map(r -> new TownConfidantReplyGenerator.Reply(r.key(),"我读到了，愿意陪你安静地坐一会儿。")).toList();
        };
        var service = new TownConfidantService(jdbc,ids,clock,letterService,generator,tx,daily);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> service.runNightly(id,date));
            var b=executor.submit(() -> service.runNightly(id,date));
            a.get();b.get();
        }
        assertThat(jdbc.queryForObject("select count(distinct reply_to_id) from town_confidant_thread where user_id=? and direction='IN'",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from town_letter where recipient_user_id=? and kind='LONG'",Integer.class,id)).isEqualTo(2);
        assertThat(countFacts(id)).isZero();
        assertThat(countKnowledge(id)).isZero();
        // Equal timestamps do not turn an unrelated response into an answer to a new letter.
        confidant.write(id,"第三封");
        assertThat(jdbc.queryForObject("select count(*) from town_confidant_thread where user_id=? and direction='OUT' and answered_at is null",Integer.class,id)).isEqualTo(1);
    }

    @Test
    void explicitTellUsesOnlyWhitelistedBlurredFactsAndKeepsGuidePrivate() throws Exception {
        Session owner=register("town-tell-production@example.test");
        long id=userIdOf("town-tell-production@example.test");
        for(int i=0;i<2;i++) mvc.perform(post("/api/v1/town/npc/KE_YUN/tell").cookie(owner.access(),owner.csrf())
            .header("X-CSRF-Token",owner.csrf().getValue()).contentType("application/json").content("{\"kind\":\"RHYTHM\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from town_npc_knowledge where town_user_id=? and npc_code='KE_YUN' and learned_from='TOLD'",Integer.class,id)).isEqualTo(1);
        society.tell(id,"GUIDE","LEVEL_BUCKET");
        assertThat(society.talkingPoints(id,"GUIDE").points()).isEmpty();
        mvc.perform(post("/api/v1/town/npc/TOWNIE_01/tell").cookie(owner.access(),owner.csrf())
            .header("X-CSRF-Token",owner.csrf().getValue()).contentType("application/json").content("{\"kind\":\"RHYTHM\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/town/npc/KE_YUN/tell").cookie(owner.access(),owner.csrf())
            .header("X-CSRF-Token",owner.csrf().getValue()).contentType("application/json").content("{\"kind\":\"我的秘密任务123\"}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("select payload from town_fact where town_user_id=?",String.class,id))
            .allSatisfy(text -> assertThat(text).doesNotContain("123", "秘密任务"));
    }

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

        // Delivery is deliberately staggered: move the mailbox clock fixture past delivery,
        // while leaving invitation timestamps untouched for the affinity ordering assertion.
        jdbc.update("update town_letter set deliver_at=? where recipient_user_id=? and kind='INVITE'",
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(clock).minusSeconds(1)),userId);
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
        provisioner.ensurePopulated(userId);

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

        // Delivery is deliberately staggered: move the mailbox clock fixture past delivery,
        // while leaving invitation timestamps untouched for the affinity ordering assertion.
        jdbc.update("update town_letter set deliver_at=? where recipient_user_id=? and kind='INVITE'",
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(clock).minusSeconds(1)),userId);
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

    @Test
    void structuredInvitationAndEventChoicesAreOwnedIdempotentAndNeverAwardAttendanceFromRsvp() throws Exception {
        Session owner = register("event-experience-owner@example.test");
        Session other = register("event-experience-other@example.test");
        long userId = userIdOf("event-experience-owner@example.test");
        provisioner.ensurePopulated(userId);
        String eventId = ids.next();
        var now = java.time.LocalDateTime.ofInstant(clock.instant(), daily.zone(userId));
        jdbc.update("""
            insert into town_event(public_id,town_user_id,host_npc_code,kind,venue,starts_at,ends_at)
            values (?,?,'KE_YUN','READING_CIRCLE','plaza',?,?)
            """, eventId,userId,java.sql.Timestamp.valueOf(now.plusHours(1)),java.sql.Timestamp.valueOf(now.plusHours(2)));
        letterService.deliver(userId,"NPC","KE_YUN","INVITE","邀请正文不用于猜测活动",java.time.LocalDateTime.now(clock).minusMinutes(1),eventId);
        assertThat(letterService.inbox(userId).letters()).anySatisfy(letter -> assertThat(letter.eventPublicId()).isEqualTo(eventId));
        mvc.perform(get("/api/v1/town/events/" + eventId).cookie(other.access())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/town/events/" + eventId + "/response").cookie(other.access(),other.csrf())
            .header("X-CSRF-Token",other.csrf().getValue()).contentType("application/json").content("{\"response\":\"GOING\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/town/events/" + eventId + "/response").cookie(owner.access(),owner.csrf())
            .header("X-CSRF-Token",owner.csrf().getValue()).contentType("application/json").content("{\"response\":\"GOING\"}"))
            .andExpect(status().isOk());
        events.respond(userId,eventId,"GOING");
        assertThat(events.detail(userId,eventId).attendedAt()).isNull();
        assertThat(events.detail(userId,eventId).phase()).isEqualTo("UPCOMING");
        mvc.perform(post("/api/v1/town/events/" + eventId + "/memory").cookie(owner.access(),owner.csrf())
            .header("X-CSRF-Token",owner.csrf().getValue())).andExpect(status().isConflict());
        jdbc.update("update town_event set starts_at=?,ends_at=? where public_id=?",
            java.sql.Timestamp.valueOf(now.minusMinutes(10)),java.sql.Timestamp.valueOf(now.plusMinutes(30)),eventId);
        int factsBefore = countFacts(userId);
        var first = events.remember(userId,eventId);
        assertThat(first.attendedAt()).isNotNull();
        assertThat(events.remember(userId,eventId).attendedAt()).isEqualTo(first.attendedAt());
        assertThat(countFacts(userId)).isEqualTo(factsBefore);
        jdbc.update("update town_event set cancelled_at=? where public_id=?",java.sql.Timestamp.from(clock.instant()),eventId);
        assertThat(events.detail(userId,eventId).phase()).isEqualTo("CANCELLED");
        mvc.perform(post("/api/v1/town/events/" + eventId + "/response").cookie(owner.access(),owner.csrf())
            .header("X-CSRF-Token",owner.csrf().getValue()).contentType("application/json").content("{\"response\":\"SKIPPED\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void pastEventsRemainReadableWithoutBeingMixedIntoTodaysWorld() throws Exception {
        Session owner = register("event-experience-history@example.test");
        long userId = userIdOf("event-experience-history@example.test");
        provisioner.ensurePopulated(userId);
        String eventId = ids.next();
        var past = daily.today(userId).minusDays(2).atTime(18,0);
        jdbc.update("""
            insert into town_event(public_id,town_user_id,host_npc_code,kind,venue,starts_at,ends_at)
            values (?,?,'KE_YUN','PARK_WALK','park',?,?)
            """,eventId,userId,java.sql.Timestamp.valueOf(past),java.sql.Timestamp.valueOf(past.plusHours(2)));
        assertThat(events.recent(userId)).anySatisfy(event -> {
            assertThat(event.publicId()).isEqualTo(eventId);
            assertThat(event.phase()).isEqualTo("ENDED");
            assertThat(event.attendedAt()).isNull();
        });
        assertThat(events.today(userId)).noneSatisfy(event -> assertThat(event.publicId()).isEqualTo(eventId));
        mvc.perform(get("/api/v1/town/events/history").cookie(owner.access())).andExpect(status().isOk());
    }

    @Test
    void lateFirstVisitDoesNotManufactureAnExpiredInvitation() throws Exception {
        register("event-late-arrival@example.test");
        long userId = userIdOf("event-late-arrival@example.test");
        provisioner.ensurePopulated(userId);
        var date = daily.today(userId);
        var lateClock = java.time.Clock.fixed(date.atTime(23,0).atZone(daily.zone(userId)).toInstant(), clock.getZone());
        var lateEvents = new TownEventService(jdbc,tx,ids,letterService,lateClock,moods,daily);
        lateEvents.runNightly(userId,date);
        assertThat(countEvents(userId)).isZero();
        assertThat(letterService.inbox(userId).letters()).noneSatisfy(letter -> assertThat(letter.kind()).isEqualTo("INVITE"));
    }

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
