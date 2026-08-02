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
import java.sql.Timestamp;
import java.time.Clock;
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
        Long planId = planId(userId, planPublicId);
        ReviewView existing = find(userId, planId);
        if (existing != null) {
            return existing;
        }
        int planned = jdbc.queryForObject(
            "select count(*) from task_schedule s join user_task t on t.id = s.task_id where s.user_id = ? and t.weekly_plan_id = ?",
            Integer.class, userId, planId
        );
        List<WeeklyMetricsCalculator.MetricEvent> events = jdbc.query(
            """
                select e.event_type,
                       exists(select 1 from task_event r where r.reverses_event_id = e.id) reversed
                from task_event e join task_schedule s on s.id = e.schedule_id
                join user_task t on t.id = s.task_id
                where e.user_id = ? and t.weekly_plan_id = ? and e.event_type <> 'REVERSED'
                """,
            (rs, row) -> new WeeklyMetricsCalculator.MetricEvent(rs.getString("event_type"), rs.getBoolean("reversed")),
            userId, planId
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
            publicId, userId, planId, json(facts)
        );
        return find(userId, planId);
    }

    @Transactional
    public ReviewView update(long userId, String planPublicId, ReviewCommand command) {
        ReviewView current = review(userId, planPublicId);
        jdbc.update(
            "update weekly_review set user_reflection = ?, proposed_adjustments = cast(? as json), updated_at = ? where user_id = ? and public_id = ?",
            command.userReflection(), json(command.proposedAdjustments() == null ? Map.of() : command.proposedAdjustments()),
            Timestamp.from(clock.instant()), userId, current.publicId()
        );
        return find(userId, planId(userId, planPublicId));
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
        return find(userId, planId(userId, planPublicId));
    }

    private Long planId(long userId, String publicId) {
        Long id = jdbc.query(
            "select id from weekly_plan where user_id = ? and public_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            userId, publicId
        );
        if (id == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WEEKLY_PLAN_NOT_FOUND", "Resource not found");
        }
        return id;
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
}
