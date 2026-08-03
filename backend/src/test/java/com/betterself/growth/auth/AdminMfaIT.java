package com.betterself.growth.auth;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Tag("security")
@Import(AdminMfaIT.FixedClockConfiguration.class)
class AdminMfaIT {

    private static final Instant TEST_NOW = Instant.parse("2026-08-03T08:00:00Z");

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
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MfaSecretCipher mfaSecretCipher;
    @Autowired Clock clock;

    @Test
    void issuesNoSessionUntilEncryptedTotpSecretIsVerified() throws Exception {
        String secret = "JBSWY3DPEHPK3PXP";
        byte[] encrypted = mfaSecretCipher.encrypt(secret);
        jdbc.update(
            """
                insert into sys_user (
                    public_id, email, email_normalized, password_hash, display_name,
                    birth_date, timezone, status, role, mfa_secret_encrypted
                ) values (?, ?, ?, ?, ?, '1990-01-01', 'Asia/Shanghai', 'ACTIVE', 'ADMIN', ?)
                """,
            "01KYVTESTADMIN000000000000",
            "admin@example.test",
            "admin@example.test",
            passwordEncoder.encode("Correct-Horse-Battery-2026!"),
            "Administrator",
            encrypted
        );
        assertThat(new String(encrypted)).doesNotContain(secret);

        String loginBody = "{\"email\":\"admin@example.test\",\"password\":\"Correct-Horse-Battery-2026!\"}";
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(loginBody))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("MFA_PENDING")))
            .andExpect(cookie().doesNotExist("access_token"))
            .andExpect(cookie().doesNotExist("refresh_token"));

        String hardcodedMfaBody = """
            {"email":"admin@example.test","password":"Correct-Horse-Battery-2026!","code":"123456"}
            """;
        mvc.perform(post("/api/v1/auth/mfa/verify").contentType("application/json").content(hardcodedMfaBody))
            .andExpect(status().isUnauthorized());

        long timeWindow = clock.instant().getEpochSecond() / 30;
        String code = new DefaultCodeGenerator().generate(secret, timeWindow);
        String mfaBody = """
            {"email":"admin@example.test","password":"Correct-Horse-Battery-2026!","code":"%s"}
            """.formatted(code);
        mvc.perform(post("/api/v1/auth/mfa/verify").contentType("application/json").content(mfaBody))
            .andExpect(status().isOk())
            .andExpect(cookie().httpOnly("access_token", true))
            .andExpect(cookie().httpOnly("refresh_token", true));

        assertThat(jdbc.queryForObject(
            "select mfa_verified_at is not null from sys_user where email_normalized = 'admin@example.test'",
            Boolean.class
        )).isTrue();
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TEST_NOW, ZoneOffset.UTC);
        }
    }
}
