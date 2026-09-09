package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ResidentLifeTest {
    private final Instant now=Instant.parse("2026-09-08T06:00:00Z");

    /** One CompanionRules.advance call simulates at most sixty seconds (ten six-second steps), so a
     * test that waits out a real walk has to keep calling it rather than naming a distant instant -
     * a single jump would land in the offline-reconciliation branch and skip the walk entirely. */
    private void advanceTo(CompanionWorld w,Instant target){
        while(w.updatedAt.isBefore(target))
            CompanionRules.advance(w,min(w.updatedAt.plusSeconds(54),target));
    }
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}

    @Test void travellingKeepsTheRequestedActionDurationInsteadOfReplacingItWithFortyTwoSeconds(){
        CompanionWorld w=CompanionRules.join("travel-duration","住客","Asia/Shanghai",now);
        ResidentState artist=ResidentSimulation.state(w,"artist");w.conversations.forEach(c->c.status="ended");
        long revision=artist.revision;
        assertThat(ResidentSimulation.applyDecision(w,"artist",revision,w.intentRevision,"garden","observe",null,"去花园看一会儿",null,java.util.List.of(),now)).isTrue();
        assertThat(artist.plan.action()).isEqualTo("travel");
        advanceTo(w,artist.plan.endsAt().plusSeconds(8));
        assertThat(artist.plan.action()).isEqualTo("observe");
        // Looking around is an activity, not a heartbeat: it used to fall through to the 60-second
        // default, so a resident who chose it was asked to decide again a simulated minute later and
        // simply chose it again. See the switch in applyDecision for what that cost.
        assertThat(Duration.between(artist.plan.startedAt(),artist.plan.endsAt()).getSeconds()).isEqualTo(900);
    }

    @Test void travelDurationIsRealDistanceNotAFixedTwelveSeconds(){
        // Item 2: minutes-scale. A walk has to outlast the simulation's own tick to exist - at three to
        // twenty seconds the whole town fitted inside one advance step and nobody was ever seen on the
        // street, which is why the first encounter implementation could not find anyone there.
        assertThat(ResidentSimulation.travelSeconds("cafe","cafe")).isEqualTo(60);
        assertThat(ResidentSimulation.travelSeconds("home-owner","cafe")).isBetween(60,180);
        assertThat(ResidentSimulation.travelSeconds("home-owner","home-gardener")).isEqualTo(520);
        assertThat(ResidentSimulation.travelSeconds("home-owner","cafe"))
            .isLessThan(ResidentSimulation.travelSeconds("home-owner","home-gardener"));
    }

    @Test void arrivingSomewhereSomeoneAlreadyIsCanOpenARuleDetectedEncounter(){
        // Item 3: the rules detect the face-to-face fact and open the conversation; the model still
        // writes every word through the same machinery a manual "invite" already uses.
        CompanionWorld w=CompanionRules.join("encounter-arrival","住客","Asia/Shanghai",now,true);
        w.conversations.forEach(c->c.status="ended");
        ResidentState owner=ResidentSimulation.state(w,"owner"),gardener=ResidentSimulation.state(w,"gardener");
        owner.plan=null;owner.suspendedAction=null;gardener.plan=null;gardener.suspendedAction=null;
        // The seeded world opens with a conversation, so both of these two count as having just
        // talked to someone; the rules deliberately leave a person alone for a while after that (see
        // ResidentSimulation.SOCIAL_RECOVERY_SECONDS). This test is about the encounter itself.
        owner.lastSocialAt=null;gardener.lastSocialAt=null;
        ResidentSimulation.replaceActor(w,"gardener","garden","observe","看看花园",now.plusSeconds(600));
        TownPlaces.release(w,"gardener");TownPlaces.claim(w,"gardener","garden",null,now);
        ResidentSimulation.replaceActor(w,"owner","home-owner","idle","在家",now.plusSeconds(600));
        TownPlaces.release(w,"owner");
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"garden","observe",null,"去看看花园","",List.of(),now)).isTrue();
        assertThat(owner.plan.action()).isEqualTo("travel");
        Instant arrival=owner.plan.endsAt();
        w.updatedAt=now;w.simulatedAt=now;
        // advance() steps in fixed 6-second increments, so the target instant must clear the next
        // 6-second boundary past the travel's own end, not merely be one second after it.
        advanceTo(w,arrival.plusSeconds(8));
        assertThat(w.conversations).anyMatch(c->"active".equals(c.status)&&c.participantIds.contains("owner")&&c.participantIds.contains("gardener"));
        assertThat(w.encounterCooldowns).isNotEmpty();
    }

    @Test void twoPeopleWalkingTheSameStreetCanCrossPathsAndStop(){
        // The other half of item 3, and the half the first implementation left out: a walker's place
        // IS "street" for the length of the walk, so passing someone needs no separate rule - only a
        // walk long enough to be seen. Both journeys are picked back up afterwards.
        CompanionWorld w=CompanionRules.join("encounter-street","住客","Asia/Shanghai",now,true);
        w.conversations.forEach(c->c.status="ended");
        ResidentState owner=ResidentSimulation.state(w,"owner"),gardener=ResidentSimulation.state(w,"gardener");
        for(ResidentState r:List.of(owner,gardener)){r.plan=null;r.suspendedAction=null;r.lastSocialAt=null;}
        ResidentSimulation.replaceActor(w,"owner","home-owner","idle","在家",now.plusSeconds(600));
        ResidentSimulation.replaceActor(w,"gardener",TownPlaces.homeOf("gardener"),"idle","在家",now.plusSeconds(600));
        TownPlaces.release(w,"owner");TownPlaces.release(w,"gardener");
        // They set off in opposite directions along the same street at the same moment.
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"garden","observe",null,"去花园走走","",List.of(),now)).isTrue();
        assertThat(ResidentSimulation.applyDecision(w,"gardener",gardener.revision,w.intentRevision,"cafe","rest",null,"去咖啡馆坐坐","",List.of(),now)).isTrue();
        assertThat(ResidentSimulation.actor(w,"owner").activity()).isEqualTo("walk");
        assertThat(ResidentSimulation.actor(w,"gardener").activity()).isEqualTo("walk");
        w.updatedAt=now;w.simulatedAt=now;
        advanceTo(w,now.plusSeconds(30));
        assertThat(w.conversations).as("two people on the same street at the same time have crossed paths")
            .anyMatch(c->"active".equals(c.status)&&c.participantIds.contains("owner")&&c.participantIds.contains("gardener"));
        // Neither of them has forgotten where they were going.
        assertThat(owner.suspendedAction).isNotNull();
        assertThat(owner.suspendedAction.plan.action()).isEqualTo("travel");
        assertThat(gardener.suspendedAction).isNotNull();
    }

    @Test void someoneWhoJustTalkedIsLeftAloneForAWhile(){
        // The rules put people in front of each other; they must not hand one sociable resident round
        // the whole town in a single afternoon. See SOCIAL_RECOVERY_SECONDS.
        CompanionWorld w=CompanionRules.join("encounter-recovery","住客","Asia/Shanghai",now,true);
        w.conversations.forEach(c->c.status="ended");
        ResidentState owner=ResidentSimulation.state(w,"owner"),gardener=ResidentSimulation.state(w,"gardener");
        for(ResidentState r:List.of(owner,gardener)){r.plan=null;r.suspendedAction=null;}
        owner.lastSocialAt=now;gardener.lastSocialAt=null;
        ResidentSimulation.replaceActor(w,"owner","garden","observe","看看花园",now.plusSeconds(600));
        ResidentSimulation.replaceActor(w,"gardener","garden","observe","看看花园",now.plusSeconds(600));
        w.updatedAt=now;w.simulatedAt=now;
        advanceTo(w,now.plusSeconds(60));
        assertThat(w.conversations).noneMatch(c->"active".equals(c.status));
        // Once the quiet stretch is over, the same two people standing in the same garden do meet.
        owner.lastSocialAt=now.minusSeconds(3600);
        advanceTo(w,now.plusSeconds(120));
        assertThat(w.conversations).anyMatch(c->"active".equals(c.status)&&c.participantIds.contains("owner")&&c.participantIds.contains("gardener"));
    }

    @Test void joinLetsAResidentSitDownWithSomeoneAlreadyThere(){
        // Item 3's "join" action: a physical positioning choice, not itself a conversation.
        CompanionWorld w=CompanionRules.join("join-action","住客","Asia/Shanghai",now,true);
        w.conversations.forEach(c->c.status="ended");
        ResidentState owner=ResidentSimulation.state(w,"owner"),student=ResidentSimulation.state(w,"student");
        owner.plan=null;owner.suspendedAction=null;student.plan=null;student.suspendedAction=null;
        ResidentSimulation.replaceActor(w,"student","cafe","study","复习",now.plusSeconds(1200));
        TownPlaces.release(w,"student");TownPlaces.claim(w,"student","cafe","seat",now);
        ResidentSimulation.replaceActor(w,"owner","cafe","observe","看看店里",now.plusSeconds(200));
        TownPlaces.release(w,"owner");
        assertThat(ResidentSimulation.availableActions(w,"owner",now)).contains("join");
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"cafe","join","student","过去和小川坐一起","",List.of(),now)).isTrue();
        assertThat(owner.plan.action()).isEqualTo("join");
        assertThat(owner.positionId).isNotNull();
        assertThat(TownPlaces.position(w,owner.positionId).place).isEqualTo("cafe");
    }

    @Test void awayGenuinelyLeavesTheMapAndReturnsWithAPrivateMemory(){
        // Item 8: an absence with a duration and a private memory, nothing more - no off-map economy.
        CompanionWorld w=CompanionRules.join("away-action","住客","Asia/Shanghai",now,true);
        w.conversations.forEach(c->c.status="ended");
        ResidentState gardener=ResidentSimulation.state(w,"gardener");
        gardener.plan=null;gardener.suspendedAction=null;
        ResidentSimulation.replaceActor(w,"gardener","garden","observe","看看花园",now.plusSeconds(200));
        TownPlaces.release(w,"gardener");
        assertThat(ResidentSimulation.availableActions(w,"gardener",now)).contains("away");
        assertThat(ResidentSimulation.applyDecision(w,"gardener",gardener.revision,w.intentRevision,"street","away",null,"去邻镇买点种子","",List.of(),now)).isTrue();
        Plan away=gardener.plan;
        assertThat(away.action()).isEqualTo("away");
        assertThat(ResidentSimulation.actor(w,"gardener").place()).isEqualTo("away");
        assertThat(gardener.positionId).isNull();
        // Shrink the (600-2700s) remaining duration so the test can observe the return quickly.
        gardener.plan=new Plan(away.id(),away.action(),away.place(),away.targetId(),away.reason(),now,now.plusSeconds(6));
        w.updatedAt=now;w.simulatedAt=now;
        CompanionRules.advance(w,now.plusSeconds(12));
        assertThat(ResidentSimulation.actor(w,"gardener").place()).isEqualTo(TownPlaces.homeOf("gardener"));
        assertThat(w.memories).anyMatch(m->m.ownerId().equals("gardener")&&m.text().contains("出门处理了自己的事")&&m.text().contains("邻镇买点种子"));
        // Nobody else witnessed where they went - this is a private memory only they have.
        assertThat(w.memories).noneMatch(m->!m.ownerId().equals("gardener")&&m.text().contains("邻镇买点种子"));
    }

    @Test void dayPlanIsCoarseAndAMissedSegmentLeavesAReflectionRatherThanASilentSuccess(){
        // Item 4: three or four qualitative segments, never a time-slotted schedule; abandonable.
        CompanionWorld w=CompanionRules.join("day-plan","住客","Asia/Shanghai",now,true);
        ResidentState owner=ResidentSimulation.state(w,"owner");
        List<String> evidence=w.memories.stream().filter(m->m.ownerId().equals("owner")).map(Memory::id).limit(1).toList();
        assertThat(ResidentSimulation.applyDayPlan(w,"owner",owner.revision,List.of("上午整理吧台","下午画一版新海报","傍晚陪读书会的人聊聊"),evidence,now)).isTrue();
        assertThat(owner.dayPlan).isNotNull();
        assertThat(owner.dayPlan.segments).hasSize(3);
        assertThat(owner.dayPlan.segments).allMatch(s->"pending".equals(s.status));
        // The day rolls over without anything ever being marked done - reality wrecked the plan, and
        // the resident should be able to notice that, not have it silently counted as a success.
        Instant nextDay=now.plusSeconds(86400);
        ResidentSimulation.step(w,nextDay);
        assertThat(owner.dayPlan).isNull();
        assertThat(w.memories).anyMatch(m->m.ownerId().equals("owner")&&"reflection".equals(m.sourceType())&&m.text().contains("没顾上"));
    }

    @Test void counterAuthorityMovesOnlyAfterTheRelevantSecondPersonAccepts(){
        CompanionWorld w=CompanionRules.join("work-agreement","住客","Asia/Shanghai",now);
        Position counter=TownPlaces.position(w,"cafe-counter");
        assertThat(ResidentSimulation.proposeWorkArrangement(w,"artist","assist","owner","高峰时我可以照看吧台",now)).isTrue();
        WorkArrangement assist=w.workArrangements.getFirst();
        assertThat(CafeService.mayTend(w,"artist")).isFalse();
        assertThat(ResidentSimulation.acceptWorkArrangement(w,"owner",assist.id,now.plusSeconds(1))).isTrue();
        assertThat(CafeService.mayTend(w,"artist")).isTrue();
        assertThat(counter.ownerId).isEqualTo("owner");

        assertThat(ResidentSimulation.proposeWorkArrangement(w,"owner","takeover","gardener","我想把经营交出去",now.plusSeconds(2))).isTrue();
        WorkArrangement takeover=w.workArrangements.getLast();
        assertThat(ResidentSimulation.acceptWorkArrangement(w,"gardener",takeover.id,now.plusSeconds(3))).isTrue();
        assertThat(w.cafeOperatorId).isEqualTo("gardener");
        assertThat(counter.ownerId).isEqualTo("gardener");
        assertThat(ResidentSimulation.actor(w,"gardener").role()).isEqualTo("咖啡馆经营者");
    }

    @Test void servingPausesAndThenReturnsToTheSameConcreteAction(){
        CompanionWorld w=CompanionRules.join("return-after-service","住客","Asia/Shanghai",now);
        w.conversations.forEach(c->c.status="ended");
        ResidentState owner=ResidentSimulation.state(w,"owner"),student=ResidentSimulation.state(w,"student");
        owner.suspendedAction=null;student.suspendedAction=null;
        // This is a state-machine test, not a social-choice test: keep a new chat from becoming a
        // second interruption before the assertion observes the returned action.
        owner.lastSocialAt=now;student.lastSocialAt=now;
        ResidentSimulation.replaceActor(w,"owner","cafe","create","改招贴",now.plusSeconds(300));
        owner.plan=new Plan("poster","create","cafe","reading-night","想改完招贴",now,now.plusSeconds(300));
        ResidentSimulation.replaceActor(w,"student","cafe","study","等一杯热水",now.plusSeconds(300));
        CafeService.request(w,student,now);owner.dutyPressure=100;
        assertThat(owner.plan.action()).isEqualTo("create");
        String request=w.serviceRequests.getLast().id;
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"cafe","tend",request,"先把这杯做好",null,java.util.List.of(),now.plusSeconds(1))).isTrue();
        assertThat(owner.plan.action()).isEqualTo("tend");
        assertThat(owner.suspendedAction.plan.action()).isEqualTo("create");
        CompanionRules.advance(w,now.plusSeconds(24));
        assertThat(owner.plan.action()).isEqualTo("create");
        assertThat(owner.suspendedAction).isNull();
        assertThat(owner.plan.reason()).isEqualTo("想改完招贴");
    }

    @Test void anOperatorCanLeaveWorkWithoutCreatingANewOwner(){
        CompanionWorld w=CompanionRules.join("leave-work","住客","Asia/Shanghai",now);
        assertThat(ResidentSimulation.changeOccupation(w,"owner","接些插画和翻译的零活",now)).isTrue();
        ResidentState owner=ResidentSimulation.state(w,"owner");
        assertThat(w.cafeOperating).isFalse();
        assertThat(owner.occupation).contains("插画");
        assertThat(owner.careerIntent.purpose).contains("插画");
        assertThat(CafeService.mayTend(w,"owner")).isFalse();
        assertThat(ResidentSimulation.actor(w,"owner").role()).contains("转向");
        w.conversations.forEach(c->c.status="ended");CompanionRules.advance(w,now.plusSeconds(6));
        assertThat(owner.plan).isNull();
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"home","make",null,"把插画零活先做一点",null,java.util.List.of(),now.plusSeconds(7))).isTrue();
        assertThat(owner.plan.action()).isEqualTo("travel");
        assertThat(owner.desiredAction).isEqualTo("make");
    }

    @Test void aRetainedOperatorCanChooseToReturnWithoutErasingTheDirectionTheyTried(){
        CompanionWorld w=CompanionRules.join("return-cafe","住客","Asia/Shanghai",now,true);
        assertThat(ResidentSimulation.changeOccupation(w,"owner","接些插画和翻译的零活",now)).isTrue();
        assertThat(w.cafeOperating).isFalse();assertThat(w.cafeOperatorId).isEqualTo("owner");assertThat(w.cafeStatus).isEqualTo("closing");
        moveEveryoneHome(w,now.plusSeconds(1));CafeService.finishClosingIfEmpty(w,now.plusSeconds(1));
        ResidentState owner=ResidentSimulation.state(w,"owner");
        assertThat(w.cafeStatus).isEqualTo("closed");
        assertThat(ResidentSimulation.cafeScheduleCue(w,"owner",now.plusSeconds(2))).contains("经营暂停");
        assertThat(ResidentSimulation.availableActions(w,"owner",now.plusSeconds(2))).contains("open_cafe");
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"cafe","open_cafe",null,"今天想回去把门打开",null,List.of(),now.plusSeconds(2))).isTrue();
        w.updatedAt=now.plusSeconds(2);w.simulatedAt=now.plusSeconds(2);
        // Walking to the cafe is a walk now, not the old three-to-twenty-second hop, so the clock has
        // to cover the trip plus the thirty seconds of actually opening up.
        advanceTo(w,owner.plan.endsAt().plusSeconds(44));
        assertThat(w.cafeOperating).isTrue();assertThat(w.cafeStatus).isEqualTo("open");
        assertThat(ResidentSimulation.actor(w,"owner").role()).isEqualTo("咖啡馆经营者");
        assertThat(owner.occupation).contains("恢复咖啡馆经营").contains("插画和翻译");
        assertThat(owner.careerIntent.purpose).contains("插画和翻译");
    }

    @Test void aFormerOwnerCannotReopenAfterARealTakeoverButTheCurrentOperatorCan(){
        CompanionWorld w=CompanionRules.join("return-after-takeover","住客","Asia/Shanghai",now,true);
        assertThat(ResidentSimulation.proposeWorkArrangement(w,"owner","takeover","gardener","我想把经营交出去",now)).isTrue();
        assertThat(ResidentSimulation.acceptWorkArrangement(w,"gardener",w.workArrangements.getLast().id,now.plusSeconds(1))).isTrue();
        assertThat(ResidentSimulation.changeOccupation(w,"gardener","先回去照看自己的苗",now.plusSeconds(2))).isTrue();
        moveEveryoneHome(w,now.plusSeconds(3));CafeService.finishClosingIfEmpty(w,now.plusSeconds(3));
        assertThat(w.cafeOperatorId).isEqualTo("gardener");assertThat(w.cafeStatus).isEqualTo("closed");
        assertThat(ResidentSimulation.availableActions(w,"owner",now.plusSeconds(4))).doesNotContain("open_cafe");
        assertThat(ResidentSimulation.availableActions(w,"gardener",now.plusSeconds(4))).contains("open_cafe");
    }

    @Test void stoppingWorkClosesRequestsAndConversationWithoutInventingAnAnnouncement(){
        CompanionWorld w=CompanionRules.join("pause-cafe-cleanly","住客","Asia/Shanghai",now,true);
        ResidentState student=ResidentSimulation.state(w,"student");w.serviceRequests.clear();CafeService.request(w,student,now);
        assertThat(w.conversations).anyMatch(conversation->"active".equals(conversation.status)&&"cafe".equals(conversation.place));
        assertThat(ResidentSimulation.changeOccupation(w,"owner","先停下来做翻译",now.plusSeconds(1))).isTrue();
        assertThat(w.cafeOperating).isFalse();assertThat(w.cafeStatus).isEqualTo("closing");
        assertThat(w.serviceRequests).allMatch(request->"abandoned".equals(request.status));
        assertThat(w.conversations).noneMatch(conversation->"active".equals(conversation.status)&&"cafe".equals(conversation.place));
        assertThat(w.memories).noneMatch(memory->"cafe-hours".equals(memory.topicId())&&"heard".equals(memory.sourceType()));
    }

    @Test void anOldPausedSaveWithoutCafeStatusRepairsToAReopenableClosedCafe()throws Exception{
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();CompanionWorld original=CompanionRules.join("old-paused-cafe","住客","Asia/Shanghai",now,true);
        original.cafeOperating=false;ObjectNode tree=(ObjectNode)json.valueToTree(original);tree.remove(List.of("cafeStatus","cafeStatusChangedAt"));
        CompanionWorld legacy=json.treeToValue(tree,CompanionWorld.class);CompanionRules.advance(legacy,now.plusSeconds(6));
        assertThat(legacy.cafeStatus).isEqualTo("closed");assertThat(legacy.cafeOperatorId).isEqualTo("owner");
        assertThat(ResidentSimulation.availableActions(legacy,"owner",now.plusSeconds(6))).contains("open_cafe");
    }

    private static void moveEveryoneHome(CompanionWorld w,Instant at){
        for(ResidentState state:w.residentStates){if("self".equals(state.id))continue;Actor actor=ResidentSimulation.actor(w,state.id);ResidentSimulation.replaceActor(w,state.id,TownPlaces.homeOf(state.id),"idle","在家",at.plusSeconds(60));state.plan=null;TownPlaces.release(w,state.id);}
        Actor avatar=w.avatar;w.avatar=new Actor(avatar.id(),avatar.name(),avatar.role(),TownPlaces.homeOf("self"),avatar.activity(),avatar.label(),avatar.x(),avatar.y(),avatar.until());TownPlaces.release(w,"self");
    }
}
