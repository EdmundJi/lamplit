package com.betterself.growth.partner;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/partners")
public class PartnerController {

    private final PartnerService partners;
    private final Clock clock;

    public PartnerController(PartnerService partners, Clock clock) {
        this.partners = partners;
        this.clock = clock;
    }

    @GetMapping("/profile")
    ApiEnvelope<PartnerService.PartnerProfile> profile(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(partners.profile(user.id()), request);
    }

    @PostMapping("/pets")
    ResponseEntity<ApiEnvelope<PartnerService.PetView>> createPet(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody PartnerService.CreatePetCommand body,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(partners.createPet(user.id(), body), request));
    }

    @PatchMapping("/pets/{petId}")
    ApiEnvelope<PartnerService.PetView> updatePet(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String petId,
        @RequestBody PartnerService.UpdatePetCommand body,
        HttpServletRequest request
    ) {
        return envelope(partners.updatePet(user.id(), petId, body), request);
    }

    @PostMapping("/pets/{petId}/select")
    ApiEnvelope<PartnerService.PetView> selectPet(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String petId,
        HttpServletRequest request
    ) {
        return envelope(partners.selectPet(user.id(), petId), request);
    }

    @PostMapping("/pets/{petId}/interact")
    ApiEnvelope<PartnerService.InteractionResult> interact(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String petId,
        HttpServletRequest request
    ) {
        return envelope(partners.interact(user.id(), petId), request);
    }

    @PostMapping("/purchase")
    ApiEnvelope<PartnerService.PurchaseResult> purchase(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody PartnerService.PurchaseCommand body,
        HttpServletRequest request
    ) {
        return envelope(partners.purchase(user.id(), body), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }
}
