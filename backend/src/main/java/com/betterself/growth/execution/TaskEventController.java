package com.betterself.growth.execution;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/task-schedules")
public class TaskEventController {

    private final TaskExecutionService execution;
    private final Clock clock;

    public TaskEventController(TaskExecutionService execution, Clock clock) {
        this.execution = execution;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<TaskExecutionService.ScheduleView>> list(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) LocalDate localDate,
        HttpServletRequest request
    ) {
        return envelope(execution.schedules(user.id(), localDate), request);
    }

    @PostMapping("/{scheduleId}/events")
    ApiEnvelope<TaskExecutionService.EventResult> record(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String scheduleId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody TaskExecutionService.TaskEventCommand body,
        HttpServletRequest request
    ) {
        return envelope(execution.record(user.id(), scheduleId, idempotencyKey, body), request);
    }

    @PostMapping("/{scheduleId}/events/{eventId}/reverse")
    ApiEnvelope<TaskExecutionService.EventResult> reverse(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String scheduleId,
        @PathVariable String eventId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        HttpServletRequest request
    ) {
        return envelope(execution.reverse(user.id(), scheduleId, eventId, idempotencyKey), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
