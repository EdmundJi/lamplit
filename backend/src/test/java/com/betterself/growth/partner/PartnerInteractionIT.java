package com.betterself.growth.partner;

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
class PartnerInteractionIT {

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
    void onlyFirstInteractionOfLocalDayAwardsAffection() throws Exception {
        Session session = register("partner-interaction@example.test");
        mvc.perform(get("/api/v1/partners/profile").cookie(session.access()))
            .andExpect(status().isOk());

        long userId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "partner-interaction@example.test"
        );
        String petId = jdbc.queryForObject(
            "select public_id from partner_pet where user_id = ? and selected = 1", String.class, userId
        );

        interact(session, petId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rewarded").value(true))
            .andExpect(jsonPath("$.data.affectionDelta").value(2))
            .andExpect(jsonPath("$.data.pet.affection").value(2));

        interact(session, petId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rewarded").value(false))
            .andExpect(jsonPath("$.data.affectionDelta").value(0))
            .andExpect(jsonPath("$.data.pet.affection").value(2));

        assertThat(jdbc.queryForObject(
            "select count(*) from partner_interaction where user_id = ?", Integer.class, userId
        )).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions interact(Session session, String petId) throws Exception {
        return mvc.perform(post("/api/v1/partners/pets/{petId}/interact", petId)
            .cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue()));
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"Partner User",
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
