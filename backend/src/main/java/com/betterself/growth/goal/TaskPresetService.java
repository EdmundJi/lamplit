package com.betterself.growth.goal;

import com.betterself.growth.career.CareerRole;
import com.betterself.growth.execution.ExperienceCalculator;
import com.betterself.growth.execution.TaskEventType;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class TaskPresetService {

    private static final int DRAW_SIZE = 4;
    private static final int DAILY_REFRESH_LIMIT = 3;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperienceCalculator experienceCalculator;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TaskPresetService(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        ExperienceCalculator experienceCalculator,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.experienceCalculator = experienceCalculator;
        this.clock = clock;
    }

    @Transactional
    public TaskPresetDraw get(long userId, String roleCode) {
        CareerRole role = CareerRole.parse(roleCode);
        LocalDate localDate = localDate(userId);
        int refreshCount = lockQuota(userId, localDate);
        List<String> ids = savedDraw(userId, localDate, role);
        if (ids == null) {
            ids = draw(role, List.of());
            saveDraw(userId, localDate, role, ids);
        }
        return response(role, localDate, refreshCount, ids);
    }

    @Transactional
    public TaskPresetDraw refresh(long userId, String roleCode) {
        CareerRole role = CareerRole.parse(roleCode);
        LocalDate localDate = localDate(userId);
        int refreshCount = lockQuota(userId, localDate);
        if (refreshCount >= DAILY_REFRESH_LIMIT) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "TASK_PRESET_REFRESH_LIMIT",
                "今天的三次换一批机会已经用完了"
            );
        }
        List<String> current = savedDraw(userId, localDate, role);
        List<String> ids = draw(role, current == null ? List.of() : current);
        saveDraw(userId, localDate, role, ids);
        int updatedCount = refreshCount + 1;
        jdbc.update(
            "update task_preset_quota set refresh_count = ?, updated_at = UTC_TIMESTAMP(3) where user_id = ? and local_date = ?",
            updatedCount, userId, Date.valueOf(localDate)
        );
        return response(role, localDate, updatedCount, ids);
    }

    private int lockQuota(long userId, LocalDate localDate) {
        jdbc.update(
            "insert ignore into task_preset_quota (user_id, local_date) values (?, ?)",
            userId, Date.valueOf(localDate)
        );
        return jdbc.queryForObject(
            "select refresh_count from task_preset_quota where user_id = ? and local_date = ? for update",
            Integer.class, userId, Date.valueOf(localDate)
        );
    }

    private List<String> savedDraw(long userId, LocalDate localDate, CareerRole role) {
        String json = jdbc.query(
            "select template_public_ids from task_preset_draw where user_id = ? and local_date = ? and role_code = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            userId, Date.valueOf(localDate), role.name()
        );
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored task preset draw is invalid", exception);
        }
    }

    private List<String> draw(CareerRole role, List<String> excluded) {
        List<String> candidates = new ArrayList<>(jdbc.queryForList(
            "select public_id from task_template where scene = ? and review_status = 'PUBLISHED' order by public_id",
            String.class, role.scene()
        ));
        candidates.removeAll(excluded);
        if (candidates.size() < DRAW_SIZE) {
            candidates = new ArrayList<>(jdbc.queryForList(
                "select public_id from task_template where scene = ? and review_status = 'PUBLISHED' order by public_id",
                String.class, role.scene()
            ));
        }
        if (candidates.size() < DRAW_SIZE) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TASK_PRESETS_UNAVAILABLE", "职业任务暂时不足");
        }
        Collections.shuffle(candidates, random);
        return List.copyOf(candidates.subList(0, DRAW_SIZE));
    }

    private void saveDraw(long userId, LocalDate localDate, CareerRole role, List<String> ids) {
        jdbc.update(
            """
                insert into task_preset_draw (user_id, local_date, role_code, template_public_ids)
                values (?, ?, ?, cast(? as json))
                on duplicate key update template_public_ids = values(template_public_ids), updated_at = UTC_TIMESTAMP(3)
                """,
            userId, Date.valueOf(localDate), role.name(), json(ids)
        );
    }

    private TaskPresetDraw response(CareerRole role, LocalDate localDate, int refreshCount, List<String> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> parameters = new ArrayList<>(ids);
        parameters.add(role.scene());
        List<TaskPresetView> unordered = jdbc.query(
            """
                select public_id, name, description, estimated_minutes, difficulty,
                       planned_local_time, rrule, dimension_code, dimension_weight
                from task_template
                where public_id in (%s) and scene = ? and review_status = 'PUBLISHED'
                """.formatted(placeholders),
            (rs, row) -> {
                int minutes = rs.getInt("estimated_minutes");
                int difficulty = rs.getInt("difficulty");
                return new TaskPresetView(
                    rs.getString("public_id"), role.name(), role.displayName(), rs.getString("name"),
                    rs.getString("description"), minutes, difficulty,
                    rs.getTime("planned_local_time").toLocalTime(), rs.getString("rrule"),
                    rs.getString("dimension_code"), rs.getInt("dimension_weight"),
                    experienceCalculator.earned(TaskEventType.COMPLETED, minutes, difficulty, 1, 100)
                );
            },
            parameters.toArray()
        );
        List<TaskPresetView> ordered = ids.stream()
            .map(id -> unordered.stream().filter(item -> item.publicId().equals(id)).findFirst().orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
        return new TaskPresetDraw(
            role.name(), role.displayName(), localDate, DAILY_REFRESH_LIMIT - refreshCount, ordered
        );
    }

    private LocalDate localDate(long userId) {
        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
        return clock.instant().atZone(ZoneId.of(timezone)).toLocalDate();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize task preset draw", exception);
        }
    }

    public record TaskPresetDraw(
        String roleCode,
        String roleName,
        LocalDate localDate,
        int refreshesRemaining,
        List<TaskPresetView> items
    ) {
    }

    public record TaskPresetView(
        String publicId,
        String roleCode,
        String roleName,
        String name,
        String notes,
        int estimatedMinutes,
        int difficulty,
        LocalTime plannedLocalTime,
        String rrule,
        String dimensionCode,
        int dimensionWeight,
        int experienceReward
    ) {
    }
}
