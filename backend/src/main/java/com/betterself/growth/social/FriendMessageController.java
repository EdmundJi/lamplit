package com.betterself.growth.social;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendMessageController {

    private final FriendMessageService messages;
    private final Clock clock;

    public FriendMessageController(FriendMessageService messages, Clock clock) {
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/conversations")
    ApiEnvelope<List<FriendMessageService.ConversationView>> conversations(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(messages.conversations(user.id()), request);
    }

    @GetMapping("/messages")
    ApiEnvelope<List<FriendMessageService.MessageView>> messages(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam String peerPublicId,
        @RequestParam(required = false) String beforePublicId,
        @RequestParam(defaultValue = "50") int limit,
        HttpServletRequest request
    ) {
        return envelope(messages.messages(user.id(), peerPublicId, beforePublicId, limit), request);
    }

    @PostMapping("/messages")
    ApiEnvelope<FriendMessageService.MessageView> send(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody FriendMessageService.SendMessageCommand body,
        HttpServletRequest request
    ) {
        return envelope(messages.send(user.id(), body.peerPublicId(), body.body()), request);
    }

    @PostMapping("/messages/read")
    ApiEnvelope<Void> markRead(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody FriendMessageService.ReadMessagesCommand body,
        HttpServletRequest request
    ) {
        messages.markRead(user.id(), body.peerPublicId());
        return envelope(null, request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
