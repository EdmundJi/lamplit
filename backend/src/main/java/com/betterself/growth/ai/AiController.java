package com.betterself.growth.ai;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService ai;
    private final SuggestionService suggestions;
    private final Clock clock;

    public AiController(AiService ai, SuggestionService suggestions, Clock clock) {
        this.ai = ai;
        this.suggestions = suggestions;
        this.clock = clock;
    }

    @PostMapping("/sessions")
    ResponseEntity<ApiEnvelope<AiService.SessionView>> createSession(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody AiService.CreateSessionCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(ai.createSession(user.id(), body), request));
    }

    @GetMapping("/sessions")
    ApiEnvelope<List<AiService.SessionSummaryView>> sessions(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(ai.sessions(user.id()), request);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    ApiEnvelope<List<AiService.MessageView>> messages(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String sessionId,
        HttpServletRequest request
    ) {
        return envelope(ai.messages(user.id(), sessionId), request);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String sessionId,
        @RequestBody AiService.ChatCommand body
    ) {
        SseEmitter emitter = new SseEmitter(35_000L);
        ai.stream(user.id(), sessionId, body, emitter);
        return emitter;
    }

    @PostMapping("/suggestions")
    ResponseEntity<ApiEnvelope<SuggestionService.SuggestionSetView>> generateSuggestions(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody SuggestionService.GenerateCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(suggestions.generate(user.id(), body), request));
    }

    @GetMapping("/suggestions/{setId}")
    ApiEnvelope<SuggestionService.SuggestionSetView> getSuggestions(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String setId,
        HttpServletRequest request
    ) {
        return envelope(suggestions.set(user.id(), setId), request);
    }

    @PostMapping("/suggestions/{setId}/adopt")
    ApiEnvelope<SuggestionService.AdoptionResult> adoptSuggestions(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String setId,
        @RequestHeader(name = "Idempotency-Key", required = false) String key,
        @RequestBody SuggestionService.AdoptCommand body,
        HttpServletRequest request
    ) {
        return envelope(suggestions.adopt(user.id(), setId, key, body), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
