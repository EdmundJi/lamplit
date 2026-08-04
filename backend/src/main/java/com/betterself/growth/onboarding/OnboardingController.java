package com.betterself.growth.onboarding;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboarding;
    private final Clock clock;

    public OnboardingController(OnboardingService onboarding, Clock clock) {
        this.onboarding = onboarding;
        this.clock = clock;
    }

    @GetMapping("/starters")
    ApiEnvelope<List<OnboardingService.StarterTaskView>> starters(
        @RequestParam String scene,
        HttpServletRequest request
    ) {
        return envelope(onboarding.starters(scene), request);
    }

    @PostMapping("/complete")
    ResponseEntity<ApiEnvelope<OnboardingService.SetupResult>> complete(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody OnboardingService.SetupCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(envelope(onboarding.complete(user.id(), body), request));
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
