package com.betterself.growth.town;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import com.betterself.growth.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/v1/town")
public class TownController {

    private final TownService town;
    private final TownNpcService npc;
    private final TownReflectionService reflections;
    private final TownPresenceService presence;
    private final TownSocietyService society;
    private final Clock clock;
    private final Duration streamTimeout;

    public TownController(
        TownService town,
        TownNpcService npc,
        TownReflectionService reflections,
        TownPresenceService presence,
        TownSocietyService society,
        Clock clock,
        @Value("${app.town.chat-stream-timeout:PT130S}") Duration streamTimeout
    ) {
        this.town = town;
        this.npc = npc;
        this.reflections = reflections;
        this.presence = presence;
        this.society = society;
        this.clock = clock;
        this.streamTimeout = streamTimeout;
    }

    @GetMapping
    ApiEnvelope<TownService.TownView> town(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return envelope(town.town(user.id()), request);
    }

    @GetMapping("/npc/{npc}/messages")
    ApiEnvelope<List<TownNpcService.MessageView>> messages(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable("npc") String npcCode,
        HttpServletRequest request
    ) {
        return envelope(npc.messages(user.id(), npcCode), request);
    }

    @PostMapping(value = "/npc/{npc}/chat:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable("npc") String npcCode,
        @RequestBody TownNpcService.ChatCommand body
    ) {
        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        npc.chat(user.id(), npcCode, body, emitter);
        return emitter;
    }

    @PostMapping("/presence")
    ApiEnvelope<TownPresenceService.PresenceWriteView> presence(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody TownPresenceService.PresenceCommand body,
        HttpServletRequest request
    ) {
        return envelope(presence.report(user.id(), body), request);
    }

    /** 小镇名册：18 个 NPC 的档案、今日日程、以及每人今天能说的话。 */
    @GetMapping("/npcs")
    ApiEnvelope<TownSocietyService.RosterView> npcs(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(society.roster(user.id()), request);
    }

    /**
     * 某个 NPC 今天想说的话。返回的每一条都只来自他自己的 knowledge，
     * 所以小助（全知但 no_relay）在这里永远是空的——这正是限知模型该有的样子。
     */
    @GetMapping("/npc/{npc}/talking-points")
    ApiEnvelope<TownSocietyService.TalkingPointsView> talkingPoints(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable("npc") String npcCode,
        HttpServletRequest request
    ) {
        TownSocietyService.TalkingPointsView view = society.talkingPoints(user.id(), npcCode);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TOWN_NPC_NOT_FOUND", "镇上没有这个人");
        }
        return envelope(view, request);
    }

    /**
     * 护栏 A 的每日预算持久化：谁调这个接口谁消费一次共享额度，返回消费后的
     * {@code {limit, used}}。超额时 {@code used} 不再往上涨——这是幂等的饱和状态，不是错误。
     */
    @PostMapping("/initiative/consume")
    ApiEnvelope<TownSocietyService.InitiativeBudgetView> consumeInitiative(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(society.consumeInitiative(user.id()), request);
    }

    @GetMapping("/reflection/latest")
    ApiEnvelope<TownReflectionService.ReflectionView> latestReflection(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        TownReflectionService.ReflectionView view = reflections.latest(user.id());
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TOWN_REFLECTION_NOT_FOUND", "还没有反思记录");
        }
        return envelope(view, request);
    }

    @PostMapping("/reflection/generate")
    ApiEnvelope<TownReflectionService.ReflectionView> generateReflection(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        TownService.UserRow self = town.user(user.id());
        LocalDate today = clock.instant().atZone(ZoneId.of(self.timezone())).toLocalDate();
        return envelope(reflections.generate(user.id(), today), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
