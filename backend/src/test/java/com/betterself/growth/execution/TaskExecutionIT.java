package com.betterself.growth.execution;

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
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "app.execution.expiry-delay-ms=3600000")
@AutoConfigureMockMvc
class TaskExecutionIT {

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
    @Autowired ScheduleExpiryJob expiryJob;

    @Test
    void executesIdempotentlyDefersSkipsExpiresAndReverses() throws Exception {
        Session session = register("execution@example.test");
        Fixtures fixtures = fixtures("execution@example.test");

        String completedBody = "{\"eventType\":\"COMPLETED\",\"completionRatio\":1.0}";
        MvcResult completed = event(session, fixtures.completedSchedule(), "complete-once", completedBody, 200)
            .andExpect(jsonPath("$.data.experienceDelta").value(9))
            .andExpect(jsonPath("$.data.roleExperienceDelta").value(9))
            .andExpect(jsonPath("$.data.roleProgress.roleCode").value("STUDENT"))
            .andExpect(jsonPath("$.data.roleProgress.level").value(1))
            .andExpect(jsonPath("$.data.roleProgress.experience").value(9))
            .andExpect(jsonPath("$.data.roleProgress.maxExperience").value(10))
            .andExpect(jsonPath("$.data.scheduleStatus").value("DONE"))
            .andReturn();
        String completedEventId = JsonTestValue.read(completed.getResponse().getContentAsString(), "eventPublicId");

        event(session, fixtures.completedSchedule(), "complete-once", completedBody, 200)
            .andExpect(jsonPath("$.data.eventPublicId").value(completedEventId));
        assertThat(count("select count(*) from task_event where schedule_id = ?", fixtures.completedScheduleId())).isEqualTo(1);
        assertThat(experience(fixtures.userId(), fixtures.dimensionId())).isEqualTo(9);
        assertThat(roleExperience(fixtures.userId(), "STUDENT")).isEqualTo(9);

        event(session, fixtures.completedSchedule(), "complete-once", "{\"eventType\":\"SKIPPED\"}", 409)
            .andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REUSED"));

        Instant deferredStart = Instant.now().plusSeconds(86_400);
        event(session, fixtures.deferredSchedule(), "defer-once",
            "{\"eventType\":\"DEFERRED\",\"deferredStartAt\":\"" + deferredStart + "\"}", 200)
            .andExpect(jsonPath("$.data.scheduleStatus").value("DEFERRED"))
            .andExpect(jsonPath("$.data.deferredSchedulePublicId").isNotEmpty());
        assertThat(count("select count(*) from task_schedule where deferred_from_id = ?", fixtures.deferredScheduleId())).isEqualTo(1);

        event(session, fixtures.skippedSchedule(), "skip-once", "{\"eventType\":\"SKIPPED\"}", 200)
            .andExpect(jsonPath("$.data.scheduleStatus").value("SKIPPED"))
            .andExpect(jsonPath("$.data.experienceDelta").value(0));

        assertThat(expiryJob.expireDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select status from task_schedule where id = ?", String.class, fixtures.expiredScheduleId()
        )).isEqualTo("EXPIRED");
        assertThat(count("select count(*) from task_event where schedule_id = ? and event_type = 'EXPIRED' and experience_delta = 0",
            fixtures.expiredScheduleId())).isEqualTo(1);

