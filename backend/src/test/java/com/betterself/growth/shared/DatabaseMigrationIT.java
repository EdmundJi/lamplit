package com.betterself.growth.shared;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class DatabaseMigrationIT {

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
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsCompleteVersionedBaseline() {
        Integer tableCount = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = database()",
            Integer.class
        );
        Integer systemDimensions = jdbc.queryForObject(
            "select count(*) from growth_dimension where is_system = 1",
            Integer.class
        );
        Integer failedMigrations = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 0",
            Integer.class
        );
        Integer appliedMigrations = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1",
            Integer.class
        );

        // Batch1b-followup dropped 24 orphaned town_* tables (V31); the schema now has 53
        // tables (52 migration-created + flyway_schema_history). Floor kept with headroom.
        assertThat(tableCount).isGreaterThanOrEqualTo(50);
        assertThat(systemDimensions).isEqualTo(5);
        Integer publishedTemplates = jdbc.queryForObject(
            "select count(*) from task_template where review_status = 'PUBLISHED'",
            Integer.class
        );
        Integer rolesWithFiftyTemplates = jdbc.queryForObject(
            """
                select count(*) from (
                    select scene from task_template where review_status = 'PUBLISHED'
                    group by scene having count(*) = 50
                ) role_templates
                """,
            Integer.class
        );
        Integer achievements = jdbc.queryForObject("select count(*) from achievement", Integer.class);
        Integer titles = jdbc.queryForObject("select count(*) from title_def", Integer.class);

        assertThat(failedMigrations).isZero();
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(25);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema = database() and table_name = 'user_task' and column_name = 'weekly_plan_id'", String.class)).isEqualTo("YES");
        assertThat(publishedTemplates).isEqualTo(200);
        assertThat(rolesWithFiftyTemplates).isEqualTo(4);
        assertThat(achievements).isEqualTo(20);
        assertThat(titles).isEqualTo(14);
    }
}
