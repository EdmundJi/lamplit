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

    @Test void travellingKeepsTheRequestedActionDurationInsteadOfReplacingItWithFortyTwoSeconds(){
        CompanionWorld w=CompanionRules.join("travel-duration","住客","Asia/Shanghai",now);
        ResidentState artist=ResidentSimulation.state(w,"artist");w.conversations.forEach(c->c.status="ended");
        long revision=artist.revision;
        assertThat(ResidentSimulation.applyDecision(w,"artist",revision,w.intentRevision,"garden","observe",null,"去花园看一会儿",null,java.util.List.of(),now)).isTrue();
        assertThat(artist.plan.action()).isEqualTo("travel");
        CompanionRules.advance(w,now.plusSeconds(12));
        assertThat(artist.plan.action()).isEqualTo("observe");
        assertThat(Duration.between(artist.plan.startedAt(),artist.plan.endsAt()).getSeconds()).isEqualTo(60);
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
        w.updatedAt=now.plusSeconds(2);w.simulatedAt=now.plusSeconds(2);CompanionRules.advance(w,now.plusSeconds(44));
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
