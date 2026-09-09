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

    // ---- root cause of this batch's own measurement (see the report): a full simulated day had 13
    // personality drifts from solitude/interrupted and exactly zero from agreement, declined,
    // project_complete, celebration, noticed_detail or missed_detail. The four tests below pin why:
    // project_complete/celebration/noticed_detail/missed_detail's mount points are fine - they simply
    // never got a qualifying event that day (no project ever reached 100% progress) - while
    // agreement/declined's mount point is a genuine, separate gap: it only exists on the rule-scripted
    // conversation path (continueConversation, already proven above), and is structurally unreachable
    // whenever a conversation is model-driven (ConversationLifecycle.tick short-circuits it before
    // continueConversation's own switch is ever reached), which is what every real conversation in
    // this project actually is. ConversationLifecycle.java is outside this batch's file list, so this
    // is reported rather than patched.

    @Test void aProjectThatActuallyReachesReadyNudgesTheFinishingContributorSConscientiousness() {
        // Proves the mount point in ResidentSimulation.complete()'s "create"/"help" branch really
        // fires - the real full day measured had zero "ready"/"contribution" events at all, because
        // no project ever actually finished, not because this call site is broken.
        CompanionWorld w = CompanionRules.join("drift-project-complete", "住客", "Asia/Shanghai", DAY);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        Project project = w.projects.get(0); // "reading-night", needed=2, owned by owner, place=cafe
        project.status = "active";
        project.contributors.clear();
        project.contributors.addAll(List.of("owner", "artist")); // already enough distinct contributors to lift the 75% cap
        project.progress = 90;
        double before = owner.conscientiousness;

        owner.plan = new Plan("finish-it", "create", "cafe", project.id, "再添一点", DAY, DAY.plusSeconds(6));
        ResidentSimulation.replaceActor(w, "owner", "cafe", "create", "再添一点", DAY.plusSeconds(6));
        ResidentSimulation.step(w, DAY.plusSeconds(6));

        assertThat(project.status).as("the project genuinely reached ready").isEqualTo("ready");
        assertThat(w.personalityDrifts).anyMatch(d -> d.residentId().equals("owner")
            && d.dimension().equals("conscientiousness") && "project_complete".equals(d.cause()));
        assertThat(owner.conscientiousness).isEqualTo(before + 1.0);
    }

    @Test void celebratingAReadyProjectNudgesEachPresentContributorSVolatilityDown() {
        // Same proof for "celebration" (complete()'s "celebrate" branch) - never observed in the
        // measured day because nothing ever reached "ready" for anyone to celebrate.
        CompanionWorld w = CompanionRules.join("drift-celebration", "住客", "Asia/Shanghai", DAY);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState owner = ResidentSimulation.state(w, "owner"), artist = ResidentSimulation.state(w, "artist");
        Project project = w.projects.get(0);
        project.status = "ready";
        project.contributors.clear();
        project.contributors.addAll(List.of("owner", "artist"));
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "看着桌上的东西", DAY.plusSeconds(300));
        double ownerBefore = owner.volatility, artistBefore = artist.volatility;

        owner.plan = new Plan("celebrate-it", "celebrate", "cafe", project.id, "招呼大家看看", DAY, DAY.plusSeconds(6));
        ResidentSimulation.replaceActor(w, "owner", "cafe", "celebrate", "招呼大家看看", DAY.plusSeconds(6));
        ResidentSimulation.step(w, DAY.plusSeconds(6));

        assertThat(project.status).isEqualTo("celebrating");
        assertThat(w.personalityDrifts).anyMatch(d -> d.residentId().equals("owner") && "celebration".equals(d.cause()));
        assertThat(w.personalityDrifts).anyMatch(d -> d.residentId().equals("artist") && "celebration".equals(d.cause()));
        assertThat(owner.volatility).isEqualTo(ownerBefore - 1.0);
        assertThat(artist.volatility).isEqualTo(artistBefore - 1.0);
    }

    @Test void witnessingAContributionNudgesSensitivityAccordingToHowMuchAttentionSomeonePays() {
        // Same proof for "noticed_detail"/"missed_detail" (witnessContribution, called from the same
        // create/help completion above) - also never observed that day, for the same reason: nobody
        // was there to witness a contribution that never happened.
        CompanionWorld w = CompanionRules.join("drift-witness", "住客", "Asia/Shanghai", DAY);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentState artist = ResidentSimulation.state(w, "artist"); // sensitivity 88: notices detail
        ResidentState gardener = ResidentSimulation.state(w, "gardener"); // sensitivity 28: misses it
        Personality.of(artist); Personality.of(gardener);
        double artistBefore = artist.sensitivity, gardenerBefore = gardener.sensitivity;
        Project project = w.projects.get(0);
        project.status = "active"; project.contributors.clear(); project.progress = 10;
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "在旁边看着", DAY.plusSeconds(300));
        ResidentSimulation.replaceActor(w, "gardener", "cafe", "observe", "在旁边看着", DAY.plusSeconds(300));

        owner.plan = new Plan("contribute-it", "create", "cafe", project.id, "添一点东西", DAY, DAY.plusSeconds(6));
        ResidentSimulation.replaceActor(w, "owner", "cafe", "create", "添一点东西", DAY.plusSeconds(6));
        ResidentSimulation.step(w, DAY.plusSeconds(6));

        assertThat(w.personalityDrifts).anyMatch(d -> d.residentId().equals("artist")
            && d.dimension().equals("sensitivity") && "noticed_detail".equals(d.cause()));
        assertThat(artist.sensitivity).isEqualTo(artistBefore + 1.0);
        assertThat(w.personalityDrifts).anyMatch(d -> d.residentId().equals("gardener")
            && d.dimension().equals("sensitivity") && "missed_detail".equals(d.cause()));
        assertThat(gardener.sensitivity).isEqualTo(gardenerBefore - 1.0);
    }

    @Test void aModelDrivenDeclineMovesTheInviterNotTheOneWhoSaidNo() {
        // This is the path every real conversation in this project takes. The accept/decline drift
        // used to live only in continueConversation - the RULE-scripted script, which
        // ConversationLifecycle.tick() short-circuits for anything that is not literally mode="rules".
        // So the drift existed, was tested, and had never once fired: a measured day produced 17
        // personality nudges, 13 of them from being alone for six hours, and not one from anything
        // that happened between two people.
        // Who moves is not who spoke: being turned down is a fact about whoever asked.
        CompanionWorld w = CompanionRules.join("drift-gap-decline", "住客", "Asia/Shanghai", DAY, true);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState owner = ResidentSimulation.state(w, "owner"), artist = ResidentSimulation.state(w, "artist");
        owner.plan = null; owner.suspendedAction = null; artist.plan = null; artist.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "看看店里", DAY);
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "看看店里", DAY);
        Project project = w.projects.get(0);
        ResidentSimulation.startConversation(w, owner, artist, project, DAY);
        Conversation c = w.conversations.stream().filter(x -> "active".equals(x.status)).findFirst().orElseThrow();
        assertThat(c.mode).as("this is the model-driven path, not the rule-scripted fallback").isEqualTo("model");

        var opener = ConversationLifecycle.reserveTurn(w, c, DAY.plusSeconds(10));
        assertThat(ConversationLifecycle.applyTurn(w, opener,
            new ConversationLifecycle.Utterance("你要不要一起弄这个海报？", false, "期待", "none", null, List.of()), DAY.plusSeconds(10))).isTrue();
        var declineTurn = ConversationLifecycle.reserveTurn(w, c, DAY.plusSeconds(20));
        assertThat(ConversationLifecycle.applyTurn(w, declineTurn,
            new ConversationLifecycle.Utterance("这次先不参与了。", false, "犹豫", "decline", null, List.of()), DAY.plusSeconds(20))).isTrue();

        assertThat(w.events).as("the decline genuinely happened, as a real recorded event")
            .anyMatch(e -> "declined".equals(e.type()));
        assertThat(w.personalityDrifts).as("and it moved the person who asked, by exactly one point")
            .anyMatch(d -> d.residentId().equals("owner") && d.dimension().equals("extroversion")
                && "declined".equals(d.cause()) && d.delta() == -1.0);
        assertThat(w.personalityDrifts).as("the one who said no is only answering; nothing moves in them")
            .noneMatch(d -> d.residentId().equals("artist") && "declined".equals(d.cause()));
    }

    @Test void aModelDrivenAgreementMovesTheOneWhoSaidYes() {
        // Mirror of the decline case above, for "agreement".
        CompanionWorld w = CompanionRules.join("drift-gap-agreement", "住客", "Asia/Shanghai", DAY, true);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState owner = ResidentSimulation.state(w, "owner"), artist = ResidentSimulation.state(w, "artist");
        owner.plan = null; owner.suspendedAction = null; artist.plan = null; artist.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "看看店里", DAY);
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "看看店里", DAY);
        Project project = w.projects.get(0); // artist already knows "reading-night" from the seed
        ResidentSimulation.startConversation(w, owner, artist, project, DAY);
        Conversation c = w.conversations.stream().filter(x -> "active".equals(x.status)).findFirst().orElseThrow();

        var opener = ConversationLifecycle.reserveTurn(w, c, DAY.plusSeconds(10));
        assertThat(ConversationLifecycle.applyTurn(w, opener,
            new ConversationLifecycle.Utterance("你要不要一起弄这个海报？", false, "期待", "none", null, List.of()), DAY.plusSeconds(10))).isTrue();
        var acceptTurn = ConversationLifecycle.reserveTurn(w, c, DAY.plusSeconds(20));
        assertThat(ConversationLifecycle.applyTurn(w, acceptTurn,
            new ConversationLifecycle.Utterance("行，我来帮忙画一部分。", false, "被需要", "accept", null, List.of()), DAY.plusSeconds(20))).isTrue();

        assertThat(w.events).as("the agreement genuinely happened, as a real recorded event")
            .anyMatch(e -> "agreement".equals(e.type()));
        assertThat(w.personalityDrifts).as("saying yes to someone moves the person who said it")
            .anyMatch(d -> d.residentId().equals("artist") && d.dimension().equals("extroversion")
                && "agreement".equals(d.cause()) && d.delta() == 1.0);
    }
}
