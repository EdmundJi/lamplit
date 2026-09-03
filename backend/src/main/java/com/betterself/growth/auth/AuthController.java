package com.betterself.growth.auth;

import com.betterself.growth.admin.LocalAdminLoginProperties;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@EnableConfigurationProperties(LocalAdminLoginProperties.class)
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final CookieFactory cookieFactory;
    private final Clock clock;
    private final LocalAdminLoginProperties localAdminLogin;
    private final boolean localProfileActive;

    public AuthController(
        AuthService authService,
        SessionService sessionService,
        CookieFactory cookieFactory,
        Clock clock,
        LocalAdminLoginProperties localAdminLogin,
        Environment environment
    ) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.cookieFactory = cookieFactory;
        this.clock = clock;
        this.localAdminLogin = localAdminLogin;
        this.localProfileActive = environment.acceptsProfiles(Profiles.of("local"));
    }

    @PostMapping("/register")
    ResponseEntity<ApiEnvelope<AuthService.UserView>> register(
        @Valid @RequestBody RegisterRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AuthService.UserView user = authService.register(new AuthService.RegisterCommand(
            body.email(),
            body.password(),
            body.displayName(),
            body.birthDate(),
            body.timezone(),
            body.consents() == null ? null : new AuthService.ConsentVersions(
                body.consents().terms(), body.consents().privacy(), body.consents().ai()
            )
        ));
        SessionService.IssuedSession session = sessionService.issue(user.id(), body.deviceLabel(), request);
        cookieFactory.write(response, session);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiEnvelope.of(user, requestId(request), clock));
    }

    @PostMapping("/login")
    ApiEnvelope<?> login(
        @Valid @RequestBody LoginRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AuthService.UserView user = authService.authenticate(body.email(), body.password());
        boolean localAdmin = localProfileActive && localAdminLogin.allowsDirectLogin(body.email(), user.role());
        if (!"USER".equals(user.role()) && !localAdmin) {
            return ApiEnvelope.of(Map.of("status", "MFA_PENDING"), requestId(request), clock);
        }
        SessionService.IssuedSession session = sessionService.issue(user.id(), body.deviceLabel(), request);
        cookieFactory.write(response, session);
        return ApiEnvelope.of(user, requestId(request), clock);
    }

    @PostMapping("/refresh")
    ApiEnvelope<Map<String, String>> refresh(
        @CookieValue(name = CookieFactory.REFRESH_COOKIE, required = false) String refreshToken,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        SessionService.IssuedSession session = sessionService.rotate(refreshToken, request);
        cookieFactory.write(response, session);
        return ApiEnvelope.of(Map.of("status", "REFRESHED"), requestId(request), clock);
    }

    @PostMapping("/logout")
    ApiEnvelope<Map<String, String>> logout(
        @AuthenticationPrincipal CurrentUser user,
        @CookieValue(name = CookieFactory.REFRESH_COOKIE, required = false) String refreshToken,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        sessionService.revokeCurrent(user.id(), refreshToken);
        cookieFactory.clear(response);
        return ApiEnvelope.of(Map.of("status", "LOGGED_OUT"), requestId(request), clock);
    }

    @PostMapping("/logout-all")
    ApiEnvelope<Map<String, String>> logoutAll(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        sessionService.revokeAll(user.id());
        cookieFactory.clear(response);
        return ApiEnvelope.of(Map.of("status", "LOGGED_OUT"), requestId(request), clock);
    }

    @PostMapping("/password/forgot")
    ApiEnvelope<Map<String, String>> forgot(@Valid @RequestBody ForgotRequest body, HttpServletRequest request) {
        authService.requestPasswordReset(body.email());
        return ApiEnvelope.of(Map.of("status", "ACCEPTED"), requestId(request), clock);
    }

    @PostMapping("/password/reset")
    ApiEnvelope<Map<String, String>> reset(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        authService.resetPassword(body.token(), body.newPassword());
        return ApiEnvelope.of(Map.of("status", "PASSWORD_RESET"), requestId(request), clock);
    }

    @PutMapping("/password")
    ApiEnvelope<Map<String, String>> updatePassword(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody UpdatePasswordRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        authService.updatePassword(user.id(), body.currentPassword(), body.newPassword());
        cookieFactory.clear(response);
        return ApiEnvelope.of(Map.of("status", "PASSWORD_UPDATED"), requestId(request), clock);
    }

    @PostMapping("/mfa/verify")
    ApiEnvelope<AuthService.UserView> verifyMfa(
        @Valid @RequestBody MfaRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AuthService.UserView user = authService.verifyAdminMfa(body.email(), body.password(), body.code());
        SessionService.IssuedSession session = sessionService.issue(user.id(), body.deviceLabel(), request);
        cookieFactory.write(response, session);
        return ApiEnvelope.of(user, requestId(request), clock);
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("requestId"));
    }

    public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, max = 200) String password,
        @NotBlank @Size(max = 80) String displayName,
        @NotNull @Past LocalDate birthDate,
        @NotBlank String timezone,
        @NotNull @Valid ConsentRequest consents,
        String deviceLabel
    ) {
    }

    public record ConsentRequest(@NotBlank String terms, @NotBlank String privacy, @NotBlank String ai) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String deviceLabel) {
    }

    public record ForgotRequest(@NotBlank @Email String email) {
    }

    public record ResetRequest(@NotBlank String token, @NotBlank @Size(min = 12, max = 200) String newPassword) {
    }

    public record UpdatePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 12, max = 200) String newPassword
    ) {
    }

    public record MfaRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank @Size(min = 6, max = 8) String code,
        String deviceLabel
    ) {
    }
}
