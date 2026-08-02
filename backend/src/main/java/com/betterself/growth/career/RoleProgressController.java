package com.betterself.growth.career;

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
@RequestMapping("/api/v1/progress/roles")
public class RoleProgressController {

    private final RoleProgressionService progression;
    private final Clock clock;

    public RoleProgressController(RoleProgressionService progression, Clock clock) {
        this.progression = progression;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<RoleProgressionService.RoleProgressView>> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            progression.list(user.id()),
            String.valueOf(request.getAttribute("requestId")),
            clock
        );
    }
}
