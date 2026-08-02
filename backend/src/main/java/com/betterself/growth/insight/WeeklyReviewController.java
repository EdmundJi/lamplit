package com.betterself.growth.insight;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/reviews/weekly")
public class WeeklyReviewController {

    private final WeeklyReviewService reviews;
    private final Clock clock;

    public WeeklyReviewController(WeeklyReviewService reviews, Clock clock) {
        this.reviews = reviews;
        this.clock = clock;
    }

    @GetMapping("/{planId}")
    ApiEnvelope<WeeklyReviewService.ReviewView> get(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String planId,
        HttpServletRequest request
    ) {
        return envelope(reviews.review(user.id(), planId), request);
    }

    @PatchMapping("/{planId}")
    ApiEnvelope<WeeklyReviewService.ReviewView> update(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String planId,
        @RequestBody WeeklyReviewService.ReviewCommand body,
        HttpServletRequest request
    ) {
        return envelope(reviews.update(user.id(), planId, body), request);
    }

    @PostMapping("/{planId}/confirm")
    ApiEnvelope<WeeklyReviewService.ReviewView> confirm(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String planId,
        @RequestBody(required = false) WeeklyReviewService.ConfirmationCommand body,
        HttpServletRequest request
    ) {
        return envelope(reviews.confirm(user.id(), planId, body), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
