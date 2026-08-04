package com.betterself.growth.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class OnboardingFlowIT {

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
    void createsConfirmedStarterContentAndAwardsTheNewcomerTitleOnce() throws Exception {
        Session session = register("onboarding@example.test");
        JsonNode starters = responseData(mvc.perform(get("/api/v1/onboarding/starters")
                .param("scene", "STUDY")
                .cookie(session.access()))
            .andExpect(status().isOk())
            .andReturn());
        assertThat(starters).hasSize(4);
        List<String> selected = List.of(
            starters.get(0).path("publicId").textValue(),
            starters.get(1).path("publicId").textValue()
        );

        Map<String, Object> command = Map.of(
            "scene", "STUDY",
            "dailyMinutes", 30,
            "weeklyFrequency", 3,
            "preferredDifficulty", 1,
            "starterTemplatePublicIds", selected
        );
        MvcResult completed = mvc.perform(post("/api/v1/onboarding/complete")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.alreadyCompleted").value(false))
            .andExpect(jsonPath("$.data.taskPublicIds.length()").value(2))
            .andExpect(jsonPath("$.data.rewardTitle.code").value("NEWCOMER_PATH"))
            .andReturn();
        assertThat(responseData(completed).path("goalPublicId").textValue()).isNotBlank();

        long userId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = 'onboarding@example.test'",
            Long.class
        );
        assertThat(jdbc.queryForObject(
            "select scene from user_preference where user_id = ?",
            String.class,
            userId
        )).isEqualTo("STUDY");
        assertThat(jdbc.queryForObject(
            "select onboarding_completed_at is not null from user_preference where user_id = ?",
            Boolean.class,
            userId
        )).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from growth_goal where user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from weekly_plan where user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from user_task where user_id = ?", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from user_title where user_id = ? and title_code = 'NEWCOMER_PATH'",
            Integer.class,
            userId
        )).isEqualTo(1);

        mvc.perform(post("/api/v1/onboarding/complete")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.alreadyCompleted").value(true));
        assertThat(jdbc.queryForObject("select count(*) from growth_goal where user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from user_task where user_id = ?", Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void rejectsStarterTasksFromAnotherScene() throws Exception {
        Session session = register("onboarding-mismatch@example.test");
        JsonNode fitness = responseData(mvc.perform(get("/api/v1/onboarding/starters")
                .param("scene", "FITNESS")
                .cookie(session.access()))
            .andExpect(status().isOk())
            .andReturn());
        Map<String, Object> command = Map.of(
            "scene", "STUDY",
            "dailyMinutes", 30,
            "weeklyFrequency", 3,
            "preferredDifficulty", 2,
            "starterTemplatePublicIds", List.of(fitness.get(0).path("publicId").textValue())
        );

        mvc.perform(post("/api/v1/onboarding/complete")
                .cookie(session.access(), session.csrf())
                .header("X-CSRF-Token", session.csrf().getValue())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("INVALID_STARTER_TASKS"));
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"Onboarding User",
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
