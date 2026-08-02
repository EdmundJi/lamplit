package com.betterself.growth.execution;

import com.betterself.growth.career.CareerRole;
import com.betterself.growth.career.RoleProgressionService;
import com.betterself.growth.partner.PartnerService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskExecutionService {

    private static final int DAILY_COMPLETION_LIMIT = 4;
    private static final TypeReference<Map<String, Integer>> WEIGHTS_TYPE = new TypeReference<>() {
    };
    private static final List<String> TERMINAL_TYPES = List.of("COMPLETED", "PARTIAL", "DEFERRED", "SKIPPED", "CANCELLED", "EXPIRED");

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final TaskStateMachine stateMachine;
    private final ExperienceCalculator experienceCalculator;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final RoleProgressionService roleProgression;
    private final PartnerService partners;

    public TaskExecutionService(
        JdbcTemplate jdbc,
        PublicIdGenerator ids,
        TaskStateMachine stateMachine,
        ExperienceCalculator experienceCalculator,
        IdempotencyService idempotency,
        ObjectMapper objectMapper,
        StringRedisTemplate redis,
        Clock clock,
        RoleProgressionService roleProgression,
        PartnerService partners
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.stateMachine = stateMachine;
        this.experienceCalculator = experienceCalculator;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.clock = clock;
        this.roleProgression = roleProgression;
        this.partners = partners;
    }

    @Transactional
    public EventResult record(long userId, String schedulePublicId, String key, TaskEventCommand command) {
        if (command == null || command.eventType() == null || command.eventType() == TaskEventType.REVERSED
            || command.eventType() == TaskEventType.EXPIRED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_EVENT", "Task event is invalid");
        }
        if (command.eventType() == TaskEventType.COMPLETED) {
            lockUser(userId);
        }
        ScheduleRow schedule = scheduleForUpdate(userId, schedulePublicId);
        String operation = "TASK_EVENT:" + schedulePublicId;
        IdempotencyService.BeginResult begin = idempotency.begin(userId, operation, key, command);
        if (begin.replay()) {
            return idempotency.replay(begin, EventResult.class);
        }
        String nextStatus = stateMachine.next(schedule.status(), command.eventType());
        if (command.eventType() == TaskEventType.COMPLETED) {
            ensureDailyCompletionAvailable(userId, schedule.localDate());
        }
        double ratio = validateRatio(command.eventType(), command.completionRatio());
        int remainingCap = remainingDailyExperience(userId, schedule.localDate());
        int experience = experienceCalculator.earned(
            command.eventType(), schedule.estimatedMinutes(), schedule.difficulty(), ratio, remainingCap
        );
        RoleProgressionService.ProgressChange roleChange = roleProgression.apply(
            userId, schedule.role(), experience
        );
        int coinDelta = partners.applyTaskCoinDelta(userId, experience);
        Instant occurredAt = clock.instant();
        String eventPublicId = ids.next();
        long eventId = insertEvent(
            eventPublicId, userId, schedule, command.eventType(), occurredAt, ratio, experience,
            roleChange.experienceDelta(), command.note(), null
        );
        jdbc.update(
            "update task_schedule set status = ?, version = version + 1, updated_at = ? where id = ?",
            nextStatus, Timestamp.from(occurredAt), schedule.id()
        );

        String deferredSchedulePublicId = null;
        if (command.eventType() == TaskEventType.DEFERRED) {
            deferredSchedulePublicId = createDeferredSchedule(userId, schedule, command.deferredStartAt(), occurredAt);
        }
        applyExperience(userId, schedule.dimensionWeights(), experience);
        writeOperationalEvents(userId, schedulePublicId, command.eventType(), occurredAt);

        EventResult result = new EventResult(
            eventPublicId, schedulePublicId, command.eventType(), nextStatus, experience,
            roleChange.experienceDelta(), roleChange.progress(), coinDelta, deferredSchedulePublicId, occurredAt
        );
        idempotency.complete(userId, operation, key, result, eventPublicId);
        evictOverviewAfterCommit(userId);
        return result;
    }

    @Transactional
    public EventResult reverse(long userId, String schedulePublicId, String eventPublicId, String key) {
        ScheduleRow schedule = scheduleForUpdate(userId, schedulePublicId);
        String operation = "REVERSE_TASK_EVENT:" + eventPublicId;
        IdempotencyService.BeginResult begin = idempotency.begin(userId, operation, key, Map.of("eventPublicId", eventPublicId));
        if (begin.replay()) {
            return idempotency.replay(begin, EventResult.class);
        }
        EventRow target = reversibleEvent(userId, schedule.id(), eventPublicId);
        RoleProgressionService.ProgressChange roleChange = roleProgression.apply(
            userId, target.role(), -target.roleExperienceDelta()
        );
        int coinDelta = partners.applyTaskCoinDelta(userId, -target.experienceDelta());
        String nextStatus = hasActiveStartedEvent(schedule.id(), target.id()) ? "IN_PROGRESS" : "PLANNED";
        Instant occurredAt = clock.instant();
        String reversalPublicId = ids.next();
        insertEvent(
            reversalPublicId, userId, schedule, TaskEventType.REVERSED, occurredAt,
            target.completionRatio(), -target.experienceDelta(), roleChange.experienceDelta(), null, target.id()
        );
        jdbc.update(
            "update task_schedule set status = ?, version = version + 1, updated_at = ? where id = ?",
            nextStatus, Timestamp.from(occurredAt), schedule.id()
        );
        if (target.eventType() == TaskEventType.DEFERRED) {
            jdbc.update(
                "update task_schedule set status = 'CANCELLED', version = version + 1, updated_at = ? where deferred_from_id = ? and status = 'PLANNED'",
                Timestamp.from(occurredAt), schedule.id()
            );
        }
        applyExperience(userId, schedule.dimensionWeights(), -target.experienceDelta());
        writeOperationalEvents(userId, schedulePublicId, TaskEventType.REVERSED, occurredAt);
        EventResult result = new EventResult(
            reversalPublicId, schedulePublicId, TaskEventType.REVERSED, nextStatus,
            -target.experienceDelta(), roleChange.experienceDelta(), roleChange.progress(), coinDelta, null, occurredAt
        );
        idempotency.complete(userId, operation, key, result, reversalPublicId);
        evictOverviewAfterCommit(userId);
        return result;
    }

    public List<ScheduleView> schedules(long userId, java.time.LocalDate localDate) {
        return jdbc.query(
            """
                select s.public_id, t.public_id task_public_id, t.title, s.planned_start_at, s.planned_end_at,
                       s.local_date, s.timezone, s.status, s.deferred_from_id, t.role_code
                from task_schedule s join user_task t on t.id = s.task_id
                where s.user_id = ? and (? is null or s.local_date = ?)
                order by s.planned_start_at
                """,
            (rs, row) -> new ScheduleView(
                rs.getString("public_id"), rs.getString("task_public_id"), rs.getString("title"),
                rs.getTimestamp("planned_start_at").toInstant(),
                rs.getTimestamp("planned_end_at") == null ? null : rs.getTimestamp("planned_end_at").toInstant(),
                rs.getDate("local_date").toLocalDate(), rs.getString("timezone"), rs.getString("status"),
                rs.getObject("deferred_from_id") != null, rs.getString("role_code"),
                CareerRole.valueOf(rs.getString("role_code")).displayName()
            ),
            userId, localDate == null ? null : Date.valueOf(localDate), localDate == null ? null : Date.valueOf(localDate)
        );
    }

    private ScheduleRow scheduleForUpdate(long userId, String publicId) {
        ScheduleRow row = jdbc.query(
            """
                select s.id, s.public_id, s.task_id, s.planned_start_at, s.planned_end_at, s.local_date,
                       s.timezone, s.status, t.title, t.estimated_minutes, t.difficulty,
                       t.dimension_weights, t.role_code
                from task_schedule s join user_task t on t.id = s.task_id
                where s.user_id = ? and s.public_id = ? for update
                """,
            rs -> rs.next() ? new ScheduleRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getLong("task_id"),
                rs.getTimestamp("planned_start_at").toInstant(),
                rs.getTimestamp("planned_end_at") == null ? null : rs.getTimestamp("planned_end_at").toInstant(),
                rs.getDate("local_date").toLocalDate(), rs.getString("timezone"), rs.getString("status"),
                rs.getString("title"), rs.getInt("estimated_minutes"), rs.getInt("difficulty"),
                readWeights(rs.getString("dimension_weights")), CareerRole.valueOf(rs.getString("role_code"))
            ) : null,
            userId, publicId
        );
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_SCHEDULE_NOT_FOUND", "Resource not found");
        }
        return row;
    }

    private double validateRatio(TaskEventType eventType, Double value) {
        if (eventType == TaskEventType.PARTIAL) {
            if (value == null || value <= 0 || value >= 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPLETION_RATIO", "Partial completion ratio must be between 0 and 1");
            }
            return value;
        }
        return eventType == TaskEventType.COMPLETED ? 1.0 : 0.0;
    }

    private void lockUser(long userId) {
        jdbc.queryForObject("select id from sys_user where id = ? for update", Long.class, userId);
    }

    private void ensureDailyCompletionAvailable(long userId, java.time.LocalDate localDate) {
        Integer completed = jdbc.queryForObject(
            "select count(*) from task_schedule where user_id = ? and local_date = ? and status = 'DONE'",
            Integer.class, userId, Date.valueOf(localDate)
        );
        int completedCount = completed == null ? 0 : completed;
        if (completedCount >= DAILY_COMPLETION_LIMIT) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "DAILY_TASK_COMPLETION_LIMIT_REACHED",
                "每天最多完成 4 个任务",
                Map.of("limit", DAILY_COMPLETION_LIMIT, "completed", completedCount, "localDate", localDate.toString())
            );
        }
    }

    private int remainingDailyExperience(long userId, java.time.LocalDate localDate) {
        Integer earned = jdbc.queryForObject(
            """
                select coalesce(sum(e.experience_delta), 0) from task_event e
                join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and s.local_date = ?
                """,
            Integer.class, userId, Date.valueOf(localDate)
        );
        return Math.max(0, 100 - (earned == null ? 0 : earned));
    }

    private long insertEvent(
        String publicId,
        long userId,
        ScheduleRow schedule,
        TaskEventType eventType,
        Instant occurredAt,
        double ratio,
        int experience,
        int roleExperience,
        String note,
        Long reversesEventId
    ) {
        jdbc.update(
            """
                insert into task_event (
                    public_id, user_id, schedule_id, event_type, occurred_at, completion_ratio,
                    experience_delta, role_experience_delta, task_title_snapshot, estimated_minutes_snapshot,
                    difficulty_snapshot, role_code_snapshot, dimension_weights_snapshot, note, reverses_event_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json), ?, ?)
                """,
            publicId, userId, schedule.id(), eventType.name(), Timestamp.from(occurredAt),
            eventType == TaskEventType.PARTIAL || eventType == TaskEventType.COMPLETED ? ratio : null,
            experience, roleExperience, schedule.title(), schedule.estimatedMinutes(), schedule.difficulty(),
            schedule.role().name(),
            json(schedule.dimensionWeights()), note, reversesEventId
        );
        return jdbc.queryForObject("select id from task_event where public_id = ?", Long.class, publicId);
    }

    private String createDeferredSchedule(long userId, ScheduleRow schedule, Instant requestedStart, Instant occurredAt) {
        if (requestedStart == null || !requestedStart.isAfter(occurredAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEFERRED_START", "Deferred start must be in the future");
        }
        String publicId = ids.next();
        ZoneId zone = ZoneId.of(schedule.timezone());
        jdbc.update(
            """
                insert into task_schedule (
                    public_id, user_id, task_id, planned_start_at, planned_end_at, local_date,
                    timezone, status, deferred_from_id
                ) values (?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?)
                """,
            publicId, userId, schedule.taskId(), Timestamp.from(requestedStart),
            Timestamp.from(requestedStart.plusSeconds(schedule.estimatedMinutes() * 60L)),
            Date.valueOf(requestedStart.atZone(zone).toLocalDate()), schedule.timezone(), schedule.id()
        );
        return publicId;
    }

    private EventRow reversibleEvent(long userId, long scheduleId, String eventPublicId) {
        EventRow row = jdbc.query(
            """
                select e.id, e.event_type, e.completion_ratio, e.experience_delta,
                       e.role_experience_delta, e.role_code_snapshot
                from task_event e
                where e.user_id = ? and e.schedule_id = ? and e.public_id = ?
                  and e.event_type in ('COMPLETED','PARTIAL','DEFERRED','SKIPPED','CANCELLED')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                  and e.id = (
                    select max(latest.id) from task_event latest
                    where latest.schedule_id = e.schedule_id
                      and latest.event_type in ('COMPLETED','PARTIAL','DEFERRED','SKIPPED','CANCELLED')
                      and not exists (select 1 from task_event reversed where reversed.reverses_event_id = latest.id)
                  )
                """,
            rs -> rs.next() ? new EventRow(
                rs.getLong("id"), TaskEventType.valueOf(rs.getString("event_type")),
                rs.getDouble("completion_ratio"), rs.getInt("experience_delta"),
                rs.getInt("role_experience_delta"), CareerRole.valueOf(rs.getString("role_code_snapshot"))
            ) : null,
            userId, scheduleId, eventPublicId
        );
        if (row == null) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_EVENT_NOT_REVERSIBLE", "Only the latest terminal event can be reversed");
        }
        return row;
    }

    private boolean hasActiveStartedEvent(long scheduleId, long beforeEventId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from task_event where schedule_id = ? and event_type = 'STARTED' and id < ?",
            Integer.class, scheduleId, beforeEventId
        );
        return count != null && count > 0;
    }

    private void applyExperience(long userId, Map<String, Integer> weights, int totalDelta) {
        if (totalDelta == 0) {
            return;
        }
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        int remaining = totalDelta;
        int index = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            index++;
            int delta = index == weights.size()
                ? remaining
                : (int) Math.round((double) totalDelta * entry.getValue() / totalWeight);
            remaining -= delta;
            jdbc.update(
                """
                    update user_dimension ud join growth_dimension d on d.id = ud.dimension_id
                    set ud.experience = greatest(0, cast(ud.experience as signed) + ?),
                        ud.level = floor(sqrt(ud.experience) / 10) + 1,
                        ud.updated_at = UTC_TIMESTAMP(3)
                    where ud.user_id = ? and d.code = ?
                    """,
                delta, userId, entry.getKey()
            );
        }
    }

    private void writeOperationalEvents(long userId, String schedulePublicId, TaskEventType type, Instant occurredAt) {
        jdbc.update(
            "insert into outbox_event (public_id, aggregate_type, aggregate_public_id, event_type, payload, available_at) values (?, 'TASK_SCHEDULE', ?, ?, cast(? as json), ?)",
            ids.next(), schedulePublicId, "TASK_" + type.name(),
            json(Map.of("scheduleId", schedulePublicId, "eventType", type.name())), Timestamp.from(occurredAt)
        );
        jdbc.update(
            "insert into product_event (public_id, analytics_user_id, event_name, properties, occurred_at, expires_at) values (?, ?, ?, cast(? as json), ?, date_add(?, interval 13 month))",
            ids.next(), analyticsUserId(userId), "task_event_recorded", json(Map.of("eventType", type.name())),
            Timestamp.from(occurredAt), Timestamp.from(occurredAt)
        );
    }

    private void evictOverviewAfterCommit(long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redis.delete("insights:overview:v2:" + userId);
                } catch (RuntimeException ignored) {
                    // Redis is an optional acceleration layer; MySQL remains authoritative.
                }
            }
        });
    }

    private String analyticsUserId(long userId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("growth-user:" + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, Integer> readWeights(String value) {
        try {
            return objectMapper.readValue(value, WEIGHTS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored dimension weights are invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize JSON", exception);
        }
    }

    public record TaskEventCommand(
        TaskEventType eventType,
        Double completionRatio,
        String note,
        Instant deferredStartAt
    ) {
    }

    public record EventResult(
        String eventPublicId,
        String schedulePublicId,
        TaskEventType eventType,
        String scheduleStatus,
        int experienceDelta,
        int roleExperienceDelta,
        RoleProgressionService.RoleProgressView roleProgress,
        int coinDelta,
        String deferredSchedulePublicId,
        Instant occurredAt
    ) {
    }

    public record ScheduleView(
        String publicId,
        String taskPublicId,
        String taskTitle,
        Instant plannedStartAt,
        Instant plannedEndAt,
        java.time.LocalDate localDate,
        String timezone,
        String status,
        boolean deferred,
        String roleCode,
        String roleName
    ) {
    }

    private record ScheduleRow(
        long id,
        String publicId,
        long taskId,
        Instant plannedStartAt,
        Instant plannedEndAt,
        java.time.LocalDate localDate,
        String timezone,
        String status,
        String title,
        int estimatedMinutes,
        int difficulty,
        Map<String, Integer> dimensionWeights,
        CareerRole role
    ) {
    }

    private record EventRow(
        long id,
        TaskEventType eventType,
        double completionRatio,
        int experienceDelta,
        int roleExperienceDelta,
        CareerRole role
    ) {
    }
}
