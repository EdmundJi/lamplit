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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4-6（迁徙）与 M4-7（路过的旅人）的端到端验收。
 *
 * <p>迁徙那部分的"冷却期内不会二次搬家"已经在 {@link TownMigrationServiceTest} 里用纯函数钉死了
 * （那才是真正驱动这个判断的逻辑）；这里只验证跑通真实数据库之后，最终状态是不是对的：
 * 两镇各自还是 18 人、搬家记录成对出现、搬完的槽位重新进入冷却。
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
class TownMigrationIT {

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
    @Autowired TownMigrationService migration;
    @Autowired TownDailyProduction daily;

    @Test
    void mutualFriendsSwapABackgroundResidentAndBothTownsStayAtEighteen() throws Exception {
        register("town-migrate-a@example.test");
        register("town-migrate-b@example.test");
        long userIdA = userIdOf("town-migrate-a@example.test");
        long userIdB = userIdOf("town-migrate-b@example.test");

        society.roster(userIdA);
        society.roster(userIdB);
        befriend(userIdA, userIdB);

        // 起点镇各自攒一条"节奏印象"，好让搬家时有东西可以带走（carryTale 用的就是这条）。
        LocalDate today = LocalDate.now();
        society.runNightly(userIdA, today);
        society.runNightly(userIdB, today);

        // 把两镇全部第三层背景居民都解禁冷却，让"谁会搬"完全交给概率而不是被冷却卡住。
        backdateLayerThreeSettledAt(userIdA, LocalDateTime.now().minusDays(30));
        backdateLayerThreeSettledAt(userIdB, LocalDateTime.now().minusDays(30));

        int migrationsBefore = countMigrations();
        boolean migrated = false;
        for (int day = 0; day < 60 && !migrated; day++) {
            migration.runNightly(userIdA, today.plusDays(day));
            migration.runNightly(userIdB, today.plusDays(day));
            migrated = countMigrations() > migrationsBefore;
        }
        assertThat(migrated).as("互为好友 + 都不在冷却里，60 天内应该会触发一次迁徙").isTrue();

        // 两镇各仍是 18 人——交换制，人数不变。
        assertThat(countNpcs(userIdA)).isEqualTo(18);
        assertThat(countNpcs(userIdB)).isEqualTo(18);

        // 迁徙记录成对出现：一条 A→B，一条 B→A，互相 paired_with。
        var fromA = jdbc.queryForList(
            "select npc_code, paired_with_npc_code from town_migration where from_user_id = ? and to_user_id = ?",
            userIdA, userIdB);
        var fromB = jdbc.queryForList(
            "select npc_code, paired_with_npc_code from town_migration where from_user_id = ? and to_user_id = ?",
            userIdB, userIdA);
        assertThat(fromA).hasSize(1);
        assertThat(fromB).hasSize(1);
        String codeFromA = (String) fromA.get(0).get("npc_code");
        String codeFromB = (String) fromB.get(0).get("npc_code");
        assertThat(fromA.get(0).get("paired_with_npc_code")).isEqualTo(codeFromB);
        assertThat(fromB.get(0).get("paired_with_npc_code")).isEqualTo(codeFromA);

        // 搬完的槽位重新进入冷却：settled_at 被重置为刚刚，而不是三十天前那个旧值。注意查的是
        // "各自镇里那个刚换了新住户的槽位"——即 A 镇里 codeFromA 这个槽位（现在住着从 B 来的人），
        // B 镇里 codeFromB 那个槽位，而不是把对方的代号拿到自己镇里去查（A 镇根本没有独立的
        // codeFromB 这个"另一个人"——两镇的 18 个 code 从名册上看本就是同一套）。
        // 应用进程的 Clock 是 UTC（AppClockConfig），settled_at 落库时用的是那个时区的挂钟时间；
        // 这里用同一个基准比较，避免测试机本地时区偏移把"刚刚"误判成"很久以前"。
        LocalDateTime nowUtc = LocalDateTime.now(java.time.Clock.systemUTC());
        LocalDateTime settledAOfIncoming = settledAtOf(userIdA, codeFromA);
        LocalDateTime settledBOfIncoming = settledAtOf(userIdB, codeFromB);
        assertThat(settledAOfIncoming).isAfter(nowUtc.minusMinutes(5));
        assertThat(settledBOfIncoming).isAfter(nowUtc.minusMinutes(5));

        // 至少一边带来了跨镇传闻——carryTale 写的是 subject_kind='NPC' 的 CROSS_TOWN_TALE。
        Integer crossTownTales = jdbc.queryForObject(
            "select count(*) from town_fact where kind = 'CROSS_TOWN_TALE' and town_user_id in (?, ?)",
            Integer.class, userIdA, userIdB);
        assertThat(crossTownTales).isGreaterThan(0);
        // The swapped identity includes its rhythm; today's persisted plan remains frozen.
        String expectedRhythmB=jdbc.queryForObject("select rhythm from town_npc where town_user_id=? and npc_code=?",String.class,userIdB,codeFromB);
        assertThat(expectedRhythmB).isNotBlank();
        // Revoking friendship closes every copied tale's read/relay path immediately.
        var crossIds=jdbc.queryForList("select public_id from town_fact where kind='CROSS_TOWN_TALE' and town_user_id in (?,?)",String.class,userIdA,userIdB);
        jdbc.update("update town_npc_knowledge set salience=0 where town_user_id in (?,?)",userIdA,userIdB);
        jdbc.update("""
            update town_npc_knowledge k join town_fact f on f.id=k.fact_id set k.salience=1
            where k.town_user_id in (?,?) and f.kind='CROSS_TOWN_TALE'
            """,userIdA,userIdB);
        assertThat(java.util.stream.Stream.concat(society.roster(userIdA).npcs().stream(),society.roster(userIdB).npcs().stream())
            .flatMap(n->n.talkingPoints().stream()).map(TownSocietyService.TalkingPoint::factId).toList()).containsAnyElementsOf(crossIds);
        jdbc.update("update town_fact set occurred_on=? where kind='CROSS_TOWN_TALE' and town_user_id in (?,?)",
            java.sql.Date.valueOf(LocalDate.now().minusDays(15)),userIdA,userIdB);
        assertThat(java.util.stream.Stream.concat(society.roster(userIdA).npcs().stream(),society.roster(userIdB).npcs().stream())
            .flatMap(n->n.talkingPoints().stream()).map(TownSocietyService.TalkingPoint::factId).toList()).doesNotContainAnyElementsOf(crossIds);
        jdbc.update("update town_fact set occurred_on=? where kind='CROSS_TOWN_TALE' and town_user_id in (?,?)",
            java.sql.Date.valueOf(LocalDate.now()),userIdA,userIdB);
        jdbc.update("delete from friend_relationship where requester_user_id in (?,?) and addressee_user_id in (?,?)",userIdA,userIdB,userIdA,userIdB);
        assertThat(java.util.stream.Stream.concat(society.roster(userIdA).npcs().stream(),society.roster(userIdB).npcs().stream())
            .flatMap(n->n.talkingPoints().stream()).map(TownSocietyService.TalkingPoint::factId).toList()).doesNotContainAnyElementsOf(crossIds);

    }

