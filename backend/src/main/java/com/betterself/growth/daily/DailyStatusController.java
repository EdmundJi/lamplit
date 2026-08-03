package com.betterself.growth.daily;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/daily-status")
public class DailyStatusController {

    private final DailyStatusService statuses;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public DailyStatusController(DailyStatusService statuses, Clock clock, JdbcTemplate jdbc) {
        this.statuses = statuses;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @GetMapping
    ApiEnvelope<DailyStatusService.StatusView> today(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) String localDate,
        HttpServletRequest request
    ) {
        ZoneId zone = userZone(user.id());
        LocalDate date = localDate == null
            ? clock.instant().atZone(zone).toLocalDate()
            : LocalDate.parse(localDate);
        return envelope(statuses.status(user.id(), date), request);
    }

    @PostMapping
    ApiEnvelope<DailyStatusService.StatusView> save(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody DailyStatusService.SaveStatusCommand body,
        HttpServletRequest request
    ) {
        ZoneId zone = userZone(user.id());
        return envelope(statuses.save(user.id(), body, zone), request);
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject(
            "select timezone from sys_user where id = ?", String.class, userId
        );
        return ZoneId.of(timezone);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
