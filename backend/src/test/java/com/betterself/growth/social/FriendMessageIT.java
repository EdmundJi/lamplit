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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class FriendMessageIT {

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
        registry.add("test.context.variant", () -> "message");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void friendsExchangeMessagesWithUnreadTrackingAndPaging() throws Exception {
        Session alice = register("msg-alice@example.test", "Alice");
        Session bob = register("msg-bob@example.test", "Bob");
        long aliceId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "msg-alice@example.test"
        );
        long bobId = jdbc.queryForObject(
            "select id from sys_user where email_normalized = ?", Long.class, "msg-bob@example.test"
        );
        String bobPublicId = jdbc.queryForObject(
            "select public_id from sys_user where id = ?", String.class, bobId
        );
        String alicePublicId = jdbc.queryForObject(
            "select public_id from sys_user where id = ?", String.class, aliceId
        );

        request(alice, "msg-bob@example.test").andExpect(status().isCreated());
        accept(bob, alicePublicId).andExpect(status().isOk());

        send(alice, bobPublicId, "今晚一起复习一章 📚")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fromMe").value(true))
            .andExpect(jsonPath("$.data.body").value("今晚一起复习一章 📚"));
        send(bob, alicePublicId, "好呀 😊").andExpect(status().isOk());
        send(bob, alicePublicId, "八点见 🎉").andExpect(status().isOk());

        mvc.perform(get("/api/v1/friends/conversations").cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].peerDisplayName").value("Bob"))
            .andExpect(jsonPath("$.data[0].lastMessage").value("八点见 🎉"))
            .andExpect(jsonPath("$.data[0].lastMessageFromMe").value(false))
            .andExpect(jsonPath("$.data[0].unreadCount").value(2));

        mvc.perform(get("/api/v1/friends/messages").param("peerPublicId", bobPublicId).cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].body").value("今晚一起复习一章 📚"))
            .andExpect(jsonPath("$.data[2].body").value("八点见 🎉"))
            .andExpect(jsonPath("$.data[2].read").value(false));

        mvc.perform(post("/api/v1/friends/messages/read")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"peerPublicId\":\"%s\"}".formatted(bobPublicId)))
            .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
            "select count(*) from friend_message where receiver_user_id = ? and read_at is null",
            Integer.class, aliceId
        )).isZero();

        String latestId = jdbc.queryForObject(
            "select public_id from friend_message order by id desc limit 1", String.class
        );
        mvc.perform(get("/api/v1/friends/messages")
                .param("peerPublicId", bobPublicId)
                .param("beforePublicId", latestId)
                .param("limit", "2")
                .cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void strangersCannotSendOrReadMessages() throws Exception {
        Session alice = register("msg-stranger-a@example.test", "Alice");
        Session carol = register("msg-stranger-c@example.test", "Carol");
        String carolPublicId = jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, "msg-stranger-c@example.test"
        );

        send(alice, carolPublicId, "你好")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("FRIENDSHIP_NOT_FOUND"));

        mvc.perform(get("/api/v1/friends/messages")
                .param("peerPublicId", carolPublicId)
                .cookie(alice.access()))
            .andExpect(status().isNotFound());
    }

    @Test
    void emptyBodyIsRejected() throws Exception {
        Session alice = register("msg-empty-a@example.test", "Alice");
        Session bob = register("msg-empty-b@example.test", "Bob");
        String bobPublicId = jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, "msg-empty-b@example.test"
        );
        String alicePublicId = jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, "msg-empty-a@example.test"
        );
        request(alice, "msg-empty-b@example.test").andExpect(status().isCreated());
        accept(bob, alicePublicId).andExpect(status().isOk());

        send(alice, bobPublicId, "   ")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("INVALID_MESSAGE_BODY"));
    }

    private org.springframework.test.web.servlet.ResultActions request(Session session, String email) throws Exception {
        return mvc.perform(post("/api/v1/friends/requests")
            .cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue())
            .contentType("application/json")
            .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private org.springframework.test.web.servlet.ResultActions accept(Session session, String peerPublicId) throws Exception {
        return mvc.perform(post("/api/v1/friends/{peerPublicId}/accept", peerPublicId)
            .cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue()));
    }

    private org.springframework.test.web.servlet.ResultActions send(Session session, String peerPublicId, String body) throws Exception {
        return mvc.perform(post("/api/v1/friends/messages")
            .cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue())
            .contentType("application/json")
            .content("{\"peerPublicId\":\"%s\",\"body\":\"%s\"}".formatted(peerPublicId, body)));
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
