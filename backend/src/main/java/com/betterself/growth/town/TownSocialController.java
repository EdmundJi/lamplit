package com.betterself.growth.town;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * M4 的信件与树洞入口（plan §2.3 D15、§2.7、§2.8）：邮递员的收件箱，以及寄给树洞笔友的信。
 *
 * <p>刻意与 {@code TownController} 分开成新文件——两边并行开发，这个文件只属于本轮新增的三条路由，
 * 不改动任何既有接口的行为。
 */
@RestController
@RequestMapping("/api/v1/town")
public class TownSocialController {

    private final TownLetterService letters;
    private final TownConfidantService confidant;
    private final Clock clock;
    private final TownEventService events;

    public TownSocialController(TownLetterService letters, TownConfidantService confidant, Clock clock, TownEventService events) {
        this.letters = letters;
        this.confidant = confidant;
        this.clock = clock;
        this.events = events;
    }

    @GetMapping("/events")
    ApiEnvelope<java.util.List<TownEventService.EventView>> events(
        @AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return envelope(events.today(user.id()), request);
    }

    /** Count only: background map polling must not fetch private letters. */
    @GetMapping("/letters/unread")
    ApiEnvelope<TownLetterService.UnreadCountView> unreadLetters(
        @AuthenticationPrincipal CurrentUser user, HttpServletRequest request
    ) {
        return envelope(letters.unreadCount(user.id()), request);
    }

    /** 收件箱：树洞长信 / NPC 短笺 / 活动请柬三轨都在这里，含未读数。 */
    @GetMapping("/letters")
    ApiEnvelope<TownLetterService.LetterInboxView> letters(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(letters.inbox(user.id()), request);
    }

    /** 已读的信再读一次也不报错——幂等。 */
    @PostMapping("/letters/{id}/read")
    ApiEnvelope<Void> markRead(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable("id") String letterPublicId,
        HttpServletRequest request
    ) {
        letters.markRead(user.id(), letterPublicId);
        return envelope(null, request);
    }

    /** 写信寄给树洞笔友。回信要等下一次夜间流水线跑过才会送到——写信本身不产生任何回信。 */
    @PostMapping("/confidant")
    ApiEnvelope<Void> writeToConfidant(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody ConfidantWriteCommand body,
        HttpServletRequest request
    ) {
        confidant.write(user.id(), body == null ? null : body.message());
        return envelope(null, request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }

    public record ConfidantWriteCommand(String message) {
    }
}
