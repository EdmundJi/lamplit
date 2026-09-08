package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.application.ModelUsageQuery;
import com.betterself.growth.town.companion.application.ModelUsageRecorder;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V30's counters table against a real MySQL: two calls of the same type on the same day accumulate
 * instead of overwriting, a different call type gets its own row, and a call with no measured usage
 * (the mock-provider / not-yet-metered case) never writes a row at all - table stays exactly empty.
 * The HTTP side (CompanionController#usage) is exercised too: a freshly joined, mock-mode world must
 * answer with zero counts rather than failing, matching how this app runs without QWEN_PROVIDER=qwen set.
 */
@Testcontainers
@SpringBootTest(properties = {"app.ai.provider=mock"})
@AutoConfigureMockMvc
class JdbcModelUsageIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
        r.add("app.security.jwt-secret", () -> "test-only-secret-at-least-thirty-two-bytes");
        r.add("app.security.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("app.security.secure-cookies", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelUsageRecorder recorder;
    @Autowired ModelUsageQuery query;

    @Test void accumulatesByCallTypeAndIgnoresZeroUsageCalls() throws Exception {
        register("model-usage@example.test");
        long userId = userIdFor("model-usage@example.test");

        recorder.record(userId, "2026-09-08", "decision", 100, 20);
        recorder.record(userId, "2026-09-08", "decision", 50, 10);
        recorder.record(userId, "2026-09-08", "turn", 30, 5);
        recorder.record(userId, "2026-09-09", "decision", 999, 999); // a different day, must not leak in
        recorder.record(userId, "2026-09-08", "summary", 0, 0); // nothing measured: no row at all

        var today = query.forDay(userId, "2026-09-08");
        assertThat(today).hasSize(2);
        var decision = today.stream().filter(u -> u.callType().equals("decision")).findFirst().orElseThrow();
        assertThat(decision.callCount()).isEqualTo(2);
        assertThat(decision.inputTokens()).isEqualTo(150);
        assertThat(decision.outputTokens()).isEqualTo(30);
        var turn = today.stream().filter(u -> u.callType().equals("turn")).findFirst().orElseThrow();
        assertThat(turn.callCount()).isEqualTo(1);
        assertThat(turn.inputTokens()).isEqualTo(30);
        assertThat(today).noneMatch(u -> u.callType().equals("summary"));

        assertThat(jdbc.queryForObject(
            "select count(*) from town_companion_model_usage where user_id=? and usage_date='2026-09-08'",
            Integer.class, userId)).isEqualTo(2);
    }

    @Test void usageEndpointAnswersZeroForAFreshMockModeWorldInsteadOfCrashing() throws Exception {
        var session = register("model-usage-http@example.test");
        mvc.perform(post("/api/v1/town/companion/join").cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/town/companion/usage").cookie(session.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCalls").value(0))
            .andExpect(jsonPath("$.data.totalInputTokens").value(0))
            .andExpect(jsonPath("$.data.totalOutputTokens").value(0))
            .andExpect(jsonPath("$.data.byCallType").isEmpty());
    }

    private long userIdFor(String email) {
        return jdbc.queryForObject(
            "select id from sys_user where email_normalized=?", Long.class, email.toLowerCase(java.util.Locale.ROOT));
    }

    private Session register(String email) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
            {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Companion","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
            """.formatted(email))).andExpect(status().isCreated()).andReturn();
        return new Session(response.getResponse().getCookie("access_token"), response.getResponse().getCookie("csrf_token"));
    }

    private record Session(Cookie access, Cookie csrf) {}
}
