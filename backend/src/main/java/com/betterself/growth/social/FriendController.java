package com.betterself.growth.social;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {

    private final FriendService friends;
    private final Clock clock;

    public FriendController(FriendService friends, Clock clock) {
        this.friends = friends;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<FriendService.FriendListView> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(friends.list(user.id()), request);
    }

    @PostMapping("/requests")
    ResponseEntity<ApiEnvelope<FriendService.FriendItem>> request(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody FriendService.RequestCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(envelope(friends.request(user.id(), body.email()), request));
    }

    @GetMapping("/{peerPublicId}")
    ApiEnvelope<FriendService.FriendProfileView> detail(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String peerPublicId,
        HttpServletRequest request
    ) {
        return envelope(friends.detail(user.id(), peerPublicId), request);
    }

    @GetMapping("/{peerPublicId}/summary")
    ApiEnvelope<FriendService.FriendSummaryView> summary(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String peerPublicId,
        HttpServletRequest request
    ) {
        return envelope(friends.summary(user.id(), peerPublicId), request);
    }

    @PostMapping("/{peerPublicId}/accept")
    ApiEnvelope<FriendService.FriendItem> accept(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String peerPublicId,
        HttpServletRequest request
    ) {
        return envelope(friends.accept(user.id(), peerPublicId), request);
    }

    @PostMapping("/{peerPublicId}/reject")
    ResponseEntity<Void> reject(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String peerPublicId
    ) {
        friends.reject(user.id(), peerPublicId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{peerPublicId}")
    ResponseEntity<Void> remove(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String peerPublicId
    ) {
        friends.remove(user.id(), peerPublicId);
        return ResponseEntity.noContent().build();
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
