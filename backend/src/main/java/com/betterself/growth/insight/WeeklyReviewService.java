package com.betterself.growth.insight;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WeeklyReviewService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final WeeklyMetricsCalculator calculator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WeeklyReviewService(
        JdbcTemplate jdbc,
        PublicIdGenerator ids,
        WeeklyMetricsCalculator calculator,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ReviewView review(long userId, String planPublicId) {
        PlanContext plan = plan(userId, planPublicId);
        ReviewView existing = find(userId, plan.id());
        if (existing != null) {
            return existing;
        }
        LocalDate weekEnd = plan.weekStart().plusDays(6);
        int planned = jdbc.queryForObject(
            """
                select count(*) from task_schedule s
                join user_task t on t.id = s.task_id
                join weekly_plan task_plan on task_plan.id = t.weekly_plan_id
                where s.user_id = ? and task_plan.goal_id = ? and s.local_date between ? and ?
                """,
            Integer.class, userId, plan.goalId(), Date.valueOf(plan.weekStart()), Date.valueOf(weekEnd)
        );
        List<WeeklyMetricsCalculator.MetricEvent> events = jdbc.query(
            """
                select e.event_type,
                       exists(select 1 from task_event r where r.reverses_event_id = e.id) reversed
                from task_event e join task_schedule s on s.id = e.schedule_id
                join user_task t on t.id = s.task_id
                join weekly_plan task_plan on task_plan.id = t.weekly_plan_id
                where e.user_id = ? and task_plan.goal_id = ? and s.local_date between ? and ?
                  and e.event_type <> 'REVERSED'
                """,
            (rs, row) -> new WeeklyMetricsCalculator.MetricEvent(rs.getString("event_type"), rs.getBoolean("reversed")),
            userId, plan.goalId(), Date.valueOf(plan.weekStart()), Date.valueOf(weekEnd)
        );
        WeeklyMetricsCalculator.WeeklyMetrics metrics = calculator.calculate(
            new WeeklyMetricsCalculator.WeeklyFacts(planned, events)
        );
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("plannedActions", metrics.plannedActions());
        facts.put("effectiveActions", metrics.effectiveActions());
        facts.put("fulfillmentRate", metrics.fulfillmentRate());
        String publicId = ids.next();
        jdbc.update(
            "insert into weekly_review (public_id, user_id, weekly_plan_id, facts) values (?, ?, ?, cast(? as json))",
            publicId, userId, plan.id(), json(facts)
        );
        return find(userId, plan.id());
    }

    @Transactional
    public ReviewView update(long userId, String planPublicId, ReviewCommand command) {
        ReviewView current = review(userId, planPublicId);
        jdbc.update(
            "update weekly_review set user_reflection = ?, proposed_adjustments = cast(? as json), updated_at = ? where user_id = ? and public_id = ?",
            command.userReflection(), json(command.proposedAdjustments() == null ? Map.of() : command.proposedAdjustments()),
            Timestamp.from(clock.instant()), userId, current.publicId()
        );
        return find(userId, plan(userId, planPublicId).id());
    }

    @Transactional
    public ReviewView confirm(long userId, String planPublicId, ConfirmationCommand command) {
        ReviewView current = review(userId, planPublicId);
        Map<String, Object> confirmed = command != null && command.adjustments() != null
            ? command.adjustments() : current.proposedAdjustments();
        jdbc.update(
            "update weekly_review set confirmed_adjustments = cast(? as json), confirmed_at = ?, updated_at = ? where user_id = ? and public_id = ?",
            json(confirmed), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), userId, current.publicId()
        );
        return find(userId, plan(userId, planPublicId).id());
    }

    private PlanContext plan(long userId, String publicId) {
        PlanContext plan = jdbc.query(
            "select id, goal_id, week_start_date from weekly_plan where user_id = ? and public_id = ?",
            rs -> rs.next()
                ? new PlanContext(rs.getLong("id"), rs.getLong("goal_id"), rs.getDate("week_start_date").toLocalDate())
                : null,
            userId, publicId
        );
        if (plan == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WEEKLY_PLAN_NOT_FOUND", "Resource not found");
        }
        return plan;
    }

    private ReviewView find(long userId, long planId) {
        return jdbc.query(
            """
                select r.public_id, p.public_id plan_public_id, r.facts, r.user_reflection,
                       r.proposed_adjustments, r.confirmed_adjustments, r.confirmed_at
                from weekly_review r join weekly_plan p on p.id = r.weekly_plan_id
                where r.user_id = ? and r.weekly_plan_id = ?
                """,
            rs -> rs.next() ? new ReviewView(
                rs.getString("public_id"), rs.getString("plan_public_id"), readMap(rs.getString("facts")),
                rs.getString("user_reflection"), readMap(rs.getString("proposed_adjustments")),
                readMap(rs.getString("confirmed_adjustments")),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant()
            ) : null,
            userId, planId
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize review", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored review is invalid", exception);
        }
    }

    public record ReviewCommand(String userReflection, Map<String, Object> proposedAdjustments) {
    }

    public record ConfirmationCommand(Map<String, Object> adjustments) {
    }

    public record ReviewView(
        String publicId,
        String planPublicId,
        Map<String, Object> facts,
        String userReflection,
        Map<String, Object> proposedAdjustments,
        Map<String, Object> confirmedAdjustments,
        java.time.Instant confirmedAt
    ) {
    }

    private record PlanContext(long id, long goalId, LocalDate weekStart) {
    }
}
