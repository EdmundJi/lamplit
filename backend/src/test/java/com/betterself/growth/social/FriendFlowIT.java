package com.betterself.growth.social;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class FriendFlowIT {

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
    void friendRequestIsAcceptedAndProfileIsOnlyVisibleToFriends() throws Exception {
        Session alice = register("alice@example.test", "Alice");
        Session bob = register("bob@example.test", "Bob");
        long aliceId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "alice@example.test"
        );
        long bobId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "bob@example.test"
        );
        String bobPublicId = jdbc.queryForObject(
            "select public_id from sys_user where id = ?", String.class, bobId
        );
        String alicePublicId = jdbc.queryForObject(
            "select public_id from sys_user where id = ?", String.class, aliceId
        );

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"bob@example.test\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.direction").value("OUTGOING"))
            .andExpect(jsonPath("$.data.displayName").value("Bob"));

        mvc.perform(get("/api/v1/friends").cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.outgoing.length()").value(1))
            .andExpect(jsonPath("$.data.incoming.length()").value(0))
            .andExpect(jsonPath("$.data.friends.length()").value(0));

        mvc.perform(get("/api/v1/friends/{peerPublicId}", bobPublicId).cookie(alice.access()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("FRIENDSHIP_NOT_FOUND"));

        mvc.perform(post("/api/v1/friends/{peerPublicId}/accept", alicePublicId)
                .cookie(bob.access(), bob.csrf())
                .header("X-CSRF-Token", bob.csrf().getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        mvc.perform(get("/api/v1/friends").cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.friends.length()").value(1))
            .andExpect(jsonPath("$.data.friends[0].displayName").value("Bob"))
            .andExpect(jsonPath("$.data.outgoing.length()").value(0));

        mvc.perform(get("/api/v1/friends/{peerPublicId}", bobPublicId).cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.displayName").value("Bob"))
            .andExpect(jsonPath("$.data.overallLevel").value(1))
            .andExpect(jsonPath("$.data.attributes.length()").value(5))
            .andExpect(jsonPath("$.data.todayTasks").isArray())
            .andExpect(jsonPath("$.data.overview").exists());

        assertThat(jdbc.queryForObject(
            """
                select count(*) from friend_relationship
                where (requester_user_id = ? or addressee_user_id = ?) and status = 'ACCEPTED'
                """,
            Integer.class, aliceId, aliceId
        )).isEqualTo(1);
    }

    @Test
    void duplicateRequestAndSelfRequestAreRejected() throws Exception {
        Session alice = register("alice-duplicate@example.test", "Alice");
        Session bob = register("bob-duplicate@example.test", "Bob");

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"bob-duplicate@example.test\"}"))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"bob-duplicate@example.test\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.code").value("FRIEND_REQUEST_EXISTS"));

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"alice-duplicate@example.test\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("FRIEND_SELF_REQUEST"));

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"missing@example.test\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("FRIEND_USER_NOT_FOUND"));
    }

    @Test
    void soloGrowthHidesAUserFromNewFriendRequests() throws Exception {
        Session alice = register("alice-solo@example.test", "Alice");
        Session bob = register("bob-solo@example.test", "Bob");

        mvc.perform(patch("/api/v1/me/privacy")
                .cookie(bob.access(), bob.csrf())
                .header("X-CSRF-Token", bob.csrf().getValue())
                .contentType("application/json")
                .content("{\"soloGrowth\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.soloGrowth").value(true));

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"bob-solo@example.test\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("FRIEND_USER_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select count(*) from friend_relationship", Integer.class)).isZero();

        mvc.perform(patch("/api/v1/me/privacy")
                .cookie(bob.access(), bob.csrf())
                .header("X-CSRF-Token", bob.csrf().getValue())
                .contentType("application/json")
                .content("{\"soloGrowth\":false}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"bob-solo@example.test\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void crossRequestAutoAcceptsAndIncomingCanBeRejectedOrRemoved() throws Exception {
        Session alice = register("alice-cross@example.test", "Alice");
        Session bob = register("bob-cross@example.test", "Bob");
        Session carol = register("carol@example.test", "Carol");
        long aliceId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "alice-cross@example.test"
        );
        String bobPublicId = jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, "bob-cross@example.test"
        );
        String alicePublicId = jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, "alice-cross@example.test"
        );
        String carolPublicId = jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, "carol@example.test"
        );

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(bob.access(), bob.csrf())
                .header("X-CSRF-Token", bob.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"alice-cross@example.test\"}"))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"bob-cross@example.test\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"carol@example.test\"}"))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/friends/{peerPublicId}/reject", alicePublicId)
                .cookie(carol.access(), carol.csrf())
                .header("X-CSRF-Token", carol.csrf().getValue()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/friends").cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.outgoing.length()").value(0));

        mvc.perform(post("/api/v1/friends/requests")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"email\":\"carol@example.test\"}"))
            .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/friends/{peerPublicId}", carolPublicId)
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue()))
            .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
            """
                select count(*) from friend_relationship
                where (requester_user_id = ? or addressee_user_id = ?)
                """,
            Integer.class, aliceId, aliceId
        )).isEqualTo(1);
    }

    private Session register(String email, String displayName) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {
                      "email":"%s",
                      "password":"Correct-Horse-Battery-2026!",
                      "displayName":"%s",
                      "birthDate":"1990-01-01",
                      "timezone":"Asia/Shanghai",
                      "consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}
                    }
                    """.formatted(email, displayName)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private record Session(Cookie access, Cookie csrf) {
    }
}
