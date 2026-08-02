package com.betterself.growth.ai;

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
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class AiFlowIT {

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
    void streamsSafelyAndAdoptsSuggestionsIdempotently() throws Exception {
        Session owner = register("ai-owner@example.test");
        Session other = register("ai-other@example.test");
        String sessionId = createAiSession(owner, "STUDY");

        MvcResult stream = mvc.perform(post("/api/v1/ai/sessions/{id}/messages:stream", sessionId)
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"帮我规划今天的学习\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String sse = stream.getResponse().getContentAsString();
        assertThat(sse.indexOf("event:meta")).isLessThan(sse.indexOf("event:delta"));
        assertThat(sse.indexOf("event:delta")).isLessThan(sse.indexOf("event:done"));

        mvc.perform(get("/api/v1/ai/sessions").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].publicId").value(sessionId))
            .andExpect(jsonPath("$.data[0].scene").value("STUDY"))
            .andExpect(jsonPath("$.data[0].messageCount").value(2))
            .andExpect(jsonPath("$.data[0].lastRole").value("ASSISTANT"));
        mvc.perform(get("/api/v1/ai/sessions").cookie(other.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/v1/ai/sessions/{id}/messages", sessionId).cookie(other.access()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("AI_SESSION_NOT_FOUND"));

        String crisisSession = createAiSession(owner, "EMOTIONAL_SUPPORT");
        MvcResult crisis = mvc.perform(post("/api/v1/ai/sessions/{id}/messages:stream", crisisSession)
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"我不想活了\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String crisisSse = crisis.getResponse().getContentAsString();
        assertThat(crisisSse).contains("event:meta", "event:safety", "event:done").doesNotContain("event:delta");
        assertThat(jdbc.queryForObject("select count(*) from ai_safety_event where risk_level = 'L3' and redacted_excerpt = '[REDACTED]'", Integer.class)).isEqualTo(1);

        PlanningFixture planning = planning(owner.email());
        MvcResult generated = mvc.perform(post("/api/v1/ai/suggestions")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("""
                    {"scene":"STUDY","goalPublicId":"%s","prompt":"给我两个可完成的学习任务"}
                    """.formatted(planning.goalPublicId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andReturn();
        String setId = value(generated.getResponse().getContentAsString(), "publicId");
        String adoptionBody = """
            {"weeklyPlanPublicId":"%s","activeFrom":"2026-08-03","activeUntil":"2026-08-09"}
            """.formatted(planning.planPublicId());
        mvc.perform(post("/api/v1/ai/suggestions/{id}/adopt", setId)
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .header("Idempotency-Key", "adopt-once")
                .contentType("application/json")
                .content(adoptionBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.taskPublicIds.length()").value(2));
        mvc.perform(post("/api/v1/ai/suggestions/{id}/adopt", setId)
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .header("Idempotency-Key", "adopt-once")
                .contentType("application/json")
                .content(adoptionBody))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from user_task where user_id = ?", Integer.class, planning.userId())).isEqualTo(2);

        jdbc.update("update suggestion_set set expires_at = ? where public_id = ?", Timestamp.from(Instant.now().minusSeconds(60)), setId);
        mvc.perform(get("/api/v1/ai/suggestions/{id}", setId).cookie(other.access()))
            .andExpect(status().isNotFound());
    }

    private String createAiSession(Session session, String scene) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/ai/sessions")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"scene\":\"" + scene + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return value(result.getResponse().getContentAsString(), "publicId");
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"AI User","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(email, result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private PlanningFixture planning(String email) {
        long userId = jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, email);
        long dimensionId = jdbc.queryForObject("select id from growth_dimension where code = 'KNOWLEDGE'", Long.class);
        String goalId = "70000000000000000000000001";
        String planId = "70000000000000000000000002";
        jdbc.update("insert into growth_goal (public_id, user_id, dimension_id, title, start_date, end_date) values (?, ?, ?, 'AI goal', '2026-08-03', '2026-08-30')", goalId, userId, dimensionId);
        long goalInternalId = jdbc.queryForObject("select id from growth_goal where public_id = ?", Long.class, goalId);
        jdbc.update("insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone) values (?, ?, ?, '2026-08-03', 'Asia/Shanghai')", planId, userId, goalInternalId);
        return new PlanningFixture(userId, goalId, planId);
    }

    private String value(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private record Session(String email, Cookie access, Cookie csrf) {
    }

    private record PlanningFixture(long userId, String goalPublicId, String planPublicId) {
    }
}
