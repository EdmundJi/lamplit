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
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dimensions")
public class DimensionController {

    private final GoalService goals;
    private final Clock clock;

    public DimensionController(GoalService goals, Clock clock) {
        this.goals = goals;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<GoalService.DimensionView>> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(goals.dimensions(user.id()), request);
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<GoalService.DimensionView>> create(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody GoalService.DimensionCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(goals.createDimension(user.id(), body), request));
    }

    @PatchMapping("/{dimensionId}")
    ApiEnvelope<GoalService.DimensionView> update(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String dimensionId,
        @RequestBody GoalService.DimensionCommand body,
        HttpServletRequest request
    ) {
        return envelope(goals.updateDimension(user.id(), dimensionId, body), request);
    }

    @DeleteMapping("/{dimensionId}")
    ResponseEntity<Void> delete(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String dimensionId
    ) {
        goals.deleteDimension(user.id(), dimensionId);
        return ResponseEntity.noContent().build();
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
