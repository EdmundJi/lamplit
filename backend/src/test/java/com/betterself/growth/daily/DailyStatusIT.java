package com.betterself.growth.daily;

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

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DailyStatusIT {

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
        registry.add("test.context.variant", () -> "daily-status");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void savesTodayStatusWithComputedAdviceAndAllowsUpdate() throws Exception {
        Session session = register("daily@example.test");
        long userId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "daily@example.test"
        );

        mvc.perform(post("/api/v1/daily-status")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"energy\":\"LOW\",\"availableMinutes\":30}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.energy").value("LOW"))
            .andExpect(jsonPath("$.data.availableMinutes").value(30))
            .andExpect(jsonPath("$.data.advice").value("SHRINK"));

        assertThat(jdbc.queryForObject(
            "select count(*) from daily_status_check where user_id = ?", Integer.class, userId
        )).isEqualTo(1);

        mvc.perform(post("/api/v1/daily-status")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"energy\":\"OPEN\",\"availableMinutes\":60}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.advice").value("KEEP"));

        assertThat(jdbc.queryForObject(
            "select count(*) from daily_status_check where user_id = ?", Integer.class, userId
        )).isEqualTo(1);

        mvc.perform(get("/api/v1/daily-status").cookie(session.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.advice").value("KEEP"));
    }

    @Test
    void insightOverviewCountsEachCheckedDayOnce() throws Exception {
        Session session = register("daily-insight@example.test");
        for (String payload : java.util.List.of(
            "{\"energy\":\"LOW\",\"availableMinutes\":15}",
            "{\"energy\":\"OPEN\",\"availableMinutes\":60}",
            "{\"energy\":\"STEADY\",\"availableMinutes\":30}"
        )) {
            mvc.perform(post("/api/v1/daily-status")
                    .cookie(session.access(), session.csrf())
                    .header("X-CSRF-Token", session.csrf().getValue())
                    .contentType("application/json")
                    .content(payload))
                .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/insights/overview").cookie(session.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.statusCheckCount").value(1))
            .andExpect(jsonPath("$.data.statusAdvices.LIGHT").value(1))
            .andExpect(jsonPath("$.data.statusEnergy.STEADY").value(1));
    }

    @Test
    void invalidEnergyIsRejected() throws Exception {
        Session session = register("daily-invalid@example.test");
        mvc.perform(post("/api/v1/daily-status")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"energy\":\"CRAZY\",\"availableMinutes\":30}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("INVALID_ENERGY"));
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"Status User",
                      "birthDate":"1990-01-01",
                      "timezone":"Asia/Shanghai",
                      "consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}
                    }
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private record Session(Cookie access, Cookie csrf) {
    }
}
