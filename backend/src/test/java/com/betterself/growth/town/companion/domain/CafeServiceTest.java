package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.Project;
import com.betterself.growth.town.companion.domain.CompanionWorld.Plan;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import com.betterself.growth.town.companion.domain.CompanionWorld.ServiceRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

        // Waiting a long time becomes a perception; it does not make the rules decide to leave or
        // complain on the student's behalf.
        Instant at = start;
        for (int i = 0; i < 20; i++) { at = at.plusSeconds(6); CafeService.tick(w, at); }
        assertThat(request.status).isEqualTo("waiting");
        assertThat(ResidentSimulation.salientPerceptions(w,"student",at)).contains("这杯已经等了一阵，还没有人来做");
        assertThat(w.events.stream().filter(e -> "complaint".equals(e.type()))).isEmpty();

        // Once the resident's own chosen action actually leaves the cafe, the physical request
        // chain can truthfully record that it was abandoned.
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,"home","rest",null,"先回家",null,List.of(),at)).isTrue();
        CafeService.tick(w,at.plusSeconds(1));

        assertThat(request.status).isEqualTo("abandoned");
        assertThat(request.resolvedAt).isNotNull();
        assertThat(List.of("consumed", "delivered", "preparing")).doesNotContain(request.status);
        // It is recorded only in the thirsty resident's own memory, never turned into invented speech.
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student")).count()).isGreaterThan(memoriesBefore);
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "service".equals(m.topicId())))
            .anySatisfy(m -> assertThat(m.text()).contains("离开咖啡馆"));
        assertThat(w.events.stream().filter(e -> "complaint".equals(e.type()))).isEmpty();
    }

    /** The same scenario end-to-end, through the real advance()/step() loop rather than calling
     * CafeService directly: the owner is genuinely off doing something else (a committed Plan whose
     * end is far beyond the student's patience window, so choose() never even runs for the owner
     * during this window - the same gate the production loop uses), and a whole real simulated
     * afternoon passes. This is "老板跑出去做自己的事" itself, not a stand-in for it. */
    @Test void endToEndTheOwnerBeingGenuinelyAwayLeavesAWaitingRequestWithoutInventingAReaction() {
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
        for (int second = 6; second <= 600; second += 6) CompanionRules.advance(w, start.plusSeconds(second));

        assertThat(request.status).isEqualTo("waiting");
        assertThat(owner.plan.action()).isEqualTo("sleep"); // the queue never wakes a sleeping operator
        assertThat(ResidentSimulation.salientPerceptions(w,"student",start.plusSeconds(600))).contains("这杯已经等了一阵，还没有人来做");
        assertThat(w.events).noneMatch(event->"complaint".equals(event.type()));
    }

    @Test void aDeliveredDrinkIsConsumedWhenTheRequesterIsThereAndGoesColdWhenTheyHaveLeft() {
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

        // The link is allowed to break at the last step too: a cup delivered to someone who has since
        // walked out is never consumed on their behalf - after the cold window it is simply written off.
        ResidentSimulation.replaceActor(w, "student", "garden", "observe", "去花园透口气", start.plusSeconds(6000));
        ServiceRequest late = new ServiceRequest();
        late.id = "sr-late"; late.requesterId = "student"; late.kind = "coffee"; late.place = "cafe";
        late.status = "delivered"; late.requestedAt = start; late.preparingAt = start; late.deliveredAt = start;
        w.serviceRequests.add(late);
        Instant at = start;
        for (int i = 0; i < 20; i++) { at = at.plusSeconds(6); CafeService.tick(w, at); }
        assertThat(late.status).isEqualTo("cold");
    }

    @Test void restingAtTheCafeDoesNotOrderADrinkUnlessTheModelChoosesThatAction(){
        CompanionWorld w=CompanionRules.join("explicit-drink","住客","Asia/Shanghai",start,true);
        w.conversations.stream().filter(c->"active".equals(c.status)).forEach(c->ConversationLifecycle.finish(w,c,start,"测试准备"));
        ResidentState student=ResidentSimulation.state(w,"student");w.serviceRequests.clear();
        ResidentSimulation.replaceActor(w,"student","cafe","idle","坐了一会儿",start.plusSeconds(60));student.plan=null;
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,"cafe","rest",null,"坐一会儿",null,List.of(),start)).isTrue();Plan resting=student.plan;
        assertThat(w.serviceRequests).isEmpty();
        assertThat(ResidentSimulation.availableActions(w,"student",start.plusSeconds(1))).contains("request_drink");
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,"cafe","request_drink",null,"想点一杯热的",null,List.of(),start.plusSeconds(1))).isTrue();
        assertThat(w.serviceRequests).singleElement().satisfies(request->assertThat(request.status).isEqualTo("waiting"));
        assertThat(student.plan).isSameAs(resting);assertThat(student.plan.endsAt()).isEqualTo(start.plusSeconds(1200));
    }

    @Test void waitingDoesNotManufactureComplaintsReflectionsOrUnrequestedDrinks() {
        CompanionWorld w = CompanionRules.join("cafe-no-hidden-brain", "住客", "Asia/Shanghai", start);
        ResidentState owner=ResidentSimulation.state(w,"owner"),artist=ResidentSimulation.state(w,"artist");
        ResidentSimulation.replaceActor(w,"artist","cafe","observe","在咖啡馆里",start.plusSeconds(6000));
        w.serviceRequests.clear();CafeService.request(w,artist,start);
        for(int second=6;second<=300;second+=6)CafeService.tick(w,start.plusSeconds(second));
        assertThat(w.serviceRequests.getFirst().status).isEqualTo("waiting");
        assertThat(owner.complaintsSinceDutyReflection).isZero();
        // Nothing the rules can do adds a second request: a drink exists only because someone chose
        // request_drink. There is no counter of repeat visits and no unprompted pour behind this.
        assertThat(w.serviceRequests).hasSize(1);
        assertThat(w.events).noneMatch(event->"complaint".equals(event.type()));
        assertThat(w.memories).noneMatch(memory->memory.text().contains("下次想在她开口前"));
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

    // ---- the full happy path: a real day actually produces a served, drunk cup -----------------

    /** The task this test exists to prove: a normal business-hours stretch, with a resident who
     * actually chooses request_drink and an owner who actually chooses to tend, can carry one cup
     * all the way from "waiting" through "preparing" and "delivered" to "consumed" - the whole chain
     * the day's own metrics never observed because nobody chose the first step. This drives every
     * transition through the same public entry points a real decision loop uses (applyDecision for
     * both the requester and the owner, then real advance() ticks for the timed parts), never calling
     * CafeService's internal methods directly the way the narrower unit tests above do. */
    @Test void aChosenRequestActuallyRunsWaitingThroughPreparingDeliveredToConsumed(){
        CompanionWorld w = CompanionRules.join("cafe-full-cycle", "住客", "Asia/Shanghai", start);
        w.conversations.stream().filter(c -> "active".equals(c.status)).forEach(c -> ConversationLifecycle.finish(w, c, start, "测试准备"));
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentState student = ResidentSimulation.state(w, "student");
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", start.plusSeconds(60)); owner.plan = null;
        ResidentSimulation.replaceActor(w, "student", "cafe", "idle", "在窗边", start.plusSeconds(60)); student.plan = null;
        w.serviceRequests.clear();

        assertThat(ResidentSimulation.availableActions(w, "student", start)).contains("request_drink");
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision, "cafe", "request_drink", null, "想喝点热的", null, List.of(), start)).isTrue();
        ServiceRequest request = w.serviceRequests.get(w.serviceRequests.size() - 1);
        assertThat(request.status).isEqualTo("waiting");

        assertThat(ResidentSimulation.availableActions(w, "owner", start.plusSeconds(1))).contains("tend");
        assertThat(ResidentSimulation.applyDecision(w, "owner", owner.revision, w.intentRevision, "cafe", "tend", request.id, "去照应柜台", null, List.of(), start.plusSeconds(1))).isTrue();
        assertThat(request.status).isEqualTo("preparing");

        Instant at = start.plusSeconds(1);
        for (int i = 0; i < 6 && !"delivered".equals(request.status) && !"consumed".equals(request.status); i++) { at = at.plusSeconds(6); CompanionRules.advance(w, at); }
        assertThat(List.of("delivered", "consumed")).contains(request.status); // PREP_SECONDS has elapsed: the cup is made

        for (int i = 0; i < 6 && !"consumed".equals(request.status); i++) { at = at.plusSeconds(6); CompanionRules.advance(w, at); }
        assertThat(request.status).isEqualTo("consumed"); // requester never left, so it is actually drunk, not left to go cold
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "service".equals(m.topicId())))
            .anySatisfy(m -> assertThat(m.text()).contains("端来了一杯"));
    }

    // ---- a legitimate, bounded reason to want one in the first place ---------------------------

    /** request_drink was offered 32 times in a real run and never chosen once: nothing in a
     * resident's own perceptions ever gave them a reason to want a drink, so nothing ever competed
     * with whatever they were already doing. {@link CafeService#drinkWantCue} is a candidate
     * sentence for that gap, built only from a signal already sanctioned elsewhere in salientPerceptions
     * (energy translated to a qualitative "很累"), never a new hidden threshold. */
    @Test void drinkWantCueOnlyFiresForATiredResidentActuallyStandingAtAnOpenCounter(){
        CompanionWorld w = CompanionRules.join("cafe-want-cue", "住客", "Asia/Shanghai", start);
        ResidentState student = ResidentSimulation.state(w, "student");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        w.serviceRequests.clear();

        // Not tired: no cue, even standing right at the open counter.
        student.energy = 60;
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start)).isNull();

        // Tired, but not at the cafe: no cue - this is not a hidden gauge that follows them everywhere.
        student.energy = 15;
        assertThat(CafeService.drinkWantCue(w, student, "street", start)).isNull();

        // Tired and at the cafe while it is genuinely open and accepting orders: a real, bounded cue.
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start)).isNotNull().contains("很累");

        // The operator is never nudged to want their own drink through this path.
        owner.energy = 10;
        assertThat(CafeService.drinkWantCue(w, owner, "cafe", start)).isNull();

        // Already has an open request: nothing left to want.
        CafeService.request(w, student, start);
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start)).isNull();

        // Cafe not accepting orders: no cue regardless of tiredness.
        w.serviceRequests.clear();
        w.cafeOperating = false; CafeService.reconcileSchedule(w, start);
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start)).isNull();
    }

    /** The ordinary reason anyone orders anything in a cafe is not thirst - it is having sat there a
     * while with nothing in front of you. Tiredness is the rarer second case; this is the common one,
     * and it is the situation, not a body value, that produces it. */
    @Test void sittingInTheCafeAWhileWithNothingInFrontOfYouIsItsOwnReason(){
        CompanionWorld w = CompanionRules.join("cafe-settled-cue", "住客", "Asia/Shanghai", start);
        ResidentState student = ResidentSimulation.state(w, "student");
        w.serviceRequests.clear();
        student.energy = 70; // wide awake: the tired branch cannot be what fires here

        student.plan = new CompanionWorld.Plan("p-read","read","cafe",null,"看会儿书",start,start.plusSeconds(3600));
        // Just sat down - nothing to notice yet.
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start.plusSeconds(60))).isNull();
        // Twenty minutes in, looking up.
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start.plusSeconds(21*60)))
            .isNotNull().contains("手边还什么都没有");

        // Passing through rather than settled: no cue however long the clock has run.
        student.plan = new CompanionWorld.Plan("p-walk","travel","cafe",null,"路过",start,start.plusSeconds(3600));
        assertThat(CafeService.drinkWantCue(w, student, "cafe", start.plusSeconds(21*60))).isNull();
    }

    // ---- no-model fallback --------------------------------------------------------------------

    /** With no resident mind configured, elapsed time may finish existing physical work but cannot
     * invent thirst, service decisions, or a new social routine on the residents' behalf. */
    @Test void aRuleOnlyMultiDayRunDoesNotManufactureRequestsOrServiceDecisions() {
        CompanionWorld w = CompanionRules.join("cafe-e2e-lifecycle", "住客", "Asia/Shanghai", start);
        Instant at = start, end = start.plusSeconds(5L * 86400); // real advance() cadence
        while (at.isBefore(end)) {
            at = at.plusSeconds(60);
            CompanionRules.advance(w, at);
        }
        assertThat(w.serviceRequests).isEmpty();
        assertThat(w.events).noneMatch(event->Set.of("complaint","work_offer","work_agreement").contains(event.type()));
    }
}
