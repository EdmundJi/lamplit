package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class AdminAuthorizationService {

    private static final Map<String, Set<String>> PERMISSIONS = Map.of(
        "ADMIN", Set.of("TEMPLATE_EDIT", "CONTENT_PUBLISH", "SAFETY_REVIEW", "SAFETY_SENSITIVE_READ", "AUDIT_READ", "METRICS_READ", "USER_ADMIN"),
        "CONTENT_OPERATOR", Set.of("TEMPLATE_EDIT"),
        "SAFETY_OPERATOR", Set.of("SAFETY_REVIEW")
    );

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminAuthorizationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void require(CurrentUser user, String permission) {
        if (!PERMISSIONS.getOrDefault(user.role(), Set.of()).contains(permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_PERMISSION_REQUIRED", "Administrator permission is required");
        }
    }

    public void requireRecentMfa(CurrentUser user) {
        require(user, "CONTENT_PUBLISH");
        java.sql.Timestamp verified = jdbc.queryForObject("select mfa_verified_at from sys_user where id = ?", java.sql.Timestamp.class, user.id());
        if (verified == null || verified.toInstant().isBefore(clock.instant().minus(Duration.ofMinutes(15)))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RECENT_MFA_REQUIRED", "Recent MFA verification is required");
        }
    }
}
