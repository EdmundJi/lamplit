package com.betterself.growth.goal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public interface PlanningService {

    WeeklyPlanView createWeeklyPlan(long userId, CreateWeeklyPlanCommand command);

    TaskView createTask(long userId, CreateTaskCommand command);

    WeeklyPlanView updateWeeklyPlan(long userId, String publicId, UpdateWeeklyPlanCommand command);

    TaskView updateTask(long userId, String publicId, UpdateTaskCommand command);

    List<TaskScheduleView> materializeWeek(long userId, String weeklyPlanPublicId);

    record CreateWeeklyPlanCommand(String goalPublicId, LocalDate weekStartDate, String timezone) {
    }

    record UpdateWeeklyPlanCommand(String timezone, String status) {
    }

    record WeeklyPlanView(
        String publicId,
        String goalPublicId,
        LocalDate weekStartDate,
        String timezone,
        String status
    ) {
    }

    record CreateTaskCommand(
        String weeklyPlanPublicId,
        String title,
        String notes,
        int estimatedMinutes,
        int difficulty,
        String rrule,
        Map<String, Integer> dimensionWeights,
        LocalTime plannedLocalTime,
        LocalDate activeFrom,
        LocalDate activeUntil,
        String sourceTemplatePublicId,
        String roleCode
    ) {
    }

    record UpdateTaskCommand(
        String title,
        String notes,
        Integer estimatedMinutes,
        Integer difficulty,
        String rrule,
        Map<String, Integer> dimensionWeights,
        LocalTime plannedLocalTime,
        LocalDate activeFrom,
        LocalDate activeUntil
    ) {
    }

    record TaskView(
        String publicId,
        String weeklyPlanPublicId,
        String title,
        String notes,
        int estimatedMinutes,
        int difficulty,
        String rrule,
        Map<String, Integer> dimensionWeights,
        LocalTime plannedLocalTime,
        LocalDate activeFrom,
        LocalDate activeUntil,
        boolean active,
        String sourceTemplatePublicId,
        String roleCode
    ) {
    }

    record TaskScheduleView(
        String publicId,
        String taskPublicId,
        Instant plannedStartAt,
        LocalDate localDate,
        String timezone,
        String status
    ) {
    }
}
