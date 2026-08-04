package com.betterself.growth.goal;

import com.betterself.growth.career.CareerRole;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatabasePlanningService implements PlanningService {

    private static final Set<String> PLAN_STATUSES = Set.of("DRAFT", "CONFIRMED", "COMPLETED", "ARCHIVED");

    private static final TypeReference<Map<String, Integer>> WEIGHTS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final RecurrenceExpander recurrenceExpander;
    private final ObjectMapper objectMapper;

    public DatabasePlanningService(
        JdbcTemplate jdbc,
        PublicIdGenerator ids,
        RecurrenceExpander recurrenceExpander,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.recurrenceExpander = recurrenceExpander;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WeeklyPlanView createWeeklyPlan(long userId, CreateWeeklyPlanCommand command) {
        if (command == null || command.weekStartDate() == null || command.weekStartDate().getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEK_START", "Week start must be a Monday");
        }
        ZoneId timezone = parseZone(command.timezone());
        GoalRow goal = jdbc.query(
            "select id, start_date, end_date from growth_goal where user_id = ? and public_id = ? and status in ('ACTIVE', 'DRAFT')",
            rs -> rs.next() ? new GoalRow(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate()) : null,
            userId, command.goalPublicId()
        );
        if (goal == null) {
            throw notFound("GOAL_NOT_FOUND");
        }
        if (command.weekStartDate().plusDays(6).isBefore(goal.startDate()) || command.weekStartDate().isAfter(goal.endDate())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_OUTSIDE_GOAL", "Weekly plan must overlap the goal period");
        }
        String publicId = ids.next();
        try {
            jdbc.update(
                """
                    insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone, status)
                    values (?, ?, ?, ?, ?, 'DRAFT')
                    """,
                publicId, userId, goal.id(), Date.valueOf(command.weekStartDate()), timezone.getId()
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "WEEKLY_PLAN_EXISTS", "A weekly plan already exists for this goal and week");
        }
        return weeklyPlan(userId, publicId);
    }

    @Override
    @Transactional
    public TaskView createTask(long userId, CreateTaskCommand command) {
        if (command == null || command.title() == null || command.title().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_TITLE", "Task title is required");
        }
        if (command.estimatedMinutes() < 5 || command.estimatedMinutes() > 240) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ESTIMATED_MINUTES", "Estimated minutes must be between 5 and 240");
        }
        if (command.difficulty() < 1 || command.difficulty() > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIFFICULTY", "Difficulty must be between 1 and 3");
        }
        Map<String, Integer> weights = validateWeights(userId, command.dimensionWeights());
        CareerRole role = command.roleCode() == null
            ? CareerRole.infer(weights)
            : CareerRole.parse(command.roleCode());
        Long sourceTemplateId = sourceTemplate(command.sourceTemplatePublicId(), role);
        if (command.rrule() != null && !command.rrule().isBlank()) {
            RecurrenceRule.parse(command.rrule());
        }
        boolean goalLinked = command.goalPublicId() != null && !command.goalPublicId().isBlank();
        PlanRow plan;
        LocalDate activeFrom;
        LocalDate activeUntil;
        if (goalLinked) {
            GoalRow goal = goalRow(userId, command.goalPublicId());
            activeFrom = command.activeFrom() == null ? goal.startDate() : command.activeFrom();
            activeUntil = command.activeUntil() == null ? goal.endDate() : command.activeUntil();
            if (activeFrom.isBefore(goal.startDate()) || activeUntil.isAfter(goal.endDate())) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TASK_OUTSIDE_GOAL",
                    "Task active dates must be within the goal period"
                );
            }
            plan = ensureCompatibilityPlans(userId, goal.id(), activeFrom, activeUntil);
        } else {
            if (command.weeklyPlanPublicId() == null || command.weeklyPlanPublicId().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_PARENT_REQUIRED", "A goal or weekly plan is required");
            }
            plan = planRow(userId, command.weeklyPlanPublicId());
            activeFrom = command.activeFrom() == null ? plan.weekStartDate() : command.activeFrom();
            activeUntil = command.activeUntil() == null ? plan.weekStartDate().plusDays(6) : command.activeUntil();
        }
        if (activeUntil.isBefore(activeFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_DATES", "Task active dates are invalid");
        }
        LocalTime localTime = command.plannedLocalTime() == null ? LocalTime.of(9, 0) : command.plannedLocalTime();
        String publicId = ids.next();
        jdbc.update(
            """
                insert into user_task (
                    public_id, user_id, weekly_plan_id, source_template_id, role_code,
                    title, notes, estimated_minutes, difficulty,
                    rrule, dimension_weights, planned_local_time, active_from, active_until
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            publicId, userId, plan.id(), sourceTemplateId, role.name(),
            command.title().trim(), command.notes(), command.estimatedMinutes(),
            command.difficulty(), blankToNull(command.rrule()), json(weights), Time.valueOf(localTime),
            Date.valueOf(activeFrom), Date.valueOf(activeUntil)
        );
        if (goalLinked) {
            long taskId = jdbc.queryForObject(
                "select id from user_task where user_id = ? and public_id = ?",
                Long.class,
                userId,
                publicId
            );
            materializeTask(
                userId,
                taskId,
                blankToNull(command.rrule()),
                localTime,
                activeFrom,
                activeUntil,
                command.estimatedMinutes(),
                parseZone(plan.timezone())
            );
        }
        return task(userId, publicId);
    }

    @Override
    @Transactional
    public WeeklyPlanView updateWeeklyPlan(long userId, String publicId, UpdateWeeklyPlanCommand command) {
        if (command == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEKLY_PLAN", "Weekly plan update is required");
        }
        WeeklyPlanView current = weeklyPlan(userId, publicId);
        String timezone = command.timezone() == null ? current.timezone() : parseZone(command.timezone()).getId();
        String status = command.status() == null ? current.status() : command.status().toUpperCase(java.util.Locale.ROOT);
        if (!PLAN_STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLAN_STATUS", "Weekly plan status is invalid");
        }
        jdbc.update(
            "update weekly_plan set timezone = ?, status = ?, confirmed_at = case when ? = 'CONFIRMED' then coalesce(confirmed_at, UTC_TIMESTAMP(3)) else confirmed_at end, updated_at = UTC_TIMESTAMP(3) where user_id = ? and public_id = ?",
            timezone, status, status, userId, publicId
        );
        return weeklyPlan(userId, publicId);
    }

    @Override
    @Transactional
    public TaskView updateTask(long userId, String publicId, UpdateTaskCommand command) {
        if (command == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK", "Task update is required");
        }
        TaskView current = task(userId, publicId);
        String title = command.title() == null ? current.title() : command.title().trim();
        if (title.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_TITLE", "Task title is required");
        }
        int minutes = command.estimatedMinutes() == null ? current.estimatedMinutes() : command.estimatedMinutes();
        if (minutes < 5 || minutes > 240) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ESTIMATED_MINUTES", "Estimated minutes must be between 5 and 240");
        }
        int difficulty = command.difficulty() == null ? current.difficulty() : command.difficulty();
        if (difficulty < 1 || difficulty > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIFFICULTY", "Difficulty must be between 1 and 3");
        }
        String rrule = command.rrule() == null ? current.rrule() : blankToNull(command.rrule());
        if (rrule != null) {
            RecurrenceRule.parse(rrule);
        }
        Map<String, Integer> weights = command.dimensionWeights() == null
            ? current.dimensionWeights() : validateWeights(userId, command.dimensionWeights());
        LocalDate activeFrom = command.activeFrom() == null ? current.activeFrom() : command.activeFrom();
        LocalDate activeUntil = command.activeUntil() == null ? current.activeUntil() : command.activeUntil();
        if (activeFrom != null && activeUntil != null && activeUntil.isBefore(activeFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_DATES", "Task active dates are invalid");
        }
        jdbc.update(
            """
                update user_task set title = ?, notes = ?, estimated_minutes = ?, difficulty = ?, rrule = ?,
                    dimension_weights = ?, planned_local_time = ?, active_from = ?, active_until = ?, updated_at = UTC_TIMESTAMP(3)
                where user_id = ? and public_id = ?
                """,
            title, command.notes() == null ? current.notes() : command.notes(), minutes, difficulty, rrule,
            json(weights), Time.valueOf(command.plannedLocalTime() == null ? current.plannedLocalTime() : command.plannedLocalTime()),
            activeFrom == null ? null : Date.valueOf(activeFrom), activeUntil == null ? null : Date.valueOf(activeUntil),
            userId, publicId
        );
        return task(userId, publicId);
    }

    @Override
    @Transactional
    public List<TaskScheduleView> materializeWeek(long userId, String weeklyPlanPublicId) {
        PlanRow plan = planRow(userId, weeklyPlanPublicId);
        ZoneId timezone = parseZone(plan.timezone());
        LocalDate weekEnd = plan.weekStartDate().plusDays(6);
        List<TaskRow> tasks = taskRows(userId, plan.id());
        for (TaskRow task : tasks) {
            List<PlannedOccurrence> occurrences;
            LocalDate activeFrom = task.activeFrom() == null ? plan.weekStartDate() : task.activeFrom();
            LocalDate activeUntil = task.activeUntil() == null ? weekEnd : task.activeUntil();
            LocalDate expansionEnd = activeUntil.isBefore(weekEnd) ? activeUntil : weekEnd;
            if (expansionEnd.isBefore(activeFrom)) {
                continue;
            }
            if (task.rrule() == null) {
                LocalDate date = activeFrom;
                occurrences = date.isBefore(plan.weekStartDate()) || date.isAfter(weekEnd)
                    ? List.of()
                    : List.of(new PlannedOccurrence(date, date.atTime(task.localTime()).atZone(timezone).toInstant()));
            } else {
                occurrences = recurrenceExpander.expand(
                    RecurrenceRule.parse(task.rrule()), activeFrom, task.localTime(), timezone, expansionEnd
                ).stream().filter(item -> !item.localDate().isBefore(plan.weekStartDate())).toList();
            }
            for (PlannedOccurrence occurrence : occurrences) {
                jdbc.update(
                    """
                        insert ignore into task_schedule (
                            public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone, status
                        ) values (?, ?, ?, ?, ?, ?, ?, 'PLANNED')
                        """,
                    ids.next(), userId, task.id(), Timestamp.from(occurrence.instant()),
                    Timestamp.from(occurrence.instant().plusSeconds(task.estimatedMinutes() * 60L)),
                    Date.valueOf(occurrence.localDate()), timezone.getId()
                );
            }
        }
        return schedules(userId, plan.id());
    }

    public List<WeeklyPlanView> weeklyPlans(long userId, LocalDate weekStart) {
        return jdbc.query(
            """
                select p.public_id, g.public_id goal_public_id, p.week_start_date, p.timezone, p.status
                from weekly_plan p join growth_goal g on g.id = p.goal_id
                where p.user_id = ? and (? is null or p.week_start_date = ?)
                order by p.week_start_date desc
                """,
            (rs, row) -> weeklyPlanView(rs),
            userId, weekStart == null ? null : Date.valueOf(weekStart), weekStart == null ? null : Date.valueOf(weekStart)
        );
    }

    public List<TaskView> tasks(long userId, String planPublicId, String goalPublicId) {
        String sql = """
            select t.public_id, p.public_id plan_public_id, g.public_id goal_public_id,
                   tt.public_id source_template_public_id,
                   t.role_code, t.title, t.notes, t.estimated_minutes,
                   t.difficulty, t.rrule, t.dimension_weights, t.planned_local_time,
                   t.active_from, t.active_until, t.active
            from user_task t join weekly_plan p on p.id = t.weekly_plan_id
            join growth_goal g on g.id = p.goal_id
            left join task_template tt on tt.id = t.source_template_id
            where t.user_id = ? and (? is null or p.public_id = ?) and (? is null or g.public_id = ?)
            order by t.active desc, t.active_from, t.created_at
            """;
        return jdbc.query(sql, (rs, row) -> new TaskView(
            rs.getString("public_id"), rs.getString("plan_public_id"), rs.getString("goal_public_id"),
            rs.getString("title"), rs.getString("notes"),
            rs.getInt("estimated_minutes"), rs.getInt("difficulty"), rs.getString("rrule"),
            readWeights(rs.getString("dimension_weights")), rs.getTime("planned_local_time").toLocalTime(),
            rs.getDate("active_from") == null ? null : rs.getDate("active_from").toLocalDate(),
            rs.getDate("active_until") == null ? null : rs.getDate("active_until").toLocalDate(), rs.getBoolean("active"),
            rs.getString("source_template_public_id"), rs.getString("role_code")
        ), userId, planPublicId, planPublicId, goalPublicId, goalPublicId);
    }

    public TaskView task(long userId, String publicId) {
        TaskView view = jdbc.query(
            """
                select t.public_id, p.public_id plan_public_id, g.public_id goal_public_id,
                       tt.public_id source_template_public_id,
                       t.role_code, t.title, t.notes, t.estimated_minutes,
                       t.difficulty, t.rrule, t.dimension_weights, t.planned_local_time,
                       t.active_from, t.active_until, t.active
                from user_task t join weekly_plan p on p.id = t.weekly_plan_id
                join growth_goal g on g.id = p.goal_id
                left join task_template tt on tt.id = t.source_template_id
                where t.user_id = ? and t.public_id = ?
                """,
            rs -> rs.next() ? taskView(rs) : null,
            userId, publicId
        );
        if (view == null) {
            throw notFound("TASK_NOT_FOUND");
        }
        return view;
    }

    @Transactional
    public TaskView setTaskActive(long userId, String publicId, boolean active) {
        int changed = jdbc.update("update user_task set active = ?, updated_at = UTC_TIMESTAMP(3) where user_id = ? and public_id = ?",
            active, userId, publicId);
        if (changed == 0) {
            throw notFound("TASK_NOT_FOUND");
        }
        return task(userId, publicId);
    }

    private WeeklyPlanView weeklyPlan(long userId, String publicId) {
        WeeklyPlanView view = jdbc.query(
            """
                select p.public_id, g.public_id goal_public_id, p.week_start_date, p.timezone, p.status
                from weekly_plan p join growth_goal g on g.id = p.goal_id
                where p.user_id = ? and p.public_id = ?
                """,
            rs -> rs.next() ? weeklyPlanView(rs) : null,
            userId, publicId
        );
        if (view == null) {
            throw notFound("WEEKLY_PLAN_NOT_FOUND");
        }
        return view;
    }

    private PlanRow planRow(long userId, String publicId) {
        PlanRow row = jdbc.query(
            "select id, week_start_date, timezone from weekly_plan where user_id = ? and public_id = ?",
            rs -> rs.next() ? new PlanRow(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3)) : null,
            userId, publicId
        );
        if (row == null) {
            throw notFound("WEEKLY_PLAN_NOT_FOUND");
        }
        return row;
    }

    private GoalRow goalRow(long userId, String publicId) {
        GoalRow row = jdbc.query(
            "select id, start_date, end_date from growth_goal where user_id = ? and public_id = ? and status in ('ACTIVE', 'DRAFT')",
            rs -> rs.next() ? new GoalRow(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate()) : null,
            userId,
            publicId
        );
        if (row == null) {
            throw notFound("GOAL_NOT_FOUND");
        }
        return row;
    }

    private PlanRow ensureCompatibilityPlans(long userId, long goalId, LocalDate activeFrom, LocalDate activeUntil) {
        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
        parseZone(timezone);
        LocalDate weekStart = activeFrom.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekStart = activeUntil.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        PlanRow first = null;
        for (LocalDate date = weekStart; !date.isAfter(lastWeekStart); date = date.plusWeeks(1)) {
            PlanRow plan = findOrCreateCompatibilityPlan(userId, goalId, date, timezone);
            if (first == null) {
                first = plan;
            }
        }
        if (first == null) {
            throw new IllegalStateException("Unable to create compatibility plan");
        }
        return first;
    }

    private PlanRow findOrCreateCompatibilityPlan(long userId, long goalId, LocalDate weekStart, String timezone) {
        PlanRow existing = compatibilityPlan(userId, goalId, weekStart);
        if (existing != null) {
            return existing;
        }
        try {
            jdbc.update(
                "insert into weekly_plan (public_id, user_id, goal_id, week_start_date, timezone, status) values (?, ?, ?, ?, ?, 'DRAFT')",
                ids.next(), userId, goalId, Date.valueOf(weekStart), timezone
            );
        } catch (DuplicateKeyException ignored) {
            // A concurrent request created the same compatibility plan.
        }
        PlanRow created = compatibilityPlan(userId, goalId, weekStart);
        if (created == null) {
            throw new IllegalStateException("Unable to create compatibility plan");
        }
        return created;
    }

    private PlanRow compatibilityPlan(long userId, long goalId, LocalDate weekStart) {
        return jdbc.query(
            "select id, week_start_date, timezone from weekly_plan where user_id = ? and goal_id = ? and week_start_date = ?",
            rs -> rs.next() ? new PlanRow(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3)) : null,
            userId,
            goalId,
            Date.valueOf(weekStart)
        );
    }

    private void materializeTask(
        long userId,
        long taskId,
        String rrule,
        LocalTime localTime,
        LocalDate activeFrom,
        LocalDate activeUntil,
        int estimatedMinutes,
        ZoneId timezone
    ) {
        List<PlannedOccurrence> occurrences = rrule == null
            ? List.of(new PlannedOccurrence(activeFrom, activeFrom.atTime(localTime).atZone(timezone).toInstant()))
            : recurrenceExpander.expand(RecurrenceRule.parse(rrule), activeFrom, localTime, timezone, activeUntil);
        for (PlannedOccurrence occurrence : occurrences) {
            jdbc.update(
                """
                    insert ignore into task_schedule (
                        public_id, user_id, task_id, planned_start_at, planned_end_at, local_date, timezone, status
                    ) values (?, ?, ?, ?, ?, ?, ?, 'PLANNED')
                    """,
                ids.next(), userId, taskId, Timestamp.from(occurrence.instant()),
                Timestamp.from(occurrence.instant().plusSeconds(estimatedMinutes * 60L)),
                Date.valueOf(occurrence.localDate()), timezone.getId()
            );
        }
    }

    private List<TaskRow> taskRows(long userId, long planId) {
        return jdbc.query(
            """
                select id, rrule, planned_local_time, active_from, active_until, estimated_minutes
                from user_task where user_id = ? and weekly_plan_id = ? and active = 1
                """,
            (rs, row) -> new TaskRow(
                rs.getLong("id"), rs.getString("rrule"), rs.getTime("planned_local_time").toLocalTime(),
                rs.getDate("active_from") == null ? null : rs.getDate("active_from").toLocalDate(),
                rs.getDate("active_until") == null ? null : rs.getDate("active_until").toLocalDate(),
                rs.getInt("estimated_minutes")
            ),
            userId, planId
        );
    }

    private List<TaskScheduleView> schedules(long userId, long planId) {
        return jdbc.query(
            """
                select s.public_id, t.public_id task_public_id, s.planned_start_at, s.local_date, s.timezone, s.status
                from task_schedule s join user_task t on t.id = s.task_id
                where s.user_id = ? and t.weekly_plan_id = ? order by s.planned_start_at
                """,
            (rs, row) -> new TaskScheduleView(
                rs.getString("public_id"), rs.getString("task_public_id"),
                rs.getTimestamp("planned_start_at").toInstant(), rs.getDate("local_date").toLocalDate(),
                rs.getString("timezone"), rs.getString("status")
            ), userId, planId
        );
    }

    private Map<String, Integer> validateWeights(long userId, Map<String, Integer> values) {
        if (values == null || values.isEmpty() || values.values().stream().anyMatch(value -> value == null || value < 1 || value > 30)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIMENSION_WEIGHTS", "Dimension weights are invalid");
        }
        List<String> allowed = jdbc.queryForList(
            """
                select d.code from growth_dimension d
                join user_dimension ud on ud.dimension_id = d.id
                where ud.user_id = ? and ud.active = 1 and d.archived_at is null
                """,
            String.class, userId
        );
        if (!allowed.containsAll(values.keySet())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIMENSION_WEIGHTS", "Dimension weights are invalid");
        }
        return new LinkedHashMap<>(values);
    }

    private Long sourceTemplate(String publicId, CareerRole role) {
        if (publicId == null || publicId.isBlank()) return null;
        TemplateRow template = jdbc.query(
            "select id, scene from task_template where public_id = ? and review_status = 'PUBLISHED'",
            rs -> rs.next() ? new TemplateRow(rs.getLong("id"), rs.getString("scene")) : null,
            publicId
        );
        if (template == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK_TEMPLATE", "任务模板无效");
        }
        if (!role.scene().equals(template.scene())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_TEMPLATE_ROLE_MISMATCH", "任务模板与职业不匹配");
        }
        return template.id();
    }

    private WeeklyPlanView weeklyPlanView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WeeklyPlanView(
            rs.getString("public_id"), rs.getString("goal_public_id"), rs.getDate("week_start_date").toLocalDate(),
            rs.getString("timezone"), rs.getString("status")
        );
    }

    private TaskView taskView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskView(
            rs.getString("public_id"), rs.getString("plan_public_id"), rs.getString("goal_public_id"),
            rs.getString("title"), rs.getString("notes"),
            rs.getInt("estimated_minutes"), rs.getInt("difficulty"), rs.getString("rrule"),
            readWeights(rs.getString("dimension_weights")), rs.getTime("planned_local_time").toLocalTime(),
            rs.getDate("active_from") == null ? null : rs.getDate("active_from").toLocalDate(),
            rs.getDate("active_until") == null ? null : rs.getDate("active_until").toLocalDate(), rs.getBoolean("active"),
            rs.getString("source_template_public_id"), rs.getString("role_code")
        );
    }

    private Map<String, Integer> readWeights(String value) {
        try {
            return objectMapper.readValue(value, WEIGHTS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored dimension weights are invalid", exception);
        }
    }

    private String json(Map<String, Integer> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize dimension weights", exception);
        }
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE", "A valid IANA timezone is required");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Resource not found");
    }

    private record GoalRow(long id, LocalDate startDate, LocalDate endDate) {
    }

    private record PlanRow(long id, LocalDate weekStartDate, String timezone) {
    }

    private record TaskRow(
        long id,
        String rrule,
        LocalTime localTime,
        LocalDate activeFrom,
        LocalDate activeUntil,
        int estimatedMinutes
    ) {
    }

    private record TemplateRow(long id, String scene) {
    }
}
