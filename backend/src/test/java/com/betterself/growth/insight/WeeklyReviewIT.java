package com.betterself.growth.insight;

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
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *"
})
class WeeklyReviewIT {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired WeeklyReviewService reviews;
    @Autowired InsightService insights;

    @Test
    void buildsOwnedFactsAndRequiresConfirmationForAdjustments() {
        Fixture owner = fixture("review-owner@example.test", "300000000000000000000000", LocalDate.now().minusDays(8));
        Fixture other = fixture("review-other@example.test", "400000000000000000000000", LocalDate.now().minusDays(1));
        completed(owner, LocalDate.now().minusDays(8), "01");
        completed(owner, LocalDate.now(), "02");
        completed(other, LocalDate.now(), "03");
        completed(other, LocalDate.now(), "04");

        InsightService.Overview overview = insights.overview(owner.userId());
        assertThat(overview.recoveryCount()).isEqualTo(1);
        assertThat(overview.personalBestDailyActions()).isEqualTo(1);

        WeeklyReviewService.ReviewView initial = reviews.review(owner.userId(), owner.planPublicId());
        assertThat(initial.facts()).containsEntry("effectiveActions", 2);
        assertThat(initial.confirmedAdjustments()).isEmpty();

        WeeklyReviewService.ReviewView proposed = reviews.update(
            owner.userId(), owner.planPublicId(),
            new WeeklyReviewService.ReviewCommand("节奏基本合适", Map.of("weeklyFrequency", 3))
        );
        assertThat(proposed.proposedAdjustments()).containsEntry("weeklyFrequency", 3);
        assertThat(proposed.confirmedAdjustments()).isEmpty();
        assertThat(proposed.confirmedAt()).isNull();

        WeeklyReviewService.ReviewView confirmed = reviews.confirm(owner.userId(), owner.planPublicId(), null);
        assertThat(confirmed.confirmedAdjustments()).containsEntry("weeklyFrequency", 3);
        assertThat(confirmed.confirmedAt()).isNotNull();
    }

    private Fixture fixture(String email, String prefix, LocalDate scheduleDate) {
        String userPublicId = prefix + "01";
        String goalPublicId = prefix + "02";
        String planPublicId = prefix + "03";
        String taskPublicId = prefix + "04";
        String schedulePublicId = prefix + "05";
        jdbc.update(
            "insert into sys_user (public_id, email, email_normalized, password_hash, display_name, birth_date, timezone, status, role) values (?, ?, ?, 'unused', 'Review', '1990-01-01', 'Asia/Shanghai', 'ACTIVE', 'USER')",
            userPublicId, email, email
        );
        long userId = jdbc.queryForObject("select id from sys_user where public_id = ?", Long.class, userPublicId);
        long dimensionId = jdbc.queryForObject("select id from growth_dimension where is_system = 1 order by id limit 1", Long.class);
        String code = jdbc.queryForObject("select code from growth_dimension where id = ?", String.class, dimensionId);
        jdbc.update("insert into user_dimension (user_id, dimension_id) values (?, ?)", userId, dimensionId);
        jdbc.update(
            "insert into growth_goal (public_id, user_id, dimension_id, title, start_date, end_date) values (?, ?, ?, 'Review goal', ?, ?)",
            goalPublicId, userId, dimensionId, Date.valueOf(scheduleDate.minusDays(7)), Date.valueOf(scheduleDate.plusDays(30))
        );
        long goalId = jdbc.queryForObject("select id from growth_goal where public_id = ?", Long.class, goalPublicId);
        LocalDate monday = scheduleDate.minusDays(scheduleDate.getDayOfWeek().getValue() - 1L);
        jdbc.update(
            "insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone) values (?, ?, ?, ?, 'Asia/Shanghai')",
            planPublicId, userId, goalId, Date.valueOf(monday)
        );
        long planId = jdbc.queryForObject("select id from weekly_plan where public_id = ?", Long.class, planPublicId);
        jdbc.update(
            "insert into user_task (public_id, user_id, weekly_plan_id, title, estimated_minutes, difficulty, dimension_weights, planned_local_time) values (?, ?, ?, 'Review task', 20, 2, cast(? as json), '09:00:00')",
            taskPublicId, userId, planId, "{\"" + code + "\":10}"
        );
        long taskId = jdbc.queryForObject("select id from user_task where public_id = ?", Long.class, taskPublicId);
        Instant start = scheduleDate.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        jdbc.update(
            "insert into task_schedule (public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone, status) values (?, ?, ?, ?, ?, ?, 'Asia/Shanghai', 'DONE')",
            schedulePublicId, userId, taskId, Timestamp.from(start), Timestamp.from(start.plusSeconds(1200)), Date.valueOf(scheduleDate)
        );
        return new Fixture(userId, planPublicId, taskId, dimensionId, code);
    }

    private void completed(Fixture fixture, LocalDate date, String suffix) {
        int minute = Integer.parseInt(suffix);
        Instant start = date.atTime(10, minute).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        String schedulePublicId = "500000000000000000000000" + suffix;
        jdbc.update(
            "insert into task_schedule (public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone, status) values (?, ?, ?, ?, ?, ?, 'Asia/Shanghai', 'DONE')",
            schedulePublicId, fixture.userId(), fixture.taskId(), Timestamp.from(start), Timestamp.from(start.plusSeconds(1200)), Date.valueOf(date)
        );
        long scheduleId = jdbc.queryForObject("select id from task_schedule where public_id = ?", Long.class, schedulePublicId);
        jdbc.update(
            "insert into task_event (public_id, user_id, schedule_id, event_type, occurred_at, completion_ratio, experience_delta, task_title_snapshot, estimated_minutes_snapshot, difficulty_snapshot, dimension_weights_snapshot) values (?, ?, ?, 'COMPLETED', ?, 1, 4, 'Review task', 20, 2, cast(? as json))",
            "600000000000000000000000" + suffix, fixture.userId(), scheduleId, Timestamp.from(start),
            "{\"" + fixture.dimensionCode() + "\":10}"
        );
    }

    private record Fixture(long userId, String planPublicId, long taskId, long dimensionId, String dimensionCode) {
    }
}
