package com.betterself.growth.identity;

import com.betterself.growth.auth.AuthService;
import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
public class IdentityController {

    private final IdentityService identityService;
    private final Clock clock;

    public IdentityController(IdentityService identityService, Clock clock) {
        this.identityService = identityService;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<AuthService.UserView> me(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(identityService.me(user.id()), request);
    }

    @GetMapping("/profile")
    ApiEnvelope<IdentityService.ProfileView> profile(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(identityService.profile(user.id()), request);
    }

    @GetMapping("/preferences")
    ApiEnvelope<IdentityService.PreferenceView> preferences(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(identityService.preference(user.id()), request);
    }

    @PatchMapping("/preferences")
    ApiEnvelope<IdentityService.PreferenceView> updatePreferences(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody IdentityService.PreferenceCommand body,
        HttpServletRequest request
    ) {
        return envelope(identityService.updatePreferences(user.id(), body), request);
    }

    @GetMapping("/consents")
    ApiEnvelope<List<IdentityService.ConsentView>> consents(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(identityService.consents(user.id()), request);
    }

    @PostMapping("/consents")
    ApiEnvelope<List<IdentityService.ConsentView>> recordConsent(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody IdentityService.ConsentCommand body,
        HttpServletRequest request
    ) {
        return envelope(identityService.recordConsent(user.id(), body), request);
    }

    @PatchMapping("/ai-memory")
    ApiEnvelope<IdentityService.PreferenceView> updateAiMemory(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody AiMemoryRequest body,
        HttpServletRequest request
    ) {
        return envelope(identityService.updateAiMemory(user.id(), body.enabled()), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }

    public record AiMemoryRequest(boolean enabled) {
    }
}
