package com.betterself.growth.town.companion.interfaces;
import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import com.betterself.growth.town.companion.application.CompanionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
@RestController
@RequestMapping("/api/v1/town/companion")
public class CompanionController {
    private final CompanionService service;
    private final Clock clock;
    public CompanionController(CompanionService service,Clock clock){this.service=service;this.clock=clock;}
    @GetMapping public ApiEnvelope<CompanionService.View> get(@AuthenticationPrincipal CurrentUser user,HttpServletRequest r){return envelope(service.get(user.id()),r);}
    @PostMapping("/join") public ApiEnvelope<CompanionService.View> join(@AuthenticationPrincipal CurrentUser user,@RequestBody(required=false) CompanionService.Join body,HttpServletRequest r){return envelope(service.join(user.id(),body),r);}
    @PostMapping("/advance") public ApiEnvelope<CompanionService.View> advance(@AuthenticationPrincipal CurrentUser user,HttpServletRequest r){return envelope(service.advance(user.id()),r);}
    @PostMapping("/intents") public ApiEnvelope<CompanionService.View> submit(@AuthenticationPrincipal CurrentUser user,@RequestBody CompanionService.Command body,HttpServletRequest r){return envelope(service.submit(user.id(),body),r);}
    @DeleteMapping("/intents/{id}") public ApiEnvelope<CompanionService.View> cancel(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,HttpServletRequest r){return envelope(service.cancel(user.id(),id),r);}
    // Read-only: how many model tokens today has cost so far, by call type. No write path, no cost math.
    @GetMapping("/usage") public ApiEnvelope<CompanionService.UsageToday> usage(@AuthenticationPrincipal CurrentUser user,HttpServletRequest r){return ApiEnvelope.of(service.usageToday(user.id()),String.valueOf(r.getAttribute("requestId")),clock);}
    private ApiEnvelope<CompanionService.View> envelope(CompanionService.View view,HttpServletRequest r){return ApiEnvelope.of(view,String.valueOf(r.getAttribute("requestId")),clock);}
}
