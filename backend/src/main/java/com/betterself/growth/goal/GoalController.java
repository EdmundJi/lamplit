package com.betterself.growth.goal;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService goals;
    private final Clock clock;

    public GoalController(GoalService goals, Clock clock) {
        this.goals = goals;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<GoalService.GoalView>> list(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) String status,
        HttpServletRequest request
    ) {
        return envelope(goals.goals(user.id(), status), request);
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<GoalService.GoalView>> create(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody GoalService.GoalCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(goals.createGoal(user.id(), body), request));
    }

    @GetMapping("/{goalId}")
    ApiEnvelope<GoalService.GoalView> get(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String goalId,
        HttpServletRequest request
    ) {
        return envelope(goals.goal(user.id(), goalId), request);
    }

    @PatchMapping("/{goalId}")
    ApiEnvelope<GoalService.GoalView> update(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String goalId,
        @RequestBody GoalService.GoalCommand body,
        HttpServletRequest request
    ) {
        return envelope(goals.updateGoal(user.id(), goalId, body), request);
    }

    @PostMapping("/{goalId}/pause")
    ApiEnvelope<GoalService.GoalView> pause(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String goalId,
        HttpServletRequest request
    ) {
        return envelope(goals.changeStatus(user.id(), goalId, "PAUSED"), request);
    }

    @PostMapping("/{goalId}/resume")
    ApiEnvelope<GoalService.GoalView> resume(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String goalId,
        HttpServletRequest request
    ) {
        return envelope(goals.changeStatus(user.id(), goalId, "ACTIVE"), request);
    }

    @PostMapping("/{goalId}/complete")
    ApiEnvelope<GoalService.GoalView> complete(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String goalId,
        HttpServletRequest request
    ) {
        return envelope(goals.changeStatus(user.id(), goalId, "COMPLETED"), request);
    }

    @DeleteMapping("/{goalId}")
    ApiEnvelope<GoalService.GoalView> cancel(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String goalId,
        HttpServletRequest request
    ) {
        return envelope(goals.changeStatus(user.id(), goalId, "CANCELLED"), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
