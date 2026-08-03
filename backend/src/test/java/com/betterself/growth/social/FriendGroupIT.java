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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class FriendGroupIT {

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
        registry.add("test.context.variant", () -> "group");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void groupChatSupportsMembersMessagesAndUnreadSummary() throws Exception {
        Session alice = register("group-a@example.test", "Alice");
        Session bob = register("group-b@example.test", "Bob");
        Session carol = register("group-c@example.test", "Carol");
        String alicePublicId = publicId("group-a@example.test");
        String bobPublicId = publicId("group-b@example.test");
        String carolPublicId = publicId("group-c@example.test");

        for (String email : List.of("group-b@example.test", "group-c@example.test")) {
            request(alice, email).andExpect(status().isCreated());
        }
        accept(bob, alicePublicId).andExpect(status().isOk());
        accept(carol, alicePublicId).andExpect(status().isOk());

        MvcResult created = mvc.perform(post("/api/v1/friends/groups")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"name\":\"周末学习小组\",\"memberPublicIds\":[\"%s\",\"%s\"]}".formatted(bobPublicId, carolPublicId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("周末学习小组"))
            .andExpect(jsonPath("$.data.members.length()").value(3))
            .andExpect(jsonPath("$.data.members[0].displayName").value("Alice"))
            .andExpect(jsonPath("$.data.members[0].owner").value(true))
            .andReturn();
        String groupPublicId = jdbc.queryForObject(
            "select public_id from friend_group order by id desc limit 1", String.class
        );

        sendGroup(alice, groupPublicId, "大家好 👋").andExpect(status().isOk());
        sendGroup(bob, groupPublicId, "来了 😊").andExpect(status().isOk());

        mvc.perform(get("/api/v1/friends/groups").cookie(alice.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("周末学习小组"))
            .andExpect(jsonPath("$.data[0].lastMessage").value("来了 😊"))
            .andExpect(jsonPath("$.data[0].memberCount").value(3));

        mvc.perform(get("/api/v1/friends/unread-summary").cookie(bob.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalUnread").value(1))
            .andExpect(jsonPath("$.data.kind").value("group"));

        mvc.perform(get("/api/v1/friends/groups/{id}/messages", groupPublicId).cookie(bob.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].body").value("大家好 👋"))
            .andExpect(jsonPath("$.data[1].senderName").value("Bob"))
            .andExpect(jsonPath("$.data[1].fromMe").value(true));

        mvc.perform(post("/api/v1/friends/groups/{id}/read", groupPublicId)
                .cookie(bob.access(), bob.csrf())
                .header("X-CSRF-Token", bob.csrf().getValue()))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
            """
                select count(*) from friend_group_message m
                join friend_group_member mem on mem.group_id = m.group_id and mem.user_id = ?
                where m.sender_user_id != ? and m.created_at > coalesce(mem.last_read_at, ?)
                """,
            Integer.class,
            jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, "group-b@example.test"),
            jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, "group-b@example.test"),
            java.sql.Timestamp.from(java.time.Instant.EPOCH)
        )).isZero();
    }

    @Test
    void groupLimitsAndMembershipAreEnforced() throws Exception {
        Session alice = register("group-limit-a@example.test", "Alice");
        Session bob = register("group-limit-b@example.test", "Bob");
        String alicePublicId = publicId("group-limit-a@example.test");
        String bobPublicId = publicId("group-limit-b@example.test");
        request(alice, "group-limit-b@example.test").andExpect(status().isCreated());
        accept(bob, alicePublicId).andExpect(status().isOk());

        mvc.perform(post("/api/v1/friends/groups")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"name\":\"\",\"memberPublicIds\":[\"%s\"]}".formatted(bobPublicId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("INVALID_GROUP_NAME"));

        mvc.perform(post("/api/v1/friends/groups")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"name\":\"只有自己\",\"memberPublicIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("INVALID_GROUP_MEMBERS"));

        mvc.perform(post("/api/v1/friends/groups")
                .cookie(alice.access(), alice.csrf())
                .header("X-CSRF-Token", alice.csrf().getValue())
                .contentType("application/json")
                .content("{\"name\":\"陌生人\",\"memberPublicIds\":[\"not-a-friend\"]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("FRIENDSHIP_NOT_FOUND"));

        String groupPublicId = jdbc.queryForObject(
            "select public_id from friend_group order by id desc limit 1", String.class
        );
        mvc.perform(post("/api/v1/friends/groups/{id}/messages", groupPublicId)
                .cookie(bob.access(), bob.csrf())
                .header("X-CSRF-Token", bob.csrf().getValue())
                .contentType("application/json")
                .content("{\"body\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.code").value("INVALID_MESSAGE_BODY"));
    }

    private String publicId(String email) {
        return jdbc.queryForObject(
            "select public_id from sys_user where email_normalized = ?", String.class, email
        );
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

    private org.springframework.test.web.servlet.ResultActions sendGroup(Session session, String groupPublicId, String body) throws Exception {
        return mvc.perform(post("/api/v1/friends/groups/{id}/messages", groupPublicId)
            .cookie(session.access(), session.csrf())
            .header("X-CSRF-Token", session.csrf().getValue())
            .contentType("application/json")
            .content("{\"body\":\"%s\"}".formatted(body)));
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
