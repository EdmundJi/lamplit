package com.betterself.growth.identity;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final Clock clock;

    public NotificationController(NotificationService notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<NotificationService.PreferenceView>> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(notifications.preferences(user.id()), request);
    }

    @PutMapping("/{channel}")
    ApiEnvelope<NotificationService.PreferenceView> update(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String channel,
        @RequestBody NotificationService.PreferenceCommand body,
        HttpServletRequest request
    ) {
        return envelope(notifications.update(user.id(), channel, body), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
