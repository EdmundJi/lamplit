package com.betterself.growth.goal;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final PlanningService planning;
    private final DatabasePlanningService queries;
    private final Clock clock;

    public TaskController(PlanningService planning, DatabasePlanningService queries, Clock clock) {
        this.planning = planning;
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<PlanningService.TaskView>> list(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) String weeklyPlanId,
        @RequestParam(required = false) String goalId,
        HttpServletRequest request
    ) {
        return envelope(queries.tasks(user.id(), weeklyPlanId, goalId), request);
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<PlanningService.TaskView>> create(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody PlanningService.CreateTaskCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(planning.createTask(user.id(), body), request));
    }

    @GetMapping("/{taskId}")
    ApiEnvelope<PlanningService.TaskView> get(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId,
        HttpServletRequest request
    ) {
        return envelope(queries.task(user.id(), taskId), request);
    }

    @PatchMapping("/{taskId}")
    ApiEnvelope<PlanningService.TaskView> update(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId,
        @RequestBody PlanningService.UpdateTaskCommand body,
        HttpServletRequest request
    ) {
        return envelope(planning.updateTask(user.id(), taskId, body), request);
    }

    @PostMapping("/{taskId}/pause")
    ApiEnvelope<PlanningService.TaskView> pause(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId,
        HttpServletRequest request
    ) {
        return envelope(queries.setTaskActive(user.id(), taskId, false), request);
    }

    @PostMapping("/{taskId}/resume")
    ApiEnvelope<PlanningService.TaskView> resume(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId,
        HttpServletRequest request
    ) {
        return envelope(queries.setTaskActive(user.id(), taskId, true), request);
    }

    @DeleteMapping("/{taskId}")
    ApiEnvelope<PlanningService.TaskView> cancel(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId,
        HttpServletRequest request
    ) {
        return envelope(queries.setTaskActive(user.id(), taskId, false), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
