package com.betterself.growth.privacy;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "app.object-storage.provider=memory",
    "app.security.access-token-ttl=P35D",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.privacy.retention-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
@ContextConfiguration(classes = {com.betterself.growth.GrowthApplication.class, PrivacyFlowIT.ClockConfig.class})
class PrivacyFlowIT {

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
    @Autowired ExportService exports;
    @Autowired DeletionService deletions;
    @Autowired MutableClock clock;

    @Test
    void exportsOwnedArchiveAndRunsDeletionLifecycle() throws Exception {
        Session owner = register("privacy-owner@example.test");
        Session other = register("privacy-other@example.test");

        MvcResult created = mvc.perform(post("/api/v1/privacy/exports")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .header("Idempotency-Key", "export-once"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andReturn();
        String exportId = value(created.getResponse().getContentAsString(), "publicId");
        long ownerId = jdbc.queryForObject("select id from sys_user where email_normalized = 'privacy-owner@example.test'", Long.class);
        assertThat(entries(exports.content(ownerId, exportId)))
            .contains("manifest.json", "profile.json", "goals.csv", "task_events.csv");

        mvc.perform(post("/api/v1/privacy/exports")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .header("Idempotency-Key", "export-twice"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.data.code").value("EXPORT_RATE_LIMIT"));
        mvc.perform(get("/api/v1/privacy/exports/{id}/download", exportId).cookie(other.access()))
            .andExpect(status().isNotFound());

        clock.advance(Duration.ofHours(24).plusSeconds(1));
        assertThat(exports.expireDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from data_export_job where public_id = ?", String.class, exportId)).isEqualTo("EXPIRED");

        Instant deletionRequestedAt = clock.instant();
        mvc.perform(post("/api/v1/privacy/deletion")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("COOLING_OFF"));
        Instant processAfter = jdbc.queryForObject(
            "select process_after from deletion_request where user_id = ? order by id desc limit 1",
            java.sql.Timestamp.class, ownerId
        ).toInstant();
        assertThat(processAfter).isEqualTo(deletionRequestedAt.plus(Duration.ofDays(7)));
        assertThat(jdbc.queryForObject("select count(*) from auth_session where user_id = ? and revoked_at is null", Integer.class, ownerId)).isZero();

        mvc.perform(post("/api/v1/ai/sessions")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json").content("{\"scene\":\"STUDY\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/privacy/deletion/cancel")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mvc.perform(post("/api/v1/privacy/deletion")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()))
            .andExpect(status().isCreated());
        clock.advance(Duration.ofDays(7));
        assertThat(deletions.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from sys_user where id = ?", String.class, ownerId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select completion_receipt_hash is not null from deletion_request where user_id = ? and status = 'COMPLETED'", Boolean.class, ownerId)).isTrue();
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Privacy User","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated()).andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private List<String> entries(byte[] archive) throws Exception {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        return entries;
    }

    private String value(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private record Session(Cookie access, Cookie csrf) {
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-07-31T12:00:00Z"));
        }
    }

    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
