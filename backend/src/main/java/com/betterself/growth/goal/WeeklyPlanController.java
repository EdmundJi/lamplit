package com.betterself.growth.goal;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/plans/weekly")
public class WeeklyPlanController {

    private final PlanningService planning;
    private final DatabasePlanningService queries;
    private final Clock clock;

    public WeeklyPlanController(PlanningService planning, DatabasePlanningService queries, Clock clock) {
        this.planning = planning;
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<PlanningService.WeeklyPlanView>> list(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) LocalDate weekStart,
        HttpServletRequest request
    ) {
        return envelope(queries.weeklyPlans(user.id(), weekStart), request);
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<PlanningService.WeeklyPlanView>> create(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody PlanningService.CreateWeeklyPlanCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(planning.createWeeklyPlan(user.id(), body), request));
    }

    @PostMapping("/{planId}/materialize")
    ApiEnvelope<List<PlanningService.TaskScheduleView>> materialize(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String planId,
        HttpServletRequest request
    ) {
        return envelope(planning.materializeWeek(user.id(), planId), request);
    }

    @PatchMapping("/{planId}")
    ApiEnvelope<PlanningService.WeeklyPlanView> update(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String planId,
        @RequestBody PlanningService.UpdateWeeklyPlanCommand body,
        HttpServletRequest request
    ) {
        return envelope(planning.updateWeeklyPlan(user.id(), planId, body), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
