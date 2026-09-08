package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.Project;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import com.betterself.growth.town.companion.domain.CompanionWorld.ServiceRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * The coffee/water service chain: proves the two things the task calls out as the actual test of
 * whether this is real emergence rather than a script - the chain can break, and the same mechanism
 * that lets the owner become more careful can also let them become less careful.
 */
class CafeServiceTest {
    private final Instant start = Instant.parse("2026-09-08T10:00:00Z"); // within business hours

    // ---- the chain breaks: owner out, someone thirsty, nobody comes ---------------------------

    @Test void aRequestMadeWhileTheOwnerIsAwayGoesUnservedAndOnlyEverWrittenIntoMemory() {
        CompanionWorld w = CompanionRules.join("cafe-break", "住客", "Asia/Shanghai", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentState student = ResidentSimulation.state(w, "student");
        // Send the owner out to the garden and keep them stuck on an unrelated errand there, well past
        // any patience the student could have - this is the "老板跑出去做自己的事" half of the scenario.
        ResidentSimulation.replaceActor(w, "owner", "garden", "observe", "在花园里看看", start.plusSeconds(6000));
        TownPlaces.release(w, "owner");
        TownPlaces.claim(w, "owner", "garden", null, start);
        // The student, at the cafe, wants a drink.
        ResidentSimulation.replaceActor(w, "student", "cafe", "study", "在窗边复习", start.plusSeconds(6000));
        TownPlaces.release(w, "student");
        TownPlaces.claim(w, "student", "cafe", "seat", start);
        // initialize()'s own deterministic warm-start can leave a request from before this test's
        // scenario is set up; start this test's own request-and-serve history from a clean slate.
        w.serviceRequests.clear();
        int memoriesBefore = (int) w.memories.stream().filter(m -> m.ownerId().equals("student")).count();
        CafeService.request(w, student, start);
        assertThat(w.serviceRequests).hasSize(1);
        ServiceRequest request = w.serviceRequests.get(0);
        assertThat(request.status).isEqualTo("waiting");

        // Advance real time well past this student's patience threshold. The owner never moves, never
        // ticks any duty decision, never claims the counter - exactly "店主外出且有人渴了".
        Instant at = start;
        for (int i = 0; i < 20; i++) { at = at.plusSeconds(6); CafeService.tick(w, at); }

        // The request was never fulfilled - it broke, it did not silently succeed.
        assertThat(request.status).isEqualTo("abandoned");
        assertThat(request.resolvedAt).isNotNull();
        assertThat(List.of("consumed", "delivered", "preparing")).doesNotContain(request.status);
        // It is recorded only in the thirsty resident's own memory - never announced as a WorldEvent
        // (the student here is introverted enough, extroversion 20, that this stays unspoken too).
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student")).count()).isGreaterThan(memoriesBefore);
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "service".equals(m.topicId())))
            .anySatisfy(m -> assertThat(m.text()).contains("没人来"));
        assertThat(w.events.stream().filter(e -> "complaint".equals(e.type()))).isEmpty();
    }

    /** The same scenario end-to-end, through the real advance()/step() loop rather than calling
     * CafeService directly: the owner is genuinely off doing something else (a committed Plan whose
     * end is far beyond the student's patience window, so choose() never even runs for the owner
     * during this window - the same gate the production loop uses), and a whole real simulated
     * afternoon passes. This is "老板跑出去做自己的事" itself, not a stand-in for it. */
    @Test void endToEndTheOwnerBeingGenuinelyAwayLeavesARealRequestUnservedInTheFullSimulationLoop() {
        CompanionWorld w = CompanionRules.join("cafe-break-e2e", "住客", "Asia/Shanghai", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentState student = ResidentSimulation.state(w, "student");
        // initialize()'s own warm-start unconditionally leaves the owner mid-conversation with the
        // artist; left alone, that conversation would end on its own and null out any plan set below
        // (see ConversationLifecycle.finish) regardless of what it was, and choose() would then run
        // early. End it properly through the real API first, so the owner-away setup below actually
        // sticks for the whole window.
        w.conversations.stream().filter(c -> c.status.equals("active") && c.participantIds.contains("owner"))
            .forEach(c -> ConversationLifecycle.finish(w, c, start, "试验场景：提前结束"));
        // "sleep" (not "observe") matters here: it is one of the plan actions that make a resident
        // ineligible to be pulled into a conversation by someone else (see step()'s invite loop) - the
        // owner must stay genuinely unreachable for the whole window, not just uninvolved in choose().
        String home = TownPlaces.homeOf("owner");
        owner.plan = new CompanionWorld.Plan("p-away", "sleep", home, null, "回家歇了一会儿，没有回柜台", start, start.plusSeconds(3000));
        ResidentSimulation.replaceActor(w, "owner", home, "sleep", "回家歇了一会儿，没有回柜台", start.plusSeconds(3000));
        TownPlaces.release(w, "owner"); TownPlaces.claim(w, "owner", home, "bed", start);
        ResidentSimulation.replaceActor(w, "student", "cafe", "study", "在窗边复习", start.plusSeconds(3000));
        TownPlaces.release(w, "student"); TownPlaces.claim(w, "student", "cafe", "seat", start);
        w.serviceRequests.clear();
        CafeService.request(w, student, start);
        ServiceRequest request = w.serviceRequests.get(w.serviceRequests.size() - 1);
        int memoriesBefore = (int) w.memories.stream().filter(m -> m.ownerId().equals("student")).count();

        for (int second = 6; second <= 600; second += 6) CompanionRules.advance(w, start.plusSeconds(second));

        // The owner never actually served it - the plan committed above never let choose() run for
        // them during this whole window - so the request broke rather than silently succeeding.
        assertThat(request.status).isEqualTo("abandoned");
        assertThat(owner.dutyPressure).isGreaterThan(0); // pressure still visibly built up while ignored
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student")).count()).isGreaterThan(memoriesBefore);
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "service".equals(m.topicId()))).isNotEmpty();
    }

    @Test void anExplicitRequestIsAlwaysConsumedOnceDeliveredButAProactiveGuessCanGoColdUnwanted() {
        CompanionWorld w = CompanionRules.join("cafe-cold", "住客", "Asia/Shanghai", start);
        ResidentState requester = ResidentSimulation.state(w, "student");
        ResidentSimulation.replaceActor(w, "student", "cafe", "study", "在窗边复习", start.plusSeconds(6000));
        TownPlaces.claim(w, "student", "cafe", "seat", start);
        w.serviceRequests.clear();
        // An explicit, already-delivered drink: always picked up.
        ServiceRequest explicit = new ServiceRequest();
        explicit.id = "sr-explicit"; explicit.requesterId = "student"; explicit.kind = "coffee"; explicit.place = "cafe";
        explicit.status = "delivered"; explicit.requestedAt = start; explicit.preparingAt = start; explicit.deliveredAt = start;
        w.serviceRequests.add(explicit);
        CafeService.tick(w, start.plusSeconds(6));
        assertThat(explicit.status).isEqualTo("consumed");

        // A proactive guess, delivered while the requester is not currently low on energy, is not
        // picked up - and after the cold window, it is written off, never consumed.
        requester.energy = 90;
        ServiceRequest guess = new ServiceRequest();
        guess.id = "sr-guess"; guess.requesterId = "student"; guess.kind = "water"; guess.place = "cafe";
        guess.status = "delivered"; guess.proactive = true; guess.requestedAt = start; guess.preparingAt = start; guess.deliveredAt = start;
        w.serviceRequests.add(guess);
        Instant at = start;
        for (int i = 0; i < 20; i++) { at = at.plusSeconds(6); CafeService.tick(w, at); }
        assertThat(guess.status).isEqualTo("cold");
    }

    // ---- allowed to grow either way: the decisive proof this is emergence, not a ratchet ------

    @Test void repeatedVoicedComplaintsNudgeConscientiousnessUpAndRepeatedInterruptionsWithNoComplaintsNudgeItDown() {
        CompanionWorld complained = CompanionRules.join("cafe-drift-up", "住客", "Asia/Shanghai", start);
        ResidentState complainedOwner = ResidentSimulation.state(complained, "owner");
        double before1 = Personality.of(complainedOwner).conscientiousness();
        // A real history of two customers, twice each, waiting too long and (being extroverted enough
        // to say so - the artist's extroversion is 62) actually voicing it - driven through the real
        // abandon() path via reapWaiting(), not by poking the counters directly.
        ResidentState artist = ResidentSimulation.state(complained, "artist");
        for (int i = 0; i < 3; i++) {
            Instant requestedAt = start.plusSeconds(i * 1000L);
            ResidentSimulation.replaceActor(complained, "artist", "cafe", "observe", "在咖啡馆里", requestedAt.plusSeconds(6000));
            TownPlaces.release(complained, "artist");
            TownPlaces.claim(complained, "artist", "cafe", null, requestedAt);
            complained.serviceRequests.removeIf(r -> "artist".equals(r.requesterId) && !java.util.Set.of("consumed","abandoned","cold").contains(r.status));
            CafeService.request(complained, artist, requestedAt);
            Instant at = requestedAt;
            for (int t = 0; t < 20; t++) { at = at.plusSeconds(6); CafeService.tick(complained, at); }
        }
        assertThat(complainedOwner.complaintsSinceDutyReflection).isGreaterThanOrEqualTo(2);
        Instant reflectAt = start.plusSeconds(400);
        CafeService.reflectOnDuty(complained, complainedOwner, reflectAt);
        double after1 = Personality.of(complainedOwner).conscientiousness();
        assertThat(after1).isGreaterThan(before1);

        CompanionWorld ignored = CompanionRules.join("cafe-drift-down", "住客", "Asia/Shanghai", start);
        ResidentState ignoredOwner = ResidentSimulation.state(ignored, "owner");
        double before2 = Personality.of(ignoredOwner).conscientiousness();
        // A real history of the owner's own project being repeatedly interrupted by duty, with nobody
        // ever complaining - driven through the real recordInterruption() path.
        Project ownProject = ignored.projects.stream().filter(p -> "owner".equals(p.ownerId)).findFirst().orElseThrow();
        for (int i = 0; i < 4; i++) CafeService.recordInterruption(ignored, ignoredOwner, ownProject, start.plusSeconds(i * 30L));
        assertThat(ignoredOwner.interruptionsSinceDutyReflection).isGreaterThanOrEqualTo(3);
        assertThat(ignoredOwner.complaintsSinceDutyReflection).isZero();
        CafeService.reflectOnDuty(ignored, ignoredOwner, reflectAt);
        double after2 = Personality.of(ignoredOwner).conscientiousness();
        assertThat(after2).isLessThan(before2);

        // The decisive assertion: the very same mechanism (reflectOnDuty, reading dutyPressure-chain
        // evidence) moved the same starting trait in opposite directions under two different histories.
        assertThat(after1).isGreaterThan(before1);
        assertThat(after2).isLessThan(before2);
        // Bounded: neither move flips the character - both stay well short of the 0/100 extremes.
        assertThat(after1).isLessThanOrEqualTo(95);
        assertThat(after2).isGreaterThanOrEqualTo(15);
    }

    @Test void dutyPressureBuildsWhileIgnoredAndDecaysOnceTheQueueClears() {
        CompanionWorld w = CompanionRules.join("cafe-pressure", "住客", "Asia/Shanghai", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.dutyPressure = 0;
        ResidentState student = ResidentSimulation.state(w, "student");
        ResidentSimulation.replaceActor(w, "student", "cafe", "study", "在窗边复习", start.plusSeconds(6000));
        TownPlaces.claim(w, "student", "cafe", "seat", start);
        w.serviceRequests.clear();
        CafeService.request(w, student, start);
        Instant at = start;
        for (int i = 0; i < 5; i++) { at = at.plusSeconds(6); CafeService.accruePressure(w, owner, at); }
        assertThat(owner.dutyPressure).isGreaterThan(0);
        double withQueue = owner.dutyPressure;
        w.serviceRequests.clear();
        for (int i = 0; i < 10; i++) { at = at.plusSeconds(6); CafeService.accruePressure(w, owner, at); }
        assertThat(owner.dutyPressure).isLessThan(withQueue);
    }

    // ---- end-to-end: the whole autonomous loop, not a hand-built scenario ---------------------

    /** Every other test above hand-builds a scenario and drives CafeService's own methods directly.
     * That is exactly the kind of test that can pass while the mechanism is dead in a real, running
     * world - which is what actually happened: a batch of this same machinery shipped with green
     * unit tests while a real world's serviceRequests were 3-for-3 abandoned, dutyPressure sat at 0,
     * and lastDutyReflectionAt was null because {@link ResidentSimulation#choose} only ever ran
     * between plans, never during one. This test drives nothing but the public entry point the app
     * itself calls every tick - {@link CompanionRules#advance} - over a real multi-day span, and
     * asserts the counters this whole batch is about actually moved on their own: requests get
     * consumed (not just created and abandoned), dutyPressure is a genuinely live quantity,
     * reflectOnDuty actually runs, and conscientiousness - the one field that proves this is
     * reflection moving a trait rather than a memory - actually drifts away from its seed value. */
    @Test void endToEndARealMultiDaySimulationActuallyMovesTheDutyCounters() {
        CompanionWorld w = CompanionRules.join("cafe-e2e-lifecycle", "住客", "Asia/Shanghai", start);
        ResidentState owner = ResidentSimulation.state(w, "owner");
        double seeded = Personality.of(owner).conscientiousness();
        boolean pressureEverPositive = false, reflectionRan = false;
        int peakComplaints = 0, peakInterruptions = 0;
        double conscientiousnessMin = seeded, conscientiousnessMax = seeded;
        Instant at = start, end = start.plusSeconds(5L * 86400); // real advance() cadence
        while (at.isBefore(end)) {
            at = at.plusSeconds(60);
            CompanionRules.advance(w, at);
            pressureEverPositive |= owner.dutyPressure > 0;
            peakComplaints = Math.max(peakComplaints, owner.complaintsSinceDutyReflection);
            peakInterruptions = Math.max(peakInterruptions, owner.interruptionsSinceDutyReflection);
            reflectionRan |= owner.lastDutyReflectionAt != null;
            double c = Personality.of(owner).conscientiousness();
            conscientiousnessMin = Math.min(conscientiousnessMin, c);
            conscientiousnessMax = Math.max(conscientiousnessMax, c);
        }
        long total = w.serviceRequests.size();
        long consumed = w.serviceRequests.stream().filter(r -> "consumed".equals(r.status)).count();
        assertThat(total).isGreaterThan(0); // residents actually asked for a drink at some point
        assertThat(consumed).isGreaterThan(0); // and duty actually won often enough to serve some of them
        assertThat(pressureEverPositive).isTrue(); // dutyPressure is a real, moving number, not stuck at 0
        // Evidence accumulated at some point, even if a given reflection window consumed it below the
        // threshold - see the reflectOnDuty fix this batch makes: sub-threshold evidence must survive
        // across windows rather than being silently discarded, or it can never reach the threshold.
        assertThat(peakComplaints + peakInterruptions).isGreaterThan(0);
        assertThat(reflectionRan).isTrue(); // reflectOnDuty's call site is actually reached and acts
        // The decisive assertion: over a real run, conscientiousness itself is not frozen at 85.
        assertThat(conscientiousnessMax - conscientiousnessMin).isGreaterThan(0);
    }
}
