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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class TownFlowIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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

    @Test
    void showsTheTownStreamsNpcChatAndGeneratesAReflection() throws Exception {
        Session owner = register("town-owner@example.test");
        Fixture fixture = fixture(owner.email());

        MvcResult townResult = mvc.perform(get("/api/v1/town").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.soloGrowth").value(false))
            .andExpect(jsonPath("$.data.residents.length()").value(1))
            .andExpect(jsonPath("$.data.residents[0].self").value(true))
            .andExpect(jsonPath("$.data.residents[0].displayName").value("Town Owner"))
            .andExpect(jsonPath("$.data.residents[0].timezone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$.data.residents[0].schedules.length()").value(1))
            .andExpect(jsonPath("$.data.residents[0].schedules[0].publicId").value(fixture.scheduleId()))
            .andExpect(jsonPath("$.data.residents[0].schedules[0].title").value("背单词 20 分钟"))
            .andExpect(jsonPath("$.data.residents[0].schedules[0].status").value("PLANNED"))
            .andReturn();
        assertThat(townResult.getResponse().getContentAsString()).contains("\"localDate\"");

        mvc.perform(get("/api/v1/town/npc/GUIDE/messages").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));

        MvcResult started = mvc.perform(post("/api/v1/town/npc/GUIDE/chat:stream")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"今天要做点什么好呢\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
        started.getAsyncResult(15_000); // blocks until the virtual-thread stream calls emitter.complete()
        MvcResult stream = mvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();
        String sse = stream.getResponse().getContentAsString();
        assertThat(sse.indexOf("event:meta")).isGreaterThanOrEqualTo(0);
        assertThat(sse.indexOf("event:meta")).isLessThan(sse.indexOf("event:delta"));
        assertThat(sse.indexOf("event:delta")).isLessThan(sse.indexOf("event:done"));
        assertThat(sse).contains("\"npc\":\"GUIDE\"");

        mvc.perform(get("/api/v1/town/npc/GUIDE/messages").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].role").value("USER"))
            .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
            .andExpect(jsonPath("$.data[1].status").value("COMPLETED"));

        mvc.perform(get("/api/v1/town/reflection/latest").cookie(owner.access()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("TOWN_REFLECTION_NOT_FOUND"));

        mvc.perform(post("/api/v1/town/reflection/generate")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.greeting").isNotEmpty())
            .andExpect(jsonPath("$.data.insights.length()").value(2));

        mvc.perform(get("/api/v1/town/reflection/latest").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.localDate").value(LocalDate.now(ZONE).toString()))
            .andExpect(jsonPath("$.data.insights.length()").value(2));
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(email, result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    /** One goal → plan → task → schedule, planted at "now" so it shows up in today's town view. */
    private Fixture fixture(String email) {
        long userId = jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
        long dimensionId = jdbc.queryForObject("select id from growth_dimension where code = 'KNOWLEDGE'", Long.class);
        String goalId = "80000000000000000000000001";
        String planId = "80000000000000000000000002";
        String taskId = "80000000000000000000000003";
        String scheduleId = "80000000000000000000000004";
        jdbc.update(
            "insert into growth_goal (public_id, user_id, dimension_id, title, start_date, end_date) values (?, ?, ?, '记忆单词', '2026-07-01', '2026-12-01')",
            goalId, userId, dimensionId
        );
        long goalInternalId = jdbc.queryForObject("select id from growth_goal where public_id = ?", Long.class, goalId);
        jdbc.update(
            "insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone) values (?, ?, ?, ?, 'Asia/Shanghai')",
            planId, userId, goalInternalId, Date.valueOf(LocalDate.now(ZONE).with(java.time.DayOfWeek.MONDAY))
        );
        long planInternalId = jdbc.queryForObject("select id from weekly_plan where public_id = ?", Long.class, planId);
        jdbc.update(
            """
                insert into user_task (public_id, user_id, weekly_plan_id, title, estimated_minutes, difficulty, dimension_weights, planned_local_time, active_from, active_until)
                values (?, ?, ?, '背单词 20 分钟', 20, 2, cast(? as json), '11:00:00', '2026-07-01', '2026-12-01')
                """,
            taskId, userId, planInternalId, "{\"KNOWLEDGE\":10}"
        );
        long taskInternalId = jdbc.queryForObject("select id from user_task where public_id = ?", Long.class, taskId);
        Instant start = Instant.now();
        jdbc.update(
            "insert into task_schedule (public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone) values (?, ?, ?, ?, ?, ?, 'Asia/Shanghai')",
            scheduleId, userId, taskInternalId, Timestamp.from(start), Timestamp.from(start.plusSeconds(1200)),
            Date.valueOf(LocalDate.now(ZONE))
        );
        return new Fixture(userId, scheduleId);
    }

    private record Session(String email, Cookie access, Cookie csrf) {
    }

    private record Fixture(long userId, String scheduleId) {
    }
}