    @Test
    void friendlessUserGetsATravelerWhoLeavesAfterAFewDaysAndAFriendedUserDoesNot() throws Exception {
        register("town-lonely@example.test");
        register("town-not-lonely@example.test");
        register("town-not-lonely-friend@example.test");
        long lonelyId = userIdOf("town-lonely@example.test");
        long friendedId = userIdOf("town-not-lonely@example.test");
        long friendOfFriendedId = userIdOf("town-not-lonely-friend@example.test");

        society.roster(lonelyId);
        society.roster(friendedId);
        society.roster(friendOfFriendedId);
        befriend(friendedId, friendOfFriendedId);

        LocalDate today = LocalDate.now();
        migration.runNightly(lonelyId, today);
        migration.runNightly(friendedId, today);

        assertThat(travelerExists(lonelyId)).as("孤身用户应该收到一位路过的旅人").isTrue();
        assertThat(travelerExists(friendedId)).as("有朋友的用户不需要旅人替代").isFalse();

        // 旅人待了几天之后应该离开——把它的入住时间往回拨，模拟"已经住了好几天"。
        jdbc.update("update town_npc set settled_at = ? where town_user_id = ? and npc_code = 'TRAVELER'",
            Timestamp.valueOf(LocalDateTime.now().minusDays(10)), lonelyId);
        migration.runNightly(lonelyId, today.plusDays(10));

        assertThat(travelerExists(lonelyId)).as("停留超过几天后旅人应该离开").isFalse();
        migration.runNightly(lonelyId,today.plusDays(10));
        assertThat(travelerExists(lonelyId)).as("重跑离开当天不能马上再生一个旅人").isFalse();
        Integer leftoverKnowledge = jdbc.queryForObject(
            "select count(*) from town_npc_knowledge where town_user_id = ? and npc_code = 'TRAVELER'",
            Integer.class, lonelyId);
        assertThat(leftoverKnowledge).isZero();
    }

