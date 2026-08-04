package com.betterself.growth.achievement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AchievementTitleIT {

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
    @Autowired ObjectMapper objectMapper;

    @Test
    void lazilyUnlocksAchievementsAndManagesTheRewardTitle() throws Exception {
        Session session = register("achievement-title@example.test");
        long userId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?",
            Long.class,
            "achievement-title@example.test"
        );
        jdbc.update(
            "update user_role_progress set level = 2 where user_id = ? and role_code = 'STUDENT'",
            userId
        );

        JsonNode achievements = responseData(mvc.perform(get("/api/v1/achievements").cookie(session.access()))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode learningStart = item(achievements, "LEARNING_START");
        assertThat(achievements).hasSize(20);
        assertThat(learningStart.path("earned").booleanValue()).isTrue();
        assertThat(learningStart.path("earnedAt").textValue()).isNotBlank();
        assertThat(learningStart.path("triggerText").textValue()).isEqualTo("学生等级 >= 2");
        assertThat(jdbc.queryForObject(
            "select count(*) from user_achievement where user_id = ? and achievement_code = 'LEARNING_START'",
            Integer.class,
            userId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from user_title where user_id = ? and title_code = 'LEARNER'",
            Integer.class,
            userId
        )).isEqualTo(1);

        mvc.perform(get("/api/v1/achievements").cookie(session.access()))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
            "select count(*) from user_achievement where user_id = ? and achievement_code = 'LEARNING_START'",
            Integer.class,
            userId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from user_title where user_id = ? and title_code = 'LEARNER'",
            Integer.class,
            userId
        )).isEqualTo(1);

        JsonNode titles = responseData(mvc.perform(get("/api/v1/titles").cookie(session.access()))
            .andExpect(status().isOk())
            .andReturn());
        assertThat(titles).hasSize(14);
        assertThat(item(titles, "LEARNER").path("held").booleanValue()).isTrue();
        assertThat(item(titles, "PEAK_CLIMBER").path("held").booleanValue()).isFalse();

        JsonNode equipped = responseData(mvc.perform(patch("/api/v1/titles/equipped")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"code\":\"LEARNER\"}"))
            .andExpect(status().isOk())
            .andReturn());
        assertThat(item(equipped, "LEARNER").path("equipped").booleanValue()).isTrue();
        mvc.perform(get("/api/v1/me/profile").cookie(session.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.equippedTitle.code").value("LEARNER"))
            .andExpect(jsonPath("$.data.equippedTitle.frameStyle").value("sky"));

        mvc.perform(patch("/api/v1/titles/equipped")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"code\":\"PEAK_CLIMBER\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("TITLE_NOT_HELD"));
        mvc.perform(patch("/api/v1/titles/equipped")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content("{\"code\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("VALIDATION_FAILED"));

        jdbc.update(
            "insert into user_title (user_id, title_code) values (?, 'START_STEPPER')",
            userId
        );
        assertThatThrownBy(() -> jdbc.update(
            "update user_title set equipped = 1 where user_id = ? and title_code = 'START_STEPPER'",
            userId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
            "select count(*) from user_title where user_id = ? and equipped = 1",
            Integer.class,
            userId
        )).isEqualTo(1);

        mvc.perform(delete("/api/v1/titles/equipped")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue()))
            .andExpect(status().isOk());
        assertThat(responseData(mvc.perform(get("/api/v1/me/profile").cookie(session.access()))
            .andExpect(status().isOk())
            .andReturn()).path("equippedTitle").isNull()).isTrue();
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private JsonNode item(JsonNode items, String code) {
        for (JsonNode item : items) {
            if (code.equals(item.path("code").textValue())) {
                return item;
            }
        }
        throw new AssertionError("Missing response item: " + code);
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"Achievement User",
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
