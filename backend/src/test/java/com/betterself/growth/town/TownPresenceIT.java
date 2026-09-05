package com.betterself.growth.town;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.assertj.core.api.Assertions.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side position memory: a resident's spot survives a refresh instead of being
 * recomputed from the schedule, movement is clamped to a plausible walking distance, scene
 * changes are never clamped, and a stale record still reports where the resident actually
 * was rather than a freshly recomputed spot.
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *",
    "app.security.access-token-ttl=P35D"
})
@AutoConfigureMockMvc
class TownPresenceIT {

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
    @Autowired MutableClock clock;

    @Test
    void tracksPositionContinuityWithClampingAndStaleness() throws Exception {
        Session owner = register("town-presence@example.test");

        // Before any report, a resident has no presence on record.
        MvcResult beforeAnyReport = mvc.perform(get("/api/v1/town").cookie(owner.access()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(beforeAnyReport)).contains("\"presence\":null");

        // First ever report: nothing to clamp against, accepted as-is.
        MvcResult first = reportPosition(owner, 100, 100, "down", "town");
        assertThat(numberField(body(first), "x")).isEqualTo(100, offset(0.001));
        assertThat(numberField(body(first), "y")).isEqualTo(100, offset(0.001));

        // A small, plausible move well inside the speed budget passes through unchanged.
        clock.advance(Duration.ofSeconds(2));
        MvcResult small = reportPosition(owner, 150, 100, "right", "town");
        assertThat(numberField(body(small), "x")).isEqualTo(150, offset(0.001));

        // A same-scene teleport gets clamped to the max plausible distance, not rejected.
        clock.advance(Duration.ofSeconds(2));
        MvcResult teleport = reportPosition(owner, 5000, 100, "right", "town");
        double allowed = TownPresenceClamp.RUN_SPEED_PX_PER_SEC * 2.0 * TownPresenceClamp.SPEED_MARGIN;
        assertThat(numberField(body(teleport), "x")).isEqualTo(150 + allowed, offset(0.001));
        assertThat(numberField(body(teleport), "y")).isEqualTo(100, offset(0.001));

        // Switching scene is a discrete event, never clamped, even with a huge jump.
        clock.advance(Duration.ofSeconds(2));
        MvcResult sceneChange = reportPosition(owner, 9999, 8888, "up", "academy");
        assertThat(numberField(body(sceneChange), "x")).isEqualTo(9999, offset(0.001));
        assertThat(numberField(body(sceneChange), "y")).isEqualTo(8888, offset(0.001));
        assertThat(field(body(sceneChange), "scene")).isEqualTo("academy");

        // A report that arrives implausibly soon after the last accepted one (same scene) is
        // ignored outright: the response still reflects the last *accepted* state, not this one.
        MvcResult tooSoon = reportPosition(owner, 1, 1, "down", "academy");
        assertThat(numberField(body(tooSoon), "x")).isEqualTo(9999, offset(0.001));
        assertThat(field(body(tooSoon), "facing")).isEqualTo("up");

        // GET /town reflects the last accepted position and is not yet stale.
        mvc.perform(get("/api/v1/town").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.residents[0].presence.scene").value("academy"))
            .andExpect(jsonPath("$.data.residents[0].presence.facing").value("up"))
            .andExpect(jsonPath("$.data.residents[0].presence.stale").value(false));

        // Ten-plus minutes later the same spot is flagged stale, not recomputed.
        clock.advance(Duration.ofMinutes(11));
        mvc.perform(get("/api/v1/town").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.residents[0].presence.scene").value("academy"))
            .andExpect(jsonPath("$.data.residents[0].presence.stale").value(true));
    }

    @Test
    void rejectsAnUnknownFacingValue() throws Exception {
        Session owner = register("town-presence-invalid@example.test");

        mvc.perform(post("/api/v1/town/presence")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"x\":1,\"y\":1,\"facing\":\"sideways\",\"scene\":\"town\"}"))
            .andExpect(status().isBadRequest());
    }

    private MvcResult reportPosition(Session owner, double x, double y, String facing, String scene) throws Exception {
        return mvc.perform(post("/api/v1/town/presence")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"x\":%s,\"y\":%s,\"facing\":\"%s\",\"scene\":\"%s\"}".formatted(x, y, facing, scene)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private double numberField(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker) + marker.length();
        int end = start;
        while (end < json.length() && "-0123456789.".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    private String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private record Session(Cookie access, Cookie csrf) {
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
