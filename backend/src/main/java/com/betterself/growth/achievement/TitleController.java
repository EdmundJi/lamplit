package com.betterself.growth.achievement;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/titles")
public class TitleController {

    private final TitleService titles;
    private final Clock clock;

    public TitleController(TitleService titles, Clock clock) {
        this.titles = titles;
        this.clock = clock;
    }

    @GetMapping
    ApiEnvelope<List<TitleService.TitleView>> list(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            titles.list(user.id()),
            String.valueOf(request.getAttribute("requestId")),
            clock
        );
    }

    @PatchMapping("/equipped")
    ApiEnvelope<List<TitleService.TitleView>> equip(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody EquipCommand body,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            titles.equip(user.id(), body.code()),
            String.valueOf(request.getAttribute("requestId")),
            clock
        );
    }

    @DeleteMapping("/equipped")
    ApiEnvelope<List<TitleService.TitleView>> unequip(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            titles.unequip(user.id()),
            String.valueOf(request.getAttribute("requestId")),
            clock
        );
    }

    public record EquipCommand(@NotBlank(message = "称号代码不能为空") String code) {
    }
}
