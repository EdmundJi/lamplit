package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SafetyReviewService {

    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;

    public SafetyReviewService(JdbcTemplate jdbc, AdminAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public List<SafetyEventView> events(CurrentUser user) {
        authorization.require(user, "SAFETY_REVIEW");
        boolean sensitive = "ADMIN".equals(user.role());
        return jdbc.query(
            "select public_id, scene, risk_level, direction, redacted_excerpt, review_status, created_at from ai_safety_event order by id desc limit 200",
            (rs, row) -> new SafetyEventView(
                rs.getString("public_id"), rs.getString("scene"), rs.getString("risk_level"), rs.getString("direction"),
                sensitive ? rs.getString("redacted_excerpt") : "[REDACTED]", rs.getString("review_status"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
    }

    public record SafetyEventView(String publicId, String scene, String riskLevel, String direction, String excerpt, String reviewStatus, java.time.Instant createdAt) {
    }
}
