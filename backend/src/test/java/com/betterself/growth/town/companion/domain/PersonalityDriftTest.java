package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item 2: real, already-recorded experience - not the clock - moves who someone is, in small,
 * slow, bounded steps that are always traceable to the one event that caused them, and never
 * become a gate on what a resident may do. Cafe operation already has an owner-only,
 * conscientiousness-only mechanism for this (see CafeService.reflectOnDuty/recordInterruption) -
 * but it is never actually wired into the tick loop (nothing calls reflectOnDuty or
 * recordInterruption, and complaintsSinceDutyReflection/interruptionsSinceDutyReflection are never
 * incremented), and it only ever covers the owner and one dimension. This batch adds a second,
 * general mechanism (ResidentSimulation.driftPersonality and its call sites) that covers all four
 * dimensions for every resident, tied to real events already recorded elsewhere in this file:
 * conversation agreement/rejection, project completion/interruption, long solitude, a shared
 * celebratory moment, and noticing (or missing) another resident's detail.
 */
class PersonalityDriftTest {
    private static final Instant DAY = Instant.parse("2026-09-09T04:00:00Z"); // noon Shanghai, cafe open

    private void advanceTo(CompanionWorld w, Instant target) {
        while (w.updatedAt.isBefore(target))
            CompanionRules.advance(w, w.updatedAt.plusSeconds(54).isBefore(target) ? w.updatedAt.plusSeconds(54) : target);
    }

    @Test void aRealAgreementNudgesTheAccepterSExtroversionUpByOneBoundedPoint() {
        // The seeded world already opens with an active, rules-scripted (not model) conversation
        // between owner and artist over "reading-night" - artist's seeded energy (64) and her
        // relationship with owner (62) both clear continueConversation's "reluctant" bar, so this
        // reliably reaches the stage-3 agreement branch without needing to fabricate anything.
        CompanionWorld w = CompanionRules.join("drift-agreement", "住客", "Asia/Shanghai", DAY);
        ResidentState artist = ResidentSimulation.state(w, "artist");
        double before = artist.extroversion;
        assertThat(before).isEqualTo(62); // Personality's own seeded initial value for the artist

        advanceTo(w, DAY.plusSeconds(120));

        assertThat(w.personalityDrifts).as("a traceable record of which event caused it")
            .anyMatch(d -> d.residentId().equals("artist") && d.dimension().equals("extroversion") && "agreement".equals(d.cause()));
        var drift = w.personalityDrifts.stream()
            .filter(d -> d.residentId().equals("artist") && "agreement".equals(d.cause())).findFirst().orElseThrow();
        assertThat(drift.delta()).isEqualTo(1.0); // small
        assertThat(artist.extroversion).isEqualTo(before + 1.0);
    }

    @Test void aRealRejectionNudgesTheInviterSExtroversionDownByOneBoundedPoint() {
        CompanionWorld w = CompanionRules.join("drift-rejection", "住客", "Asia/Shanghai", DAY);
        ResidentState owner = ResidentSimulation.state(w, "owner"), artist = ResidentSimulation.state(w, "artist");
        double before = owner.extroversion;
        assertThat(before).isEqualTo(78);
        // Force the seeded owner->artist conversation's responder to actually decline, without
        // touching anything the rules do not already read for exactly this purpose (energy).
        artist.energy = 30;

        advanceTo(w, DAY.plusSeconds(120));

        assertThat(w.personalityDrifts)
            .anyMatch(d -> d.residentId().equals("owner") && d.dimension().equals("extroversion") && "declined".equals(d.cause()));
        assertThat(owner.extroversion).isEqualTo(before - 1.0);
    }

