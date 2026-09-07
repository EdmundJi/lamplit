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
class TownPrivacyIT {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ExportService exports;
    @Autowired com.betterself.growth.town.companion.application.CompanionService companion;
    @Autowired DeletionService deletions;
    @Autowired com.betterself.growth.shared.id.PublicIdGenerator ids;

    @Test
    void exportsOnlyOwnedTownDataAndClearsNewPrivateStateOnDeletion() throws Exception {
        long owner=user("town-privacy-owner@example.test"), friend=user("town-privacy-friend@example.test"), other=user("town-privacy-other@example.test");
        companion.join(owner,new com.betterself.growth.town.companion.application.CompanionService.Join("OWNED_COMPANION","Asia/Shanghai"));
        companion.join(other,new com.betterself.growth.town.companion.application.CompanionService.Join("UNRELATED_COMPANION","Asia/Shanghai"));
        townData(owner,"meadow","GUIDE","OWNED_EVENT","OWNED_MEMENTO");
        townData(other,"dusk","POSTMAN","UNRELATED_EVENT","UNRELATED_MEMENTO");
        postcard(owner,friend,"RECEIVED_BY_OWNER");
        postcard(friend,owner,"SENT_BY_OWNER");
        postcard(other,friend,"UNRELATED_POSTCARD");
        var export=exports.create(owner,"town-privacy-isolated");
        String json=entry(exports.content(owner,export.publicId()),"town_experience.json");
        assertThat(json).contains("OWNED_COMPANION","OWNED_EVENT","OWNED_MEMENTO","RECEIVED_BY_OWNER","SENT_BY_OWNER","GUIDE","meadow");
        assertThat(json).doesNotContain("UNRELATED_COMPANION","UNRELATED_EVENT","UNRELATED_MEMENTO","UNRELATED_POSTCARD","POSTMAN","dusk");
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class,()->exports.content(other,export.publicId()));
        deletions.create(owner);
        jdbc.update("update deletion_request set process_after='2000-01-01 00:00:00' where user_id=?",owner);
        deletions.processDue();
        for(String table:List.of("town_visit_profile","town_visit_memento","town_companion_world"))
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from town_story_progress where town_user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from town_visit_postcard where owner_user_id=? or sender_user_id=?",Integer.class,owner,owner)).isZero();
        assertThat(jdbc.queryForObject("select player_response from town_event where town_user_id=?",String.class,owner)).isEqualTo("UNDECIDED");
        assertThat(jdbc.queryForObject("select attended_at is null from town_event where town_user_id=?",Boolean.class,owner)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from town_visit_profile where user_id=?",Integer.class,other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from town_companion_world where user_id=?",Integer.class,other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from town_story_progress where town_user_id=?",Integer.class,other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select body from town_visit_postcard where owner_user_id=?",String.class,other)).isEqualTo("UNRELATED_POSTCARD");
        assertThat(jdbc.queryForObject("select player_response from town_event where town_user_id=?",String.class,other)).isEqualTo("GOING");
    }
    private long user(String email) {
        String id=ids.next();
        jdbc.update("insert into sys_user(public_id,email,email_normalized,password_hash,display_name,birth_date,timezone) values(?,?,?,'test','Privacy Fixture','1990-01-01','Asia/Shanghai')",id,email,email);
        return jdbc.queryForObject("select id from sys_user where public_id=?",Long.class,id);
    }
    private void townData(long user,String style,String npc,String eventKind,String memento) {
        jdbc.update("insert into town_visit_profile(user_id,enabled,style,updated_at) values(?,true,?,current_timestamp(3))",user,style);
        jdbc.update("insert into town_visit_memento(user_id,achievement_code) values(?,?)",user,memento);
        jdbc.update("insert into town_story_progress(town_user_id,npc_code,stage,revision,paused,participation,updated_at) values(?,?,2,3,false,'JOINED',current_timestamp(3))",user,npc);
        jdbc.update("insert into town_event(public_id,town_user_id,host_npc_code,kind,venue,starts_at,player_response,attended_at) values(?,?,?,?,'park',current_timestamp(3),'GOING',current_timestamp(3))",ids.next(),user,npc,eventKind);
    }
    private void postcard(long owner,long sender,String body) {
        jdbc.update("insert into town_visit_postcard(public_id,owner_user_id,sender_user_id,request_key,body,created_at) values(?,?,?,?,?,current_timestamp(3))",ids.next(),owner,sender,ids.next(),body);
    }
    private String entry(byte[] archive,String filename) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) if(entry.getName().equals(filename)) return new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing archive entry: "+filename);
    }
}
