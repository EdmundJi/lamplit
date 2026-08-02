package com.betterself.growth.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "app.execution.expiry-delay-ms=3600000")
class TaskExecutionConcurrencyIT {

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

    @Autowired TaskExecutionService execution;
    @Autowired JdbcTemplate jdbc;

    private long userId;
    private long scheduleId;

    @BeforeEach
    void createFixture() {
        jdbc.update(
            "insert into sys_user (public_id, email, email_normalized, password_hash, display_name, birth_date, timezone, status, role) values ('20000000000000000000000001', 'concurrency@example.test', 'concurrency@example.test', 'not-used', 'Concurrency', '1990-01-01', 'Asia/Shanghai', 'ACTIVE', 'USER')"
        );
        userId = jdbc.queryForObject("select id from sys_user where email_normalized = 'concurrency@example.test'", Long.class);
        long dimensionId = jdbc.queryForObject("select id from growth_dimension where is_system = 1 order by id limit 1", Long.class);
        String code = jdbc.queryForObject("select code from growth_dimension where id = ?", String.class, dimensionId);
        jdbc.update("insert into user_dimension (user_id, dimension_id) values (?, ?)", userId, dimensionId);
        jdbc.update(
            "insert into growth_goal (public_id, user_id, dimension_id, title, start_date, end_date) values ('20000000000000000000000002', ?, ?, 'Concurrent goal', '2026-07-01', '2026-09-01')",
            userId, dimensionId
        );
        long goalId = jdbc.queryForObject("select id from growth_goal where public_id = '20000000000000000000000002'", Long.class);
        jdbc.update(
            "insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone) values ('20000000000000000000000003', ?, ?, '2026-07-27', 'Asia/Shanghai')",
            userId, goalId
        );
        long planId = jdbc.queryForObject("select id from weekly_plan where public_id = '20000000000000000000000003'", Long.class);
        jdbc.update(
            "insert into user_task (public_id, user_id, weekly_plan_id, title, estimated_minutes, difficulty, dimension_weights, planned_local_time) values ('20000000000000000000000004', ?, ?, 'Concurrent task', 30, 3, cast(? as json), '09:00:00')",
            userId, planId, "{\"" + code + "\":10}"
        );
        long taskId = jdbc.queryForObject("select id from user_task where public_id = '20000000000000000000000004'", Long.class);
        Instant start = Instant.now().plusSeconds(3600);
        jdbc.update(
            "insert into task_schedule (public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone) values ('20000000000000000000000005', ?, ?, ?, ?, ?, 'Asia/Shanghai')",
            userId, taskId, Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)),
            Date.valueOf(start.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
        );
        scheduleId = jdbc.queryForObject("select id from task_schedule where public_id = '20000000000000000000000005'", Long.class);
    }

    @Test
    void tenConcurrentDuplicatesCreateOneTerminalEvent() throws Exception {
        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<TaskExecutionService.EventResult>> futures = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return execution.record(
                        userId,
                        "20000000000000000000000005",
                        "ten-way-duplicate",
                        new TaskExecutionService.TaskEventCommand(TaskEventType.COMPLETED, 1.0, null, null)
                    );
                }));
            }
            ready.await();
            start.countDown();
            List<String> eventIds = new ArrayList<>();
            for (Future<TaskExecutionService.EventResult> future : futures) {
                eventIds.add(future.get().eventPublicId());
            }
            assertThat(eventIds).containsOnly(eventIds.getFirst());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from task_event where schedule_id = ? and event_type = 'COMPLETED'",
            Integer.class, scheduleId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from idempotency_record where user_id = ? and idempotency_key = 'ten-way-duplicate'",
            Integer.class, userId
        )).isEqualTo(1);
    }
}
