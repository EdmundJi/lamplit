package com.betterself.growth.onboarding;

import com.betterself.growth.achievement.TitleService;
import com.betterself.growth.career.CareerRole;
import com.betterself.growth.goal.GoalService;
import com.betterself.growth.goal.PlanningService;
import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class OnboardingService {

    private static final int STARTER_LIMIT = 3;

    private final JdbcTemplate jdbc;
    private final GoalService goals;
    private final PlanningService planning;
    private final TitleService titles;
    private final Clock clock;

    public OnboardingService(
        JdbcTemplate jdbc,
        GoalService goals,
        PlanningService planning,
        TitleService titles,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.goals = goals;
        this.planning = planning;
        this.titles = titles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StarterTaskView> starters(String scene) {
        CareerRole role = roleForScene(scene);
        return jdbc.query(
            """
                select public_id, name, description, estimated_minutes, difficulty,
                       planned_local_time, rrule, dimension_code
                from task_template
                where scene = ? and review_status = 'PUBLISHED'
                order by difficulty, estimated_minutes, id
                limit 4
                """,
            (rs, row) -> new StarterTaskView(
                rs.getString("public_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("estimated_minutes"),
                rs.getInt("difficulty"),
                rs.getTime("planned_local_time").toLocalTime(),
                rs.getString("rrule"),
                rs.getString("dimension_code")
            ),
            role.scene()
        );
    }

    @Transactional
    public SetupResult complete(long userId, SetupCommand command) {
        validate(command);
        CareerRole role = roleForScene(command.scene());
        jdbc.queryForObject("select id from sys_user where id = ? for update", Long.class, userId);
        Timestamp completedAt = jdbc.queryForObject(
            "select onboarding_completed_at from user_preference where user_id = ?",
            Timestamp.class,
            userId
        );
        if (completedAt != null) {
            return result(userId, true, completedAt.toInstant(), null, null, List.of());
        }

        List<String> selectedIds = distinctIds(command.starterTemplatePublicIds());
        List<StarterTemplate> selected = selectedTemplates(role, selectedIds);
        if (selected.size() != selectedIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STARTER_TASKS", "起步任务无效或不属于所选场景");
        }

        Instant now = clock.instant();
        jdbc.update(
            """
                update user_preference
                set scene = ?, daily_minutes = ?, weekly_frequency = ?, preferred_difficulty = ?,
                    onboarding_completed_at = ?, updated_at = UTC_TIMESTAMP(3)
                where user_id = ?
                """,
            role.scene(), command.dailyMinutes(), command.weeklyFrequency(), command.preferredDifficulty(),
            Timestamp.from(now), userId
        );

        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
        ZoneId zone = ZoneId.of(timezone);
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String dimensionPublicId = jdbc.queryForObject(
            """
                select d.public_id from growth_dimension d
                join user_dimension ud on ud.dimension_id = d.id
                where ud.user_id = ? and ud.active = 1 and d.code = ? and d.archived_at is null
                """,
            String.class,
            userId,
            role.primaryDimension()
        );

        GoalService.GoalView goal = goals.createGoal(userId, new GoalService.GoalCommand(
            dimensionPublicId,
            goalTitle(role),
            "先用四周建立一个可调整、低压力的行动节奏。",
            today,
            today.plusDays(27)
        ));
        PlanningService.WeeklyPlanView plan = planning.createWeeklyPlan(
            userId,
            new PlanningService.CreateWeeklyPlanCommand(goal.publicId(), monday, timezone)
        );

        List<String> taskIds = new ArrayList<>();
        for (StarterTemplate template : selected) {
            PlanningService.TaskView task = planning.createTask(userId, new PlanningService.CreateTaskCommand(
                plan.publicId(),
                null,
                template.name(),
                template.description(),
                template.estimatedMinutes(),
                template.difficulty(),
                template.rrule(),
                Map.of(template.dimensionCode(), template.dimensionWeight()),
                template.plannedLocalTime(),
                today,
                monday.plusDays(6),
                template.publicId(),
                role.name()
            ));
            taskIds.add(task.publicId());
        }
        planning.materializeWeek(userId, plan.publicId());
        titles.acquire(userId, "NEWCOMER_PATH");
        return result(userId, false, now, goal.publicId(), plan.publicId(), taskIds);
    }

    private SetupResult result(
        long userId,
        boolean alreadyCompleted,
        Instant completedAt,
        String goalPublicId,
        String weeklyPlanPublicId,
        List<String> taskPublicIds
    ) {
        TitleService.TitleView reward = titles.list(userId).stream()
            .filter(title -> title.code().equals("NEWCOMER_PATH"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Newcomer title is unavailable"));
        return new SetupResult(
            alreadyCompleted,
            completedAt,
            goalPublicId,
            weeklyPlanPublicId,
            List.copyOf(taskPublicIds),
            new RewardTitleView(
                reward.code(), reward.name(), reward.description(), reward.graphicType(),
                reward.graphicKey(), reward.frameStyle()
            )
        );
    }

    private void validate(SetupCommand command) {
        if (command == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ONBOARDING", "入门设置不能为空");
        }
        if (command.dailyMinutes() < 5 || command.dailyMinutes() > 720) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DAILY_MINUTES", "每天投入需在 5 到 720 分钟之间");
        }
        if (command.weeklyFrequency() < 1 || command.weeklyFrequency() > 7) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEKLY_FREQUENCY", "每周频次需在 1 到 7 次之间");
        }
        if (command.preferredDifficulty() < 1 || command.preferredDifficulty() > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIFFICULTY", "任务难度需在 1 到 3 之间");
        }
    }

    private List<String> distinctIds(List<String> values) {
        if (values == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STARTER_TASKS_REQUIRED", "请选择起步任务");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) ids.add(value);
        }
        if (ids.isEmpty() || ids.size() > STARTER_LIMIT || ids.size() != values.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STARTER_TASKS", "请选择 1 到 3 个不同的起步任务");
        }
        return List.copyOf(ids);
    }

    private List<StarterTemplate> selectedTemplates(CareerRole role, List<String> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> parameters = new ArrayList<>(ids);
        parameters.add(role.scene());
        List<StarterTemplate> rows = jdbc.query(
            """
                select public_id, name, description, estimated_minutes, difficulty,
                       planned_local_time, rrule, dimension_code, dimension_weight
                from task_template
                where public_id in (%s) and scene = ? and review_status = 'PUBLISHED'
                """.formatted(placeholders),
            (rs, row) -> new StarterTemplate(
                rs.getString("public_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("estimated_minutes"),
                rs.getInt("difficulty"),
                rs.getTime("planned_local_time").toLocalTime(),
                rs.getString("rrule"),
                rs.getString("dimension_code"),
                rs.getInt("dimension_weight")
            ),
            parameters.toArray()
        );
        return ids.stream()
            .map(id -> rows.stream().filter(row -> row.publicId().equals(id)).findFirst().orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private CareerRole roleForScene(String scene) {
        if (scene == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ONBOARDING_SCENE", "请选择关注场景");
        }
        return Arrays.stream(CareerRole.values())
            .filter(role -> role.scene().equalsIgnoreCase(scene.trim()))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ONBOARDING_SCENE", "关注场景无效"));
    }

    private String goalTitle(CareerRole role) {
        return switch (role) {
            case STUDENT -> "建立稳定的学习节奏";
            case FITNESS_USER -> "建立温和的身体照顾节奏";
            case WORKER -> "建立清晰的职场推进节奏";
            case EMOTIONAL_SUPPORT_USER -> "建立可持续的情绪支持节奏";
        };
    }

    public record SetupCommand(
        String scene,
        int dailyMinutes,
        int weeklyFrequency,
        int preferredDifficulty,
        List<String> starterTemplatePublicIds
    ) {
    }

    public record StarterTaskView(
        String publicId,
        String title,
        String description,
        int estimatedMinutes,
        int difficulty,
        LocalTime plannedLocalTime,
        String rrule,
        String dimensionCode
    ) {
    }

    public record SetupResult(
        boolean alreadyCompleted,
        Instant completedAt,
        String goalPublicId,
        String weeklyPlanPublicId,
        List<String> taskPublicIds,
        RewardTitleView rewardTitle
    ) {
    }

    public record RewardTitleView(
        String code,
        String name,
        String description,
        String graphicType,
        String graphicKey,
        String frameStyle
    ) {
    }

    private record StarterTemplate(
        String publicId,
        String name,
        String description,
        int estimatedMinutes,
        int difficulty,
        LocalTime plannedLocalTime,
        String rrule,
        String dimensionCode,
        int dimensionWeight
    ) {
    }
}
