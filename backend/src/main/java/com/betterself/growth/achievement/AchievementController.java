package com.betterself.growth.achievement;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/achievements")
public class AchievementController {

    private final AchievementService achievements;
    private final Clock clock;

    public AchievementController(AchievementService achievements, Clock clock) {
        this.achievements = achievements;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<AchievementService.AchievementView>> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            achievements.list(user.id()),
            String.valueOf(request.getAttribute("requestId")),
            clock
        );
    }
}
