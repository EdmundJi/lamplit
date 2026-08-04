package com.betterself.growth.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Tag("security")
class AuthFlowIT {

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
    void registersAtomicallyAndEnforcesCsrfWithoutReturningTokens() throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content(registrationJson("adult@example.test")))
            .andExpect(status().isCreated())
            .andExpect(cookie().httpOnly("access_token", true))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().httpOnly("csrf_token", false))
            .andReturn();

        String body = registration.getResponse().getContentAsString();
        Long userId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?",
            Long.class,
            "adult@example.test"
        );
        assertThat(body).doesNotContain("accessToken", "refreshToken", "csrfToken", "\"id\"");
        assertThat(jdbc.queryForObject("select count(*) from consent_record where user_id = ?", Integer.class, userId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from user_preference where user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from user_dimension where user_id = ?", Integer.class, userId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from user_role_progress where user_id = ?", Integer.class, userId)).isEqualTo(4);

        Cookie access = registration.getResponse().getCookie("access_token");
        Cookie csrf = registration.getResponse().getCookie("csrf_token");
        String preferenceBody = "{\"dailyMinutes\":45,\"weeklyFrequency\":4,\"preferredDifficulty\":2}";

        mvc.perform(patch("/api/v1/me/preferences")
                .cookie(access, csrf)
                .contentType("application/json")
                .content(preferenceBody))
            .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/me/preferences")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content(preferenceBody))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/me/consents")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("{\"type\":\"AI\",\"version\":\"2026-07\",\"granted\":false}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
            "select granted from consent_record where user_id = ? and consent_type = 'AI'",
            Boolean.class,
            userId
        )).isFalse();

        mvc.perform(put("/api/v1/auth/password")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("{\"currentPassword\":\"Correct-Horse-Battery-2026!\",\"newPassword\":\"Changed-Horse-Battery-2026!\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void allowsUsersUnderEighteenToRegister() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content(registrationJson("young@example.test", "2015-01-01")))
            .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
            "select birth_date from sys_user where email_normalized = ?",
            String.class,
            "young@example.test"
        )).isEqualTo("2015-01-01");
    }

    @Test
    void rejectsMalformedRegistrationAsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email": "adult@example.test",
                      "password": "Correct-Horse-Battery-2026!",
                      "displayName": "",
                      "timezone": "Asia/Shanghai",
                      "consents": {
                        "terms": "2026-07",
                        "privacy": "2026-07",
                        "ai": "2026-07"
                      }
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void returnsTheRegisteredUsersGrowthProfile() throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content(registrationJson("profile@example.test")))
            .andExpect(status().isCreated())
            .andReturn();

        mvc.perform(get("/api/v1/me/profile")
                .cookie(registration.getResponse().getCookie("access_token")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.displayName").value("Test User"))
            .andExpect(jsonPath("$.data.birthDate").value("1990-01-01"))
            .andExpect(jsonPath("$.data.age").isNumber())
            .andExpect(jsonPath("$.data.overallLevel").value(1))
            .andExpect(jsonPath("$.data.totalExperience").value(0))
            .andExpect(jsonPath("$.data.effectiveActions").value(0))
            .andExpect(jsonPath("$.data.wallet.coinBalance").value(0))
            .andExpect(jsonPath("$.data.petCount").value(1))
            .andExpect(jsonPath("$.data.soloGrowth").value(false))
            .andExpect(jsonPath("$.data.selectedPet.speciesCode").value("CAT"));

        Cookie access = registration.getResponse().getCookie("access_token");
        Cookie csrf = registration.getResponse().getCookie("csrf_token");
        mvc.perform(patch("/api/v1/me/privacy")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("{\"soloGrowth\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.soloGrowth").value(true));

        mvc.perform(get("/api/v1/me/profile").cookie(access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.soloGrowth").value(true));
    }

    static String registrationJson(String email) {
        return registrationJson(email, "1990-01-01");
    }

    static String registrationJson(String email, String birthDate) {
        return """
            {
              "email": "%s",
              "password": "Correct-Horse-Battery-2026!",
              "displayName": "Test User",
              "birthDate": "%s",
              "timezone": "Asia/Shanghai",
              "consents": {
                "terms": "2026-07",
                "privacy": "2026-07",
                "ai": "2026-07"
              }
            }
            """.formatted(email, birthDate);
    }
}
