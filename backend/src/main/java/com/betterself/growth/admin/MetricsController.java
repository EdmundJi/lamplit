package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/metrics")
public class MetricsController {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    private final Clock clock;

    public MetricsController(JdbcTemplate jdbc, AdminAuthorizationService authorization, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<Map<String, Integer>> metrics(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        authorization.require(user, "METRICS_READ");
        return ApiEnvelope.of(Map.of(
            "activeUsers", jdbc.queryForObject("select count(*) from sys_user where status = 'ACTIVE'", Integer.class),
            "taskEvents", jdbc.queryForObject("select count(*) from task_event", Integer.class),
            "safetyEvents", jdbc.queryForObject("select count(*) from ai_safety_event", Integer.class),
            "deletionBacklog", jdbc.queryForObject("select count(*) from deletion_request where status in ('COOLING_OFF','PROCESSING')", Integer.class)
        ), String.valueOf(request.getAttribute("requestId")), clock);
    }
}
