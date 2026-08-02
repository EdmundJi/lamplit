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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Tag("security")
class RefreshReplayIT {

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
    void revokesTheTokenFamilyWhenAnOldRefreshTokenIsReplayed() throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content(AuthFlowIT.registrationJson("replay@example.test")))
            .andExpect(status().isCreated())
            .andReturn();
        Cookie originalRefresh = registration.getResponse().getCookie("refresh_token");

        mvc.perform(post("/api/v1/auth/refresh").cookie(originalRefresh))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/refresh").cookie(originalRefresh))
            .andExpect(status().isUnauthorized());

        Integer activeSessions = jdbc.queryForObject(
            "select count(*) from auth_session where revoked_at is null",
            Integer.class
        );
        assertThat(activeSessions).isZero();
    }
}
