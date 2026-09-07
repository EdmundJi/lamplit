package com.betterself.growth.town;

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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class TownVisitIT {


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
    @Autowired TownVisitService visits;
    @Test
    void defaultsPrivateRequiresAcceptedFriendAndEarnedMementosAndRevokesImmediately() throws Exception {
        Session owner=register("visit-owner@example.test"), guest=register("visit-guest@example.test"), stranger=register("visit-stranger@example.test");
        assertThat(visits.mine(owner.id()).enabled()).isFalse();
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.visit(guest.id(),owner.publicId()));
        jdbc.update("insert into friend_relationship(public_id,pair_key,requester_user_id,addressee_user_id,status) values('V2800000000000000000000001',?,?,?,'PENDING')",owner.id()+":"+guest.id(),owner.id(),guest.id());
        visits.save(owner.id(),new TownVisitService.SaveProfile(true,"meadow",java.util.List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.visit(guest.id(),owner.publicId()));
        jdbc.update("update friend_relationship set status='ACCEPTED' where requester_user_id=?",owner.id());
        assertThat(visits.directory(guest.id())).extracting(TownVisitService.DirectoryEntry::publicId).contains(owner.publicId());
        mvc.perform(get("/api/v1/town/visits/"+owner.publicId()).cookie(guest.access())).andExpect(status().isOk()).andExpect(jsonPath("$.data.style").value("meadow")).andExpect(jsonPath("$.data.email").doesNotExist()).andExpect(jsonPath("$.data.tasks").doesNotExist());
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.visit(stranger.id(),owner.publicId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.save(owner.id(),new TownVisitService.SaveProfile(true,"dusk",java.util.List.of("invented"))));
        String code=jdbc.queryForObject("select code from achievement order by id limit 1",String.class);
        jdbc.update("insert into user_achievement(user_id,achievement_code,earned_at) values(?,?,current_timestamp(3)) on duplicate key update earned_at=earned_at",owner.id(),code);
        visits.save(owner.id(),new TownVisitService.SaveProfile(true,"dusk",java.util.List.of(code)));
        assertThat(visits.visit(guest.id(),owner.publicId()).mementos()).extracting(TownVisitService.Memento::code).containsExactly(code);
        var command=new TownVisitService.SendPostcard("<b>好久不见</b>","request-key-0001");
        var first=visits.send(guest.id(),owner.publicId(),command);
        assertThat(visits.send(guest.id(),owner.publicId(),command).publicId()).isEqualTo(first.publicId());
        assertThat(visits.received(owner.id())).hasSize(1);
        assertThat(visits.received(stranger.id())).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.remove(stranger.id(),first.publicId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.send(guest.id(),owner.publicId(),new TownVisitService.SendPostcard("changed",command.requestKey())));
        visits.remove(owner.id(),first.publicId());
        assertThat(visits.received(owner.id())).isEmpty();
        visits.send(guest.id(),owner.publicId(),command);
        assertThat(visits.received(owner.id())).isEmpty(); // retry does not resurrect a removed card
        visits.save(owner.id(),new TownVisitService.SaveProfile(false,"dusk",java.util.List.of(code)));
        assertThat(visits.directory(guest.id())).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.visit(guest.id(),owner.publicId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.send(guest.id(),owner.publicId(),command));
        visits.save(owner.id(),new TownVisitService.SaveProfile(true,"dusk",java.util.List.of(code)));
        jdbc.update("update sys_user set status='DELETION_PENDING' where id=?",owner.id());
        assertThat(visits.directory(guest.id())).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.visit(guest.id(),owner.publicId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.send(guest.id(),owner.publicId(),command));
        jdbc.update("update sys_user set status='ACTIVE' where id=?",owner.id());
        jdbc.update("delete from friend_relationship where requester_user_id=?",owner.id());
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.visit(guest.id(),owner.publicId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class, () -> visits.send(guest.id(),owner.publicId(),command));
        mvc.perform(get("/api/v1/town/visits/mine")).andExpect(status().isUnauthorized());
    }
    private Session register(String email) throws Exception {
        MvcResult result=mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
            {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Visit Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
            """.formatted(email))).andExpect(status().isCreated()).andReturn();
        return jdbc.queryForObject("select id,public_id from sys_user where email_normalized=?",(rs,n)->new Session(rs.getLong(1),rs.getString(2),result.getResponse().getCookie("access_token")),email);
    }
    private record Session(long id,String publicId,Cookie access) {}
}
