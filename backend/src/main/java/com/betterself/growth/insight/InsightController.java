package com.betterself.growth.insight;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {

    private final InsightService insights;
    private final Clock clock;

    public InsightController(InsightService insights, Clock clock) {
        this.insights = insights;
        this.clock = clock;
    }

    @GetMapping("/overview")
    ApiEnvelope<InsightService.Overview> overview(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return envelope(insights.overview(user.id()), request);
    }

    @GetMapping("/attributes")
    ApiEnvelope<InsightService.AttributesOverview> attributes(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(insights.attributes(user.id()), request);
    }

    @GetMapping("/trends")
    ApiEnvelope<List<InsightService.TrendPoint>> trends(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        HttpServletRequest request
    ) {
        return envelope(insights.trends(user.id(), from, to), request);
    }

    @GetMapping("/calendar")
    ApiEnvelope<List<InsightService.CalendarDay>> calendar(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        HttpServletRequest request
    ) {
        return envelope(insights.calendar(user.id(), from, to), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
