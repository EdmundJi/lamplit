package com.betterself.growth.social;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendGroupController {

    private final FriendGroupService groups;
    private final Clock clock;

    public FriendGroupController(FriendGroupService groups, Clock clock) {
        this.groups = groups;
        this.clock = clock;
    }

    @GetMapping("/unread-summary")
    ApiEnvelope<FriendGroupService.UnreadSummaryView> unreadSummary(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(groups.unreadSummary(user.id()), request);
    }

    @PostMapping("/groups")
    ApiEnvelope<FriendGroupService.GroupView> create(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody FriendGroupService.CreateGroupCommand body,
        HttpServletRequest request
    ) {
        return envelope(groups.create(user.id(), body.name(), body.memberPublicIds()), request);
    }

    @GetMapping("/groups")
    ApiEnvelope<List<FriendGroupService.GroupConversationView>> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(groups.list(user.id()), request);
    }

    @GetMapping("/groups/{groupPublicId}")
    ApiEnvelope<FriendGroupService.GroupView> group(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String groupPublicId,
        HttpServletRequest request
    ) {
        return envelope(groups.group(user.id(), groupPublicId), request);
    }

    @GetMapping("/groups/{groupPublicId}/messages")
    ApiEnvelope<List<FriendGroupService.GroupMessageView>> messages(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String groupPublicId,
        @RequestParam(required = false) String beforePublicId,
        @RequestParam(defaultValue = "50") int limit,
        HttpServletRequest request
    ) {
        return envelope(groups.messages(user.id(), groupPublicId, beforePublicId, limit), request);
    }

    @PostMapping("/groups/{groupPublicId}/messages")
    ApiEnvelope<FriendGroupService.GroupMessageView> send(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String groupPublicId,
        @RequestBody FriendGroupService.SendGroupMessageCommand body,
        HttpServletRequest request
    ) {
        return envelope(groups.send(user.id(), groupPublicId, body.body()), request);
    }

    @PostMapping("/groups/{groupPublicId}/read")
    ApiEnvelope<Void> markRead(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String groupPublicId,
        HttpServletRequest request
    ) {
        groups.markRead(user.id(), groupPublicId);
        return envelope(null, request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
