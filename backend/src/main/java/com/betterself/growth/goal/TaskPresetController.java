package com.betterself.growth.goal;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/task-presets")
public class TaskPresetController {

    private final TaskPresetService presets;
    private final Clock clock;

    public TaskPresetController(TaskPresetService presets, Clock clock) {
        this.presets = presets;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<TaskPresetService.TaskPresetDraw> get(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam String role,
        HttpServletRequest request
    ) {
        return envelope(presets.get(user.id(), role), request);
    }

    @PostMapping("/refresh")
    ApiEnvelope<TaskPresetService.TaskPresetDraw> refresh(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam String role,
        HttpServletRequest request
    ) {
        return envelope(presets.refresh(user.id(), role), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
