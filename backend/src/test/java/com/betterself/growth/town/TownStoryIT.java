package com.betterself.growth.town;

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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock", "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *", "app.town.reflection-cron=0 0 0 1 1 *",
    "app.town.society-cron=0 0 0 1 1 *", "app.security.access-token-ttl=P35D"
})
@AutoConfigureMockMvc
class TownStoryIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
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
    @Autowired ObjectMapper mapper;
    @Autowired TownStoryService stories;

    @Test
    void storyChoicesPersistPrivatelyAndRetriesNeverSkipAnUnseenPassage() throws Exception {
        Session owner = register("story-owner@example.test");
        Session other = register("story-other@example.test");
        var initial = read(owner, "GUIDE");
        assertThat(initial.stage()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from town_story_progress where town_user_id=?", Integer.class, owner.id())).isZero();
        int facts = jdbc.queryForObject("select count(*) from town_fact where town_user_id=?", Integer.class, owner.id());
        var started = advance(owner, "GUIDE", "BEGIN", initial);
        assertThat(started.stage()).isEqualTo(1);
        assertThat(advance(owner, "GUIDE", "BEGIN", initial)).isEqualTo(started);
        var paused = advance(owner, "GUIDE", "PAUSE", started);
        assertThat(paused.paused()).isTrue();
        assertThat(read(owner, "GUIDE")).isEqualTo(paused);
        var resumed = advance(owner, "GUIDE", "RESUME", paused);
        assertThat(resumed.stage()).isEqualTo(1);
        assertThat(resumed.paused()).isFalse();
        // A delayed pause request from another tab is obsolete, even at the same stage.
        assertThat(advance(owner, "GUIDE", "PAUSE", started)).isEqualTo(resumed);
        var choice = advance(owner, "GUIDE", "CONTINUE", resumed);
        var participated = advance(owner, "GUIDE", "PARTICIPATE", choice);
        assertThat(participated.body()).contains("你选的");
        var completed = advance(owner, "GUIDE", "CONTINUE", participated);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.actions()).isEmpty();
        assertThat(read(owner, "GUIDE")).isEqualTo(completed);
        assertThat(read(other, "GUIDE").stage()).isZero();
        assertThat(read(owner, "POSTMAN").stage()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from town_fact where town_user_id=?", Integer.class, owner.id())).isEqualTo(facts);
    }

    @Test
    void concurrentContinuesAndObservationPreserveMeaningfulChoice() throws Exception {
        Session owner = register("story-race@example.test");
        var started = advance(owner, "POSTMAN", "BEGIN", read(owner, "POSTMAN"));
        var command = new TownStoryService.Command("CONTINUE", started.stage(), started.revision());
        var first = CompletableFuture.supplyAsync(() -> stories.advance(owner.id(), "POSTMAN", command));
        var second = CompletableFuture.supplyAsync(() -> stories.advance(owner.id(), "POSTMAN", command));
        assertThat(first.get(15, TimeUnit.SECONDS).stage()).isEqualTo(2);
        assertThat(second.get(15, TimeUnit.SECONDS).stage()).isEqualTo(2);
        var choice = read(owner, "POSTMAN");
        var observed = advance(owner, "POSTMAN", "OBSERVE", choice);
        assertThat(observed.participation()).isEqualTo("OBSERVED");
        assertThat(observed.body()).doesNotContain("你选");
        var completed = advance(owner, "POSTMAN", "CONTINUE", observed);
        assertThat(completed.body()).contains("你听过").doesNotContain("一起", "你选");
    }

    @Test
    void requiresAuthenticationCsrfValidResidentAndCurrentLegalAction() throws Exception {
        Session owner = register("story-invalid@example.test");
        mvc.perform(get("/api/v1/town/stories/GUIDE")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/town/stories/KE_YUN").cookie(owner.access())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/town/stories/GUIDE/advance").cookie(owner.access())
            .contentType("application/json").content("{\"action\":\"BEGIN\",\"expectedStage\":0,\"expectedRevision\":0}"))
            .andExpect(status().isForbidden());
        for (String body : new String[]{"{}", "{\"action\":\"BEGIN\",\"expectedStage\":-1,\"expectedRevision\":0}",
            "{\"action\":\"PARTICIPATE\",\"expectedStage\":0,\"expectedRevision\":0}"}) {
            mvc.perform(post("/api/v1/town/stories/GUIDE/advance").cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(read(owner, "GUIDE").stage()).isZero();
    }

    private TownStoryService.StoryView read(Session session, String code) throws Exception {
        return data(mvc.perform(get("/api/v1/town/stories/" + code).cookie(session.access()))
            .andExpect(status().isOk()).andReturn());
    }
    private TownStoryService.StoryView advance(Session session, String code, String action, TownStoryService.StoryView old) throws Exception {
        return data(mvc.perform(post("/api/v1/town/stories/" + code + "/advance").cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue()).contentType("application/json")
            .content(mapper.writeValueAsString(new TownStoryService.Command(action, old.stage(), old.revision()))))
            .andExpect(status().isOk()).andReturn());
    }
    private TownStoryService.StoryView data(MvcResult response) throws Exception {
        return mapper.treeToValue(mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data"),
            TownStoryService.StoryView.class);
    }
    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
            {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
            """.formatted(email))).andExpect(status().isCreated()).andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"),
            jdbc.queryForObject("select id from sys_user where email_normalized=?", Long.class, email));
    }
    private record Session(Cookie access, Cookie csrf, long id) {}
}
