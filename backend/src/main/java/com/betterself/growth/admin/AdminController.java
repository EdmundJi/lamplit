package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ContentGovernanceService content;
    private final SafetyReviewService safety;
    private final AuditService audit;
    private final AdminUserService users;
    private final AdminAuthorizationService authorization;
    private final Clock clock;

    public AdminController(ContentGovernanceService content, SafetyReviewService safety, AuditService audit, AdminUserService users, AdminAuthorizationService authorization, Clock clock) {
        this.content = content;
        this.safety = safety;
        this.audit = audit;
        this.users = users;
        this.authorization = authorization;
        this.clock = clock;
    }

    @PostMapping("/prompts/publish")
    ApiEnvelope<ContentGovernanceService.PromptView> publish(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody ContentGovernanceService.PublishPromptCommand body,
        HttpServletRequest request
    ) {
        return envelope(content.publishPrompt(user, body, requestId(request)), request);
    }

    @PostMapping("/prompts/{promptId}/rollback")
    ApiEnvelope<ContentGovernanceService.PromptView> rollback(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String promptId,
        HttpServletRequest request
    ) {
        return envelope(content.rollback(user, promptId, requestId(request)), request);
    }

    @GetMapping("/safety/events")
    ApiEnvelope<List<SafetyReviewService.SafetyEventView>> safetyEvents(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return envelope(safety.events(user), request);
    }

    @GetMapping("/audit")
    ApiEnvelope<List<AuditService.AuditView>> audit(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        authorization.require(user, "AUDIT_READ");
        return envelope(audit.list(), request);
    }

    @GetMapping("/users")
    ApiEnvelope<List<AdminUserService.UserAdminView>> users(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return envelope(users.users(user), request);
    }

    @PostMapping("/users/admins")
    ApiEnvelope<AdminUserService.CreatedAdminView> createPrivilegedUser(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody AdminUserService.CreateAdminCommand body,
        HttpServletRequest request
    ) {
        return envelope(users.createPrivilegedUser(user, body, requestId(request)), request);
    }

    @PostMapping("/users/{userId}/role")
    ApiEnvelope<AdminUserService.UserAdminView> updateRole(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String userId,
        @RequestBody AdminUserService.RoleCommand body,
        HttpServletRequest request
    ) {
        return envelope(users.updateRole(user, userId, body, requestId(request)), request);
    }

    @PostMapping("/users/{userId}/status")
    ApiEnvelope<AdminUserService.UserAdminView> updateStatus(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String userId,
        @RequestBody AdminUserService.StatusCommand body,
        HttpServletRequest request
    ) {
        return envelope(users.updateStatus(user, userId, body, requestId(request)), request);
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("requestId"));
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, requestId(request), clock);
    }
}
