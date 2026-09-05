package com.betterself.growth.town;

import com.betterself.growth.shared.id.PublicIdGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TownNpcProvisioner} against a real schema: the 18-archetype roster actually lands in
 * {@code town_npc}, a repeat call is a no-op (in particular {@code settled_at} survives), and
 * the {@code town_bond} CHECK constraint that backs plan §2.5 红线 1 (NPCs never regard the
 * player) is not just a convention — the database itself refuses the row.
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class TownNpcProvisionerIT {

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
    @Autowired TownNpcProvisioner provisioner;
    @Autowired PublicIdGenerator ids;

    @Test
    void populatesAllEighteenArchetypesAndIsIdempotent() throws Exception {
        long userId = register("town-npc-provisioner@example.test");

        provisioner.ensurePopulated(userId);

        List<String> codes = jdbc.queryForList(
            "select npc_code from town_npc where town_user_id = ?", String.class, userId
        );
        assertThat(codes).hasSize(18);
        assertThat(codes).containsExactlyInAnyOrderElementsOf(
            TownNpcCatalog.all().stream().map(TownNpcCatalog.Archetype::code).toList()
        );

        Timestamp settledAtFirstRun = jdbc.queryForObject(
            "select settled_at from town_npc where town_user_id = ? and npc_code = ?",
            Timestamp.class, userId, TownNpcCatalog.GUIDE
        );

        // A second call must not add rows, and must not touch runtime-mutable columns.
        provisioner.ensurePopulated(userId);

        Integer countAfterSecondRun = jdbc.queryForObject(
            "select count(*) from town_npc where town_user_id = ?", Integer.class, userId
        );
        assertThat(countAfterSecondRun).isEqualTo(18);

        Timestamp settledAtSecondRun = jdbc.queryForObject(
            "select settled_at from town_npc where town_user_id = ? and npc_code = ?",
            Timestamp.class, userId, TownNpcCatalog.GUIDE
        );
        assertThat(settledAtSecondRun).isEqualTo(settledAtFirstRun);

        // A PLAYER<->NPC bond was seeded for every archetype, at low starting affinity.
        Integer playerBondCount = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ? and (a_kind = 'PLAYER' or b_kind = 'PLAYER')",
            Integer.class, userId
        );
        assertThat(playerBondCount).isEqualTo(36); // 18 archetypes x 2 directions

        Double playerToGuideAffinity = jdbc.queryForObject(
            "select affinity from town_bond where town_user_id = ? and a_kind = 'PLAYER' and b_kind = 'NPC' and b_ref = ?",
            Double.class, userId, TownNpcCatalog.GUIDE
        );
        assertThat(playerToGuideAffinity).isBetween(0.0, 0.2);

        Integer playerRegardCount = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ? and (a_kind = 'PLAYER' or b_kind = 'PLAYER') and (regard <> 0 or regard_kind is not null)",
            Integer.class, userId
        );
        assertThat(playerRegardCount).isZero();

        // A sparse NPC<->NPC web exists, and at least one hidden regard edge among layer-2/3 NPCs.
        Integer npcNpcBondCount = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC'",
            Integer.class, userId
        );
        assertThat(npcNpcBondCount).isGreaterThan(0);

        Integer regardEdgeCount = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC' and regard_kind is not null",
            Integer.class, userId
        );
        assertThat(regardEdgeCount).isGreaterThan(0);
    }

    @Test
    void databaseRejectsAPlayerInvolvingRegardRow() throws Exception {
        long userId = register("town-npc-provisioner-check@example.test");
        provisioner.ensurePopulated(userId);
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbc.update(
            """
                insert into town_bond
                    (public_id, town_user_id, a_kind, a_ref, b_kind, b_ref, affinity, resonance, meet_count,
                     last_met_at, regard, regard_kind, created_at, updated_at)
                values (?, ?, 'PLAYER', 'PLAYER', 'NPC', ?, 0.1, 0, 0, null, 0.9, 'CRUSH', ?, ?)
                """,
            ids.next(), userId, TownNpcCatalog.POSTMAN, now, now
        )).isInstanceOf(DataAccessException.class)
            // MySQL 的 CHECK 违例是 error 3819 / SQLSTATE HY000，Spring 不会把它翻成
            // DataIntegrityViolationException，所以断言落在"被哪条约束拦下的"上——这本来
            // 也是更该断言的东西：拦住它的必须是红线那条，而不是碰巧撞了别的约束。
            .hasMessageContaining("chk_town_bond_no_player_regard");
    }

    private long register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        Cookie access = result.getResponse().getCookie("access_token");
        assertThat(access).isNotNull();
        return jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email.toLowerCase());
    }

    @Test
    void aProvisionedTownIsReadOnlyOnEveryLaterCall() throws Exception {
        long userId = register("town-npc-provisioner-noop@example.test");
        provisioner.ensurePopulated(userId);

        // ensurePopulated 挂在每次进小镇的路径上（GET /town/npcs 也调它），所以"已经建好之后
        // 不再写"不是优化而是正确性：每次都重发那批 insert 会在 town_bond 上占行锁，夜间 job
        // 和 HTTP 请求撞上时就会互相等。
        Timestamp before = jdbc.queryForObject(
            "select max(updated_at) from town_bond where town_user_id = ?", Timestamp.class, userId);
        Integer bondsBefore = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ?", Integer.class, userId);

        provisioner.ensurePopulated(userId);
        provisioner.ensurePopulated(userId);

        Timestamp after = jdbc.queryForObject(
            "select max(updated_at) from town_bond where town_user_id = ?", Timestamp.class, userId);
        Integer bondsAfter = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ?", Integer.class, userId);

        assertThat(bondsAfter).isEqualTo(bondsBefore);
        assertThat(after).isEqualTo(before);
    }
}