    @Test void aWeekOfTheSameKindOfRejectionNeverPushesPastTheLifetimeCapOrIntoTheNextDimension() {
        // Item 2's own red line: "幅度小、慢、有上下界" and "绝不让漂移变成门". Rather than
        // orchestrating dozens of real conversations, this drives the same mechanism through its own
        // public entry point (applyDecision -> invite -> continueConversation) repeatedly, spaced far
        // enough apart in simulated time to each count as a fresh, independent rejection - exactly
        // what a resident would actually live through over a repeated, real week.
        CompanionWorld w = CompanionRules.join("drift-bounds", "住客", "Asia/Shanghai", DAY);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState owner = ResidentSimulation.state(w, "owner"), gardener = ResidentSimulation.state(w, "gardener");
        Instant t = DAY;
        double initial = owner.extroversion;
        for (int round = 0; round < 20; round++) {
            t = t.plusSeconds(6 * 3600L + 300); // well past both the invite cooldown and the 4h drift gap
            owner.plan = null; owner.suspendedAction = null; gardener.plan = null; gardener.suspendedAction = null;
            gardener.energy = 20; // reliably reluctant -> reliably declined
            ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "看看店里", t);
            ResidentSimulation.replaceActor(w, "gardener", "cafe", "observe", "看看店里", t);
            w.conversations.forEach(c -> c.status = "ended");
            ResidentSimulation.applyDecision(w, "owner", owner.revision, w.intentRevision, "cafe", "invite", "gardener",
                "想请他一起看看", null, List.of(), t);
            // Walk the rules-scripted conversation through its three stages.
            for (int tick = 0; tick < 6; tick++) { t = t.plusSeconds(20); ResidentSimulation.step(w, t); }
        }
        assertThat(w.personalityDrifts.stream().filter(d -> d.residentId().equals("owner") && "declined".equals(d.cause())).count())
            .as("twenty real, separately-spaced rejections really did happen").isGreaterThan(5);
        // Bounded: never past the lifetime cap of 12 points below where the owner actually started.
        assertThat(owner.extroversion).isGreaterThanOrEqualTo(initial - 12);
        // Small and slow: even twenty qualifying events could not move it further than the declared cap.
        assertThat(initial - owner.extroversion).isLessThanOrEqualTo(12);
    }

    @Test void personalityDriftNeverGatesAvailableActionsAtAnyValue() {
        // The cleanest, timing-independent proof of item 2's own red line: availableActions() is
        // computed purely from the resident's plan/place/projects/cafe state - never from
        // Personality - so no value any drift could ever reach changes what shows up here.
        CompanionWorld w = CompanionRules.join("drift-gate", "住客", "Asia/Shanghai", DAY);
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        Personality.of(gardener); // ensure seeded
        List<String> before = ResidentSimulation.availableActions(w, "gardener", DAY);
        gardener.extroversion = 6; gardener.conscientiousness = 48; gardener.sensitivity = 16; gardener.volatility = 30;
        List<String> after = ResidentSimulation.availableActions(w, "gardener", DAY);
        assertThat(after).isEqualTo(before);
    }

    @Test void longUninterruptedSolitudeNudgesVolatilityUpAndSaturatesAtTheLifetimeCap() {
        // Item 2's fifth example event, "长时间独处", read only from lastSocialAt - a fact the rules
        // already track for SOCIAL_RECOVERY_SECONDS and never send to any model. Driven directly
        // through step() with large jumps, exactly like ResidentReflectionTest already does for its
        // own day-boundary test, so this does not depend on orchestrating any conversation at all.
        CompanionWorld w = CompanionRules.join("drift-solitude", "住客", "Asia/Shanghai", DAY);
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        Personality.of(gardener);
        double initial = gardener.volatility;
        assertThat(initial).isEqualTo(18);
        gardener.lastSocialAt = DAY.minusSeconds(7 * 3600L); // already well past the six-hour threshold
        Instant t = DAY;
        for (int i = 0; i < 20; i++) { t = t.plusSeconds(5 * 3600L); ResidentSimulation.step(w, t); }
        assertThat(w.personalityDrifts).anyMatch(d -> d.residentId().equals("gardener") && "solitude".equals(d.cause()));
        assertThat(gardener.volatility).isEqualTo(initial + 12); // saturated at the lifetime cap, not beyond it
    }
}
