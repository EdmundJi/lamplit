package com.betterself.growth.town;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/town/stories")
public class TownStoryController {
    private final TownStoryService stories;
    private final Clock clock;

    public TownStoryController(TownStoryService stories, Clock clock) {
        this.stories = stories;
        this.clock = clock;
    }

    @GetMapping("/{npcCode}")
    ApiEnvelope<TownStoryService.StoryView> detail(@AuthenticationPrincipal CurrentUser user,
        @PathVariable String npcCode, HttpServletRequest request) {
        return ApiEnvelope.of(stories.detail(user.id(), npcCode), String.valueOf(request.getAttribute("requestId")), clock);
    }

    @PostMapping("/{npcCode}/advance")
    ApiEnvelope<TownStoryService.StoryView> advance(@AuthenticationPrincipal CurrentUser user,
        @PathVariable String npcCode, @RequestBody TownStoryService.Command command, HttpServletRequest request) {
        return ApiEnvelope.of(stories.advance(user.id(), npcCode, command), String.valueOf(request.getAttribute("requestId")), clock);
    }
}
