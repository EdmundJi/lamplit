package com.betterself.growth.goal;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TaskPresetIT {

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
    void keepsDailyDrawStableAndSharesThreeRefreshesAcrossRoles() throws Exception {
        Session session = register("presets@example.test");

        MvcResult first = getDraw(session, "STUDENT")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(4))
            .andExpect(jsonPath("$.data.refreshesRemaining").value(3))
            .andExpect(jsonPath("$.data.items[0].roleName").value("学生"))
            .andReturn();
        MvcResult repeated = getDraw(session, "STUDENT").andExpect(status().isOk()).andReturn();
        assertThat(itemIds(first)).isEqualTo(itemIds(repeated));

        MvcResult refreshed = refresh(session, "STUDENT")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshesRemaining").value(2))
            .andReturn();
        assertThat(itemIds(refreshed)).doesNotContainAnyElementsOf(itemIds(first));

        getDraw(session, "FITNESS_USER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshesRemaining").value(2));
        refresh(session, "FITNESS_USER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshesRemaining").value(1));
        refresh(session, "WORKER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshesRemaining").value(0));
        refresh(session, "EMOTIONAL_SUPPORT_USER")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.data.code").value("TASK_PRESET_REFRESH_LIMIT"));

        assertThat(jdbc.queryForObject(
            "select refresh_count from task_preset_quota", Integer.class
        )).isEqualTo(3);
    }

    private org.springframework.test.web.servlet.ResultActions getDraw(Session session, String role) throws Exception {
        return mvc.perform(get("/api/v1/task-presets").param("role", role).cookie(session.access()));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(Session session, String role) throws Exception {
        return mvc.perform(post("/api/v1/task-presets/refresh").param("role", role)
            .cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue()));
    }

    private java.util.List<String> itemIds(MvcResult result) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("items");
        java.util.List<String> ids = new java.util.ArrayList<>();
        items.forEach(item -> ids.add(item.path("publicId").asText()));
        return ids;
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"Preset User",
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
