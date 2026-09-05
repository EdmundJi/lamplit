package com.betterself.growth.town;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A promise made in one turn must not be asked about in that same turn — even though
 * {@code town_npc_promise.made_at} is a {@code DATETIME(3)} column that floors the in-memory
 * instant it was written with, which once made a plain "made_at &lt; now" comparison true for
 * the very row just inserted (see {@link TownPromiseWindow}). It should sit unasked until a
 * later turn with a real gap, then get surfaced exactly once.
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *",
    // The mocked clock's anchor is arbitrary and stays far behind the wall clock; a long TTL
    // keeps the session's own token validity from tripping over that gap (see PrivacyFlowIT).
    "app.security.access-token-ttl=P35D"
})
@AutoConfigureMockMvc
class TownNpcPromiseIT {

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
    @Autowired MutableClock clock;

    @Test
    void neverAsksAboutAPromiseInTheSameTurnThenSurfacesItOnceAfterARealGap() throws Exception {
        Session owner = register("town-promise@example.test");
        long userId = jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, owner.email());

        chat(owner, "我等下看五分钟就好");

        // Same turn: the row was just written, so a naive timestamp comparison against the
        // in-memory "now" of this very request would already be floored below it (the bug).
        // The fix must still leave it unasked.
        String content = jdbc.queryForObject(
            "select content from town_npc_promise where user_id = ? and npc_code = 'GUIDE'", String.class, userId
        );
        assertThat(content).isEqualTo("我等下看五分钟就好");
        assertThat(unasked(userId)).isTrue();

        // A reply moments later is still too soon to bring it up (requirement: a real gap).
        clock.advance(Duration.ofMinutes(1));
        chat(owner, "早呀");
        assertThat(unasked(userId)).isTrue();

        // Only once a real gap has passed does the next turn surface it — and only once.
        clock.advance(Duration.ofMinutes(5));
        chat(owner, "在吗");
        assertThat(unasked(userId)).isFalse();

        Integer count = jdbc.queryForObject("select count(*) from town_npc_promise where user_id = ?", Integer.class, userId);
        assertThat(count).isEqualTo(1);
    }

    private Boolean unasked(long userId) {
        return jdbc.queryForObject(
            "select asked_at is null from town_npc_promise where user_id = ? and npc_code = 'GUIDE'", Boolean.class, userId
        );
    }

    private void chat(Session owner, String message) throws Exception {
        MvcResult started = mvc.perform(post("/api/v1/town/npc/GUIDE/chat:stream")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"" + message + "\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
        started.getAsyncResult(15_000);
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(email, result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private record Session(String email, Cookie access, Cookie csrf) {
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-05T01:00:00Z"));
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
