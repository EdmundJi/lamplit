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
    void exportsOnlyOwnedCompanionWorldAndClearsItOnDeletion() throws Exception {
        long owner=user("town-privacy-owner@example.test"), other=user("town-privacy-other@example.test");
        companion.join(owner,new com.betterself.growth.town.companion.application.CompanionService.Join("OWNED_COMPANION","Asia/Shanghai"));
        companion.join(other,new com.betterself.growth.town.companion.application.CompanionService.Join("UNRELATED_COMPANION","Asia/Shanghai"));
        var export=exports.create(owner,"town-privacy-isolated");
        String json=entry(exports.content(owner,export.publicId()),"town_experience.json");
        assertThat(json).contains("OWNED_COMPANION");
        assertThat(json).doesNotContain("UNRELATED_COMPANION");
        org.junit.jupiter.api.Assertions.assertThrows(com.betterself.growth.shared.api.ApiException.class,()->exports.content(other,export.publicId()));
        deletions.create(owner);
        jdbc.update("update deletion_request set process_after='2000-01-01 00:00:00' where user_id=?",owner);
        deletions.processDue();
        assertThat(jdbc.queryForObject("select count(*) from town_companion_world where user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from town_companion_world where user_id=?",Integer.class,other)).isEqualTo(1);
    }
    private long user(String email) {
        String id=ids.next();
        jdbc.update("insert into sys_user(public_id,email,email_normalized,password_hash,display_name,birth_date,timezone) values(?,?,?,'test','Privacy Fixture','1990-01-01','Asia/Shanghai')",id,email,email);
        return jdbc.queryForObject("select id from sys_user where public_id=?",Long.class,id);
    }
    private String entry(byte[] archive,String filename) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) if(entry.getName().equals(filename)) return new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing archive entry: "+filename);
    }
}