        mvc.perform(post("/api/v1/task-schedules/{scheduleId}/events/{eventId}/reverse",
                fixtures.completedSchedule(), completedEventId)
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .header("Idempotency-Key", "reverse-once"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scheduleStatus").value("PLANNED"))
            .andExpect(jsonPath("$.data.experienceDelta").value(-9))
            .andExpect(jsonPath("$.data.roleExperienceDelta").value(-9))
            .andExpect(jsonPath("$.data.roleProgress.level").value(1));
        assertThat(experience(fixtures.userId(), fixtures.dimensionId())).isZero();
        assertThat(roleExperience(fixtures.userId(), "STUDENT")).isZero();
        assertThat(count("select count(*) from task_event where schedule_id = ?", fixtures.completedScheduleId())).isEqualTo(2);

        jdbc.update(
            "update user_role_progress set experience = 9, level = 1 where user_id = ? and role_code = 'STUDENT'",
            fixtures.userId()
        );
        MvcResult crossed = event(session, fixtures.completedSchedule(), "complete-cross-level", completedBody, 200)
            .andExpect(jsonPath("$.data.experienceDelta").value(9))
            .andExpect(jsonPath("$.data.roleExperienceDelta").value(9))
            .andExpect(jsonPath("$.data.roleProgress.experience").value(8))
            .andExpect(jsonPath("$.data.roleProgress.maxExperience").value(15))
            .andExpect(jsonPath("$.data.roleProgress.level").value(2))
            .andReturn();
        String crossedEventId = JsonTestValue.read(crossed.getResponse().getContentAsString(), "eventPublicId");
        mvc.perform(post("/api/v1/task-schedules/{scheduleId}/events/{eventId}/reverse",
                fixtures.completedSchedule(), crossedEventId)
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .header("Idempotency-Key", "reverse-cross-level"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleExperienceDelta").value(-9))
            .andExpect(jsonPath("$.data.roleProgress.level").value(1))
            .andExpect(jsonPath("$.data.roleProgress.experience").value(9));

        jdbc.update(
            "update user_role_progress set experience = 2145, level = 10 where user_id = ? and role_code = 'STUDENT'",
            fixtures.userId()
        );
        MvcResult capped = event(session, fixtures.completedSchedule(), "complete-at-cap", completedBody, 200)
            .andExpect(jsonPath("$.data.experienceDelta").value(9))
            .andExpect(jsonPath("$.data.roleExperienceDelta").value(4))
            .andExpect(jsonPath("$.data.roleProgress.experience").value(999))
            .andExpect(jsonPath("$.data.roleProgress.maxExperience").value(999))
            .andExpect(jsonPath("$.data.roleProgress.level").value(10))
            .andReturn();
        String cappedEventId = JsonTestValue.read(capped.getResponse().getContentAsString(), "eventPublicId");
        mvc.perform(post("/api/v1/task-schedules/{scheduleId}/events/{eventId}/reverse",
                fixtures.completedSchedule(), cappedEventId)
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .header("Idempotency-Key", "reverse-at-cap"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleExperienceDelta").value(-4))
            .andExpect(jsonPath("$.data.roleProgress.experience").value(995));

        long taskId = jdbc.queryForObject(
            "select task_id from task_schedule where id = ?", Long.class, fixtures.completedScheduleId()
        );
        Instant quotaStart = LocalDate.of(2026, 8, 15).atTime(9, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        String[] quotaSchedules = {
            "10000000000000000000000021", "10000000000000000000000022",
            "10000000000000000000000023", "10000000000000000000000024",
            "10000000000000000000000025"
        };
        for (int index = 0; index < quotaSchedules.length; index++) {
            insertSchedule(quotaSchedules[index], fixtures.userId(), taskId, quotaStart.plusSeconds(index * 3600L));
        }
        MvcResult firstQuotaCompletion = event(
            session, quotaSchedules[0], "quota-complete-0", completedBody, 200
        ).andReturn();
        for (int index = 1; index < 4; index++) {
            event(session, quotaSchedules[index], "quota-complete-" + index, completedBody, 200);
        }
        event(session, quotaSchedules[4], "quota-complete-4", completedBody, 200)
            .andExpect(jsonPath("$.data.scheduleStatus").value("DONE"));

        String firstQuotaEventId = JsonTestValue.read(
            firstQuotaCompletion.getResponse().getContentAsString(), "eventPublicId"
        );
        mvc.perform(post("/api/v1/task-schedules/{scheduleId}/events/{eventId}/reverse",
                quotaSchedules[0], firstQuotaEventId)
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .header("Idempotency-Key", "quota-reverse-0"))
            .andExpect(status().isOk());
        event(session, quotaSchedules[0], "quota-complete-after-reverse", completedBody, 200);
        assertThat(count(
            "select count(*) from task_schedule where user_id = ? and local_date = ? and status = 'DONE'",
            fixtures.userId(), Date.valueOf(LocalDate.of(2026, 8, 15))
        )).isEqualTo(5);

        Session other = register("execution-other@example.test");
        event(other, fixtures.deferredChildSchedule(), "ownership-check", "{\"eventType\":\"SKIPPED\"}", 404)
            .andExpect(jsonPath("$.data.code").value("TASK_SCHEDULE_NOT_FOUND"));
    }

    @Test
    void standaloneChecklistPersistsWithoutGoalsAndSharesExecutionAndOwnership() throws Exception {
        Session session = register("checklist@example.test");
        Session other = register("checklist-other@example.test");
        long userId = jdbc.queryForObject("select id from sys_user where email = 'checklist@example.test'", Long.class);
        String firstSchedule = null;
        String firstEvent = null;
        for (int index = 0; index < 6; index++) {
            String body = "{\"title\":\"清单事项 " + index + "\",\"localDate\":\"2026-01-01\"}";
            MvcResult created = mvc.perform(post("/api/v1/tasks/quick")
                    .cookie(session.access(), session.csrf()).header("X-CSRF-Token", session.csrf().getValue())
                    .header("Idempotency-Key", "quick-" + index).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.goalPublicId").doesNotExist()).andReturn();
            String taskId = JsonTestValue.read(created.getResponse().getContentAsString(), "publicId");
            mvc.perform(post("/api/v1/tasks/quick")
                    .cookie(session.access(), session.csrf()).header("X-CSRF-Token", session.csrf().getValue())
                    .header("Idempotency-Key", "quick-" + index).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.publicId").value(taskId));
            String schedule = jdbc.queryForObject("select s.public_id from task_schedule s join user_task t on t.id = s.task_id where t.public_id = ?", String.class, taskId);
            expiryJob.expireDue();
            assertThat(jdbc.queryForObject("select status from task_schedule where public_id = ?", String.class, schedule)).isEqualTo("PLANNED");
            mvc.perform(patch("/api/v1/tasks/" + taskId).cookie(session.access(), session.csrf())
                    .header("X-CSRF-Token", session.csrf().getValue()).contentType("application/json").content("{\"title\":\"已修改\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("已修改"));
            mvc.perform(get("/api/v1/tasks/" + taskId).cookie(other.access())).andExpect(status().isNotFound());
            MvcResult done = event(session, schedule, "quick-done-" + index, "{\"eventType\":\"COMPLETED\"}", 200)
                .andExpect(jsonPath("$.data.experienceDelta").value(0)).andReturn();
            if (index == 0) { firstSchedule = schedule; firstEvent = JsonTestValue.read(done.getResponse().getContentAsString(), "eventPublicId"); }
        }
        assertThat(count("select count(*) from user_task where user_id = ?", userId)).isEqualTo(6);
        assertThat(count("select count(*) from growth_goal where user_id = ?", userId)).isZero();
        mvc.perform(get("/api/v1/task-schedules").cookie(session.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(6));
        mvc.perform(get("/api/v1/task-schedules").cookie(other.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post("/api/v1/task-schedules/{scheduleId}/events/{eventId}/reverse", firstSchedule, firstEvent)
                .cookie(session.access(), session.csrf()).header("X-CSRF-Token", session.csrf().getValue()).header("Idempotency-Key", "quick-undo"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.scheduleStatus").value("PLANNED"));
        event(other, firstSchedule, "quick-other", "{\"eventType\":\"COMPLETED\"}", 404);
        mvc.perform(post("/api/v1/tasks/quick").cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue()).header("Idempotency-Key", "quick-blank")
                .contentType("application/json").content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions event(
        Session session,
        String scheduleId,
        String key,
        String body,
        int expectedStatus
    ) throws Exception {
        return mvc.perform(post("/api/v1/task-schedules/{id}/events", scheduleId)
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
            .andExpect(status().is(expectedStatus));
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"Execution User",
                      "birthDate":"1990-01-01",
                      "timezone":"Asia/Shanghai",
                      "consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}
                    }
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private Fixtures fixtures(String email) {
        long userId = jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
        long dimensionId = jdbc.queryForObject("select id from growth_dimension where is_system = 1 order by id limit 1", Long.class);
        String dimensionCode = jdbc.queryForObject("select code from growth_dimension where id = ?", String.class, dimensionId);
        long goalId = insertAndId(
            "insert into growth_goal (public_id, user_id, dimension_id, title, start_date, end_date) values ('10000000000000000000000001', ?, ?, 'Execution goal', '2026-07-01', '2026-09-01')",
            "select id from growth_goal where public_id = '10000000000000000000000001'", userId, dimensionId
        );
        long planId = insertAndId(
            "insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone) values ('10000000000000000000000002', ?, ?, '2026-07-27', 'Asia/Shanghai')",
            "select id from weekly_plan where public_id = '10000000000000000000000002'", userId, goalId
        );
        jdbc.update(
            "insert into user_task (public_id, user_id, weekly_plan_id, title, estimated_minutes, difficulty, dimension_weights, planned_local_time, active_from, active_until) values ('10000000000000000000000003', ?, ?, 'Execute safely', 30, 3, cast(? as json), '09:00:00', '2026-07-27', '2026-08-02')",
            userId, planId, "{\"" + dimensionCode + "\":10}"
        );
        long taskId = jdbc.queryForObject("select id from user_task where public_id = '10000000000000000000000003'", Long.class);
        Instant future = Instant.now().plusSeconds(172_800);
        Instant past = Instant.now().minusSeconds(172_800);
        long completedId = insertSchedule("10000000000000000000000011", userId, taskId, future);
        long deferredId = insertSchedule("10000000000000000000000012", userId, taskId, future.plusSeconds(3600));
        long skippedId = insertSchedule("10000000000000000000000013", userId, taskId, future.plusSeconds(7200));
        long expiredId = insertSchedule("10000000000000000000000014", userId, taskId, past);
        return new Fixtures(userId, dimensionId, completedId, deferredId, skippedId, expiredId,
            "10000000000000000000000011", "10000000000000000000000012",
            "10000000000000000000000013", "10000000000000000000000014");
    }

    private long insertSchedule(String publicId, long userId, long taskId, Instant start) {
        jdbc.update(
            "insert into task_schedule (public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone) values (?, ?, ?, ?, ?, ?, 'Asia/Shanghai')",
            publicId, userId, taskId, Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)),
            Date.valueOf(start.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())
        );
        return jdbc.queryForObject("select id from task_schedule where public_id = ?", Long.class, publicId);
    }

    private long insertAndId(String insert, String select, Object... args) {
        jdbc.update(insert, args);
        return jdbc.queryForObject(select, Long.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int experience(long userId, long dimensionId) {
        return jdbc.queryForObject(
            "select experience from user_dimension where user_id = ? and dimension_id = ?",
            Integer.class, userId, dimensionId
        );
    }

    private int roleExperience(long userId, String role) {
        return jdbc.queryForObject(
            "select experience from user_role_progress where user_id = ? and role_code = ?",
            Integer.class, userId, role
        );
    }

    private record Session(Cookie access, Cookie csrf) {
    }

    private record Fixtures(
        long userId,
        long dimensionId,
        long completedScheduleId,
        long deferredScheduleId,
        long skippedScheduleId,
        long expiredScheduleId,
        String completedSchedule,
        String deferredSchedule,
        String skippedSchedule,
        String deferredChildSchedule
    ) {
    }

    private static final class JsonTestValue {
        private static String read(String json, String field) {
            String marker = "\"" + field + "\":\"";
            int start = json.indexOf(marker) + marker.length();
            return json.substring(start, json.indexOf('"', start));
        }
    }
}
