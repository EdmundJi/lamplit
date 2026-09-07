package com.betterself.growth.goal;

import com.betterself.growth.execution.IdempotencyService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class QuickTaskService {
    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final IdempotencyService idempotency;
    private final DatabasePlanningService planning;

    public QuickTaskService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock,
                            IdempotencyService idempotency, DatabasePlanningService planning) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.idempotency = idempotency;
        this.planning = planning;
    }

    @Transactional
    public PlanningService.TaskView create(long userId, String key, Command command) {
        if (command == null || command.title() == null || command.title().isBlank()
            || command.title().trim().length() > 160) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_TITLE", "请输入 1 到 160 字的任务名称");
        }
        // Serialize retries and concurrent creates for this account, as task events do.
        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ? for update", String.class, userId);
        var begin = idempotency.begin(userId, "QUICK_TASK", key, command);
        if (begin.replay()) return idempotency.replay(begin, PlanningService.TaskView.class);
        LocalDate date = command.localDate() == null ? LocalDate.now(clock.withZone(ZoneId.of(timezone))) : command.localDate();
        String taskId = ids.next();
        jdbc.update("""
            insert into user_task (public_id, user_id, weekly_plan_id, title, estimated_minutes,
                difficulty, dimension_weights, role_code, planned_local_time, active_from, active_until)
            values (?, ?, null, ?, 5, 1, '{}', 'WORKER', '00:00:00', ?, ?)
            """, taskId, userId, command.title().trim(), Date.valueOf(date), Date.valueOf(date));
        Long internalId = jdbc.queryForObject("select id from user_task where user_id = ? and public_id = ?", Long.class, userId, taskId);
        // No deadline: an unfinished checklist item remains actionable on subsequent days.
        jdbc.update("""
            insert into task_schedule (public_id, user_id, task_id, planned_start_at, local_date, timezone, status)
            values (?, ?, ?, ?, ?, ?, 'PLANNED')
            """, ids.next(), userId, internalId, Timestamp.from(date.atStartOfDay(ZoneId.of(timezone)).toInstant()), Date.valueOf(date), timezone);
        var result = planning.task(userId, taskId);
        idempotency.complete(userId, "QUICK_TASK", key, result, taskId);
        return result;
    }

    public record Command(String title, LocalDate localDate) {}
}