    @Test
    void concurrentFriendJobsCommitOneAtomicPairAndRetriesDoNotSwapAgain() throws Exception {
        register("town-concurrent-a@example.test");
        register("town-concurrent-b@example.test");
        long a=userIdOf("town-concurrent-a@example.test"),b=userIdOf("town-concurrent-b@example.test");
        society.roster(a);society.roster(b);befriend(a,b);
        backdateLayerThreeSettledAt(a,LocalDateTime.now().minusDays(30));
        backdateLayerThreeSettledAt(b,LocalDateTime.now().minusDays(30));
        int before=countMigrations();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for(int i=0;i<60 && countMigrations()==before;i++) {
                LocalDate date=LocalDate.now().plusDays(i);
                var first=executor.submit(() -> migration.runNightly(a,date));
                var second=executor.submit(() -> migration.runNightly(b,date));
                first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                second.get(15,java.util.concurrent.TimeUnit.SECONDS);
                migration.runNightly(a,date);migration.runNightly(b,date);
            }
        }
        assertThat(countMigrations()-before).isEqualTo(2);
        assertThat(countNpcs(a)).isEqualTo(18);
        assertThat(countNpcs(b)).isEqualTo(18);
    }

    @Test
    void aCrossTimezoneExchangeRecordsTheArrivalDateOfEachDestination() throws Exception {
        register("town-zone-a@example.test");register("town-zone-b@example.test");
        long a=userIdOf("town-zone-a@example.test"),b=userIdOf("town-zone-b@example.test");
        jdbc.update("update sys_user set timezone='Pacific/Honolulu' where id=?",b);
        society.roster(a);society.roster(b);befriend(a,b);
        backdateLayerThreeSettledAt(a,LocalDateTime.now().minusDays(30));
        backdateLayerThreeSettledAt(b,LocalDateTime.now().minusDays(30));
        int before=countMigrations();
        LocalDate movedOn=null;
        for(int i=0;i<60 && countMigrations()==before;i++) {
            movedOn=daily.today(a).plusDays(i);
            migration.runNightly(a,movedOn);
        }
        assertThat(countMigrations()-before).isEqualTo(2);
        assertThat(jdbc.queryForObject("select local_date from town_migration where to_user_id=?",LocalDate.class,a)).isEqualTo(movedOn);
        assertThat(jdbc.queryForObject("select local_date from town_migration where to_user_id=?",LocalDate.class,b))
            .isEqualTo(daily.dateForPeer(a,b,movedOn));
    }

    // ---------------------------------------------------------------- helpers

    private boolean travelerExists(long userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from town_npc where town_user_id = ? and npc_code = 'TRAVELER'", Integer.class, userId);
        return count != null && count > 0;
    }

    private LocalDateTime settledAtOf(long userId, String code) {
        return jdbc.queryForObject(
            "select settled_at from town_npc where town_user_id = ? and npc_code = ?",
            Timestamp.class, userId, code
        ).toLocalDateTime();
    }

    private int countNpcs(long userId) {
        return jdbc.queryForObject("select count(*) from town_npc where town_user_id = ?", Integer.class, userId);
    }

    private int countMigrations() {
        return jdbc.queryForObject("select count(*) from town_migration", Integer.class);
    }

    private void backdateLayerThreeSettledAt(long userId, LocalDateTime settledAt) {
        jdbc.update("update town_npc set settled_at = ? where town_user_id = ? and layer = 3",
            Timestamp.valueOf(settledAt), userId);
    }

    private void befriend(long userIdA, long userIdB) {
        String pairKey = Math.min(userIdA, userIdB) + ":" + Math.max(userIdA, userIdB);
        jdbc.update(
            """
                insert into friend_relationship (public_id, pair_key, requester_user_id, addressee_user_id, status)
                values (?, ?, ?, ?, 'ACCEPTED')
                """,
            UUID.randomUUID().toString().replace("-", "").substring(0, 26), pairKey, userIdA, userIdB
        );
    }

    private long userIdOf(String email) {
        return jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
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
