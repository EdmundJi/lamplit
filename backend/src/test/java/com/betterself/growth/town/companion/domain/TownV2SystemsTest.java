package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TownV2SystemsTest {
    private static final Instant NOW=Instant.parse("2026-09-14T06:00:00Z");

    private CompanionWorld town(){
        CompanionWorld w=CompanionRules.join("v2-systems","我","Asia/Shanghai",NOW,true);
        w.conversations.stream().filter(c->"active".equals(c.status)).forEach(c->ConversationLifecycle.finish(w,c,NOW,"测试准备"));
        return w;
    }
    private void freeAt(CompanionWorld w,String id,String place){
        ResidentState r=ResidentSimulation.state(w,id);r.plan=null;TownPlaces.release(w,id);
        ResidentSimulation.replaceActor(w,id,place,"idle","在这里",NOW.plusSeconds(3600));
    }
    private void step(CompanionWorld w,int seconds){for(int n=6;n<=seconds;n+=6)ResidentSimulation.step(w,NOW.plusSeconds(n));}

    @Test void everyPositionAndObjectHasAResolvableFourLevelAddress(){
        CompanionWorld w=town();
        assertThat(w.locations).hasSize(23);
        assertThat(w.rooms).hasSizeGreaterThan(25);
        assertThat(w.residentStates).allMatch(r->r.roomId!=null&&TownPlaces.room(w,r.roomId)!=null);
        for(Position p:w.positions){
            assertThat(p.roomId).as(p.id).isNotBlank();
            assertThat(TownPlaces.room(w,p.roomId)).as(p.id).isNotNull();
            assertThat(TownPlaces.room(w,p.roomId).buildingId()).isEqualTo(p.place);
        }
        for(WorldObject o:w.objects){
            assertThat(o.roomId()).as(o.id()).isNotBlank();
            assertThat(TownPlaces.room(w,o.roomId())).as(o.id()).isNotNull();
            assertThat(TownPlaces.room(w,o.roomId()).buildingId()).isEqualTo(o.place());
        }
    }

    @Test void allSixPublicPlacesExposeAnExecutableChoiceWithAnExactRoom(){
        CompanionWorld w=town();
        assertThat(ResidentSimulation.availableDecisionOptions(w,"owner",NOW)).anyMatch(o->"cafe".equals(o.place())&&"cafe-main".equals(o.roomId()));
        assertThat(ResidentSimulation.availableDecisionOptions(w,"gardener",NOW)).anyMatch(o->"garden".equals(o.place())&&"tend_plants".equals(o.action())&&o.targetIds().contains("garden-plot"));
        assertThat(ResidentSimulation.availableDecisionOptions(w,"student",NOW)).anyMatch(o->"academy".equals(o.place())&&"academy-reading-room".equals(o.roomId())&&"study".equals(o.action()));
        assertThat(ResidentSimulation.availableDecisionOptions(w,"trainer",NOW)).anyMatch(o->"gym".equals(o.place())&&"exercise".equals(o.action())&&o.targetIds().contains("gym-equipment"));
        assertThat(ResidentSimulation.availableDecisionOptions(w,"messenger",NOW)).anyMatch(o->"board".equals(o.place())&&"read_notice".equals(o.action())&&o.targetIds().contains("board-noticeboard"));
        assertThat(ResidentSimulation.availableDecisionOptions(w,"fixer",NOW)).anyMatch(o->"shop".equals(o.place())&&"shop-workroom".equals(o.roomId())&&"work".equals(o.action()));
    }

    @Test void tendingPlantsRunsForTimeAndChangesTheVisibleFlowerbed(){
        CompanionWorld w=town();freeAt(w,"gardener","garden");
        ResidentState gardener=ResidentSimulation.state(w,"gardener");
        String before=w.objects.stream().filter(o->"flowerbed".equals(o.id())).findFirst().orElseThrow().state();
        assertThat(ResidentSimulation.applyDecision(w,"gardener",gardener.revision,w.intentRevision,
            "garden","garden-main","tend_plants","garden-plot","照看一下花苗",null,List.of(),NOW)).isTrue();
        assertThat(gardener.positionId).isEqualTo("garden-plot");
        step(w,1200);
        WorldObject flowerbed=w.objects.stream().filter(o->"flowerbed".equals(o.id())).findFirst().orElseThrow();
        assertThat(flowerbed.state()).isNotEqualTo(before);
        assertThat(flowerbed.projectId()).as("the living flowerbed is not deleted when a seeded project completes").isNull();
        assertThat(w.events).anyMatch(e->"plants_tended".equals(e.type())&&"garden-plot".equals(e.positionId()));
        ResidentSimulation.step(w,NOW.plusSeconds(1200+6*3600));
        assertThat(w.objects.stream().filter(o->"flowerbed".equals(o.id())).findFirst().orElseThrow().state()).isEqualTo("new-leaves");
    }

    @Test void readingAnEmptyBoardDoesNotBroadcastPrivateProjects(){
        CompanionWorld w=town();freeAt(w,"messenger","board");ResidentState messenger=ResidentSimulation.state(w,"messenger");
        messenger.knownProjects.clear();
        assertThat(ResidentSimulation.applyDecision(w,"messenger",messenger.revision,w.intentRevision,
            "board","board-square","read_notice","board-noticeboard","看看今天贴了什么",null,List.of(),NOW)).isTrue();
        step(w,600);
        assertThat(messenger.knownProjects).isEmpty();
        assertThat(w.memories).anyMatch(m->"messenger".equals(m.ownerId())&&m.text().contains("没有新内容"));
    }

    @Test void aSharedHomeHasOneBathroomAndEveryFlatmateKeepsTheirOwnBedChoice(){
        CompanionWorld w=town();String home=TownPlaces.homeOf("yogi");
        Room common=TownPlaces.room(w,home+"-common");
        assertThat(common.residentIds()).containsExactlyInAnyOrder("botanist","trainer","boxer","yogi");
        assertThat(TownPlaces.position(w,home+"-bathroom").capacity).isEqualTo(1);
        assertThat(ResidentSimulation.availableDecisionOptions(w,"yogi",NOW))
            .anyMatch(o->"sleep".equals(o.action())&&o.targetIds().contains("home-yogi-bed")&&!o.targetIds().contains("home-botanist-bed"));
    }

    @Test void anUnknownBuildingExposesItsEntranceButNotItsInteriorUntilVisited(){
        CompanionWorld w=town();ResidentState gardener=ResidentSimulation.state(w,"gardener");
        List<ResidentSimulation.DecisionOption> before=ResidentSimulation.availableDecisionOptions(w,"gardener",NOW);
        assertThat(before).anyMatch(o->"gym".equals(o.place())&&"observe".equals(o.action())&&"gym-training-room".equals(o.roomId()));
        assertThat(before).noneMatch(o->"exercise".equals(o.action()));
        freeAt(w,"gardener","garden");
        assertThat(ResidentSimulation.applyDecision(w,"gardener",gardener.revision,w.intentRevision,
            "gym","gym-training-room","observe",null,"进去看一看",null,List.of(),NOW)).isTrue();
        step(w,120);
        assertThat(gardener.knownPositionIds).contains("gym-equipment");
        assertThat(ResidentSimulation.availableDecisionOptions(w,"gardener",NOW.plusSeconds(120)))
            .anyMatch(o->"exercise".equals(o.action())&&o.targetIds().contains("gym-equipment"));
    }

    @Test void aPositionTargetMustBelongToTheChosenAction(){
        CompanionWorld w=town();freeAt(w,"student",TownPlaces.homeOf("student"));ResidentState student=ResidentSimulation.state(w,"student");
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,
            "home",TownPlaces.homeOf("student")+"-room-student","study","home-student-bed","拿床当书桌",null,List.of(),NOW)).isFalse();
    }

    @Test void wallsBlockConversationWitnessingAndHandingObjectsBetweenBedrooms(){
        CompanionWorld w=town();String home=TownPlaces.homeOf("artist");
        freeAt(w,"artist",home);freeAt(w,"weaver",home);
        ResidentSimulation.state(w,"artist").roomId=home+"-room-artist";
        ResidentSimulation.state(w,"weaver").roomId=home+"-room-weaver";
        assertThat(ResidentSimulation.sameRoom(w,"artist","weaver")).isFalse();
        assertThat(ResidentSimulation.availableActions(w,"artist",NOW)).doesNotContain("join","invite_home");
        assertThat(Lending.lend(w,"weaver","artist","shop-thread-box",NOW)).isFalse();
        ResidentSimulation.state(w,"artist").roomId=home+"-common";
        ResidentSimulation.state(w,"weaver").roomId=home+"-common";
        assertThat(ResidentSimulation.sameRoom(w,"artist","weaver")).isTrue();
        assertThat(Lending.lend(w,"weaver","artist","shop-thread-box",NOW)).isTrue();
    }

    @Test void anInvitationMayEnterTheHostsCommonRoomButNotTheirBedroom(){
        CompanionWorld w=town();freeAt(w,"owner","cafe");freeAt(w,"student","cafe");
        ResidentSimulation.state(w,"owner").roomId="cafe-main";ResidentSimulation.state(w,"student").roomId="cafe-main";
        DoorService.invite(w,"owner","student",NOW);
        ResidentState student=ResidentSimulation.state(w,"student");String home=TownPlaces.homeOf("owner");
        assertThat(TownPlaces.room(w,home+"-common")).isNotNull();
        assertThat(TownPlaces.room(w,home+"-common").kind()).isEqualTo("common");
        assertThat(DoorService.isInvited(w,"student",home,NOW)).isTrue();
        assertThat(ResidentSimulation.activeConversation(w,"student")).isNull();
        assertThat(ResidentSimulation.availableActions(w,"student",NOW)).contains("visit_home");
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,
            "home",home+"-room-owner","visit_home","owner","不能直接进卧室",null,List.of(),NOW)).isFalse();
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,
            "home",home+"-common","visit_home","owner","去客厅坐坐",null,List.of(),NOW)).isTrue();
    }

    @Test void exactScarceQueueIsFifoAndLeavingTheQueueReallyRemovesYou(){
        CompanionWorld w=town();String bench="shop-workbench";
        freeAt(w,"fixer","shop");freeAt(w,"weaver","shop");freeAt(w,"student","shop");
        assertThat(TownPlaces.claimExact(w,"fixer",bench,NOW)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.claimExact(w,"weaver",bench,NOW)).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(TownPlaces.claimExact(w,"student",bench,NOW)).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(TownPlaces.position(w,bench).waitingIds).containsExactly("weaver","student");
        TownPlaces.release(w,"fixer");
        assertThat(TownPlaces.claimExact(w,"student",bench,NOW)).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(TownPlaces.claimExact(w,"weaver",bench,NOW)).isEqualTo(TownPlaces.Outcome.SEATED);
        TownPlaces.release(w,"student");
        assertThat(TownPlaces.position(w,bench).waitingIds).isEmpty();
    }

    @Test void changingExactTargetRemovesTheOldQueueEntry(){
        CompanionWorld w=town();
        assertThat(TownPlaces.claimExact(w,"fixer","shop-workbench",NOW)).isEqualTo(TownPlaces.Outcome.SEATED);
        assertThat(TownPlaces.claimExact(w,"weaver","shop-workbench",NOW)).isEqualTo(TownPlaces.Outcome.WAITING);
        assertThat(TownPlaces.position(w,"shop-workbench").waitingIds).containsExactly("weaver");
        TownPlaces.claimExact(w,"weaver","gym-equipment",NOW.plusSeconds(1));
        assertThat(TownPlaces.position(w,"shop-workbench").waitingIds).doesNotContain("weaver");
    }

    @Test void borrowedObjectFollowsItsHolderAndReturnsToItsOwnerWithRoomAddress(){
        CompanionWorld w=town();freeAt(w,"fixer","shop");freeAt(w,"weaver","shop");
        assertThat(Lending.lend(w,"fixer","weaver","shop-toolkit",NOW)).isTrue();
        ResidentSimulation.replaceActor(w,"weaver",TownPlaces.homeOf("weaver"),"walk","回家",NOW.plusSeconds(60));
        WorldObject carried=w.objects.stream().filter(o->"shop-toolkit".equals(o.id())).findFirst().orElseThrow();
        assertThat(carried.ownerId()).isEqualTo("fixer");
        assertThat(carried.holderId()).isEqualTo("weaver");
        assertThat(carried.place()).isEqualTo(TownPlaces.homeOf("weaver"));
        assertThat(carried.roomId()).isEqualTo(TownPlaces.homeOf("weaver")+"-room-weaver");
        ResidentSimulation.replaceActor(w,"fixer",TownPlaces.homeOf("weaver"),"idle","来取东西",NOW.plusSeconds(60));
        ResidentSimulation.state(w,"fixer").roomId=TownPlaces.homeOf("weaver")+"-common";
        ResidentSimulation.state(w,"weaver").roomId=TownPlaces.homeOf("weaver")+"-common";
        assertThat(Lending.returnItem(w,"weaver","shop-toolkit",NOW.plusSeconds(1))).isTrue();
        WorldObject returned=w.objects.stream().filter(o->"shop-toolkit".equals(o.id())).findFirst().orElseThrow();
        assertThat(returned.ownerId()).isEqualTo("fixer");assertThat(returned.holderId()).isEqualTo("fixer");
    }

    @Test void scarceEquipmentCanBreakAndAResidentMayChooseToRepairIt(){
        CompanionWorld w=town();Position bench=TownPlaces.position(w,"shop-workbench");
        bench.usesSinceRepair=bench.maintenanceEveryUses-1;
        assertThat(TownPlaces.finishUse(w,bench.id,NOW)).isTrue();
        assertThat(bench.condition).isEqualTo("broken");
        freeAt(w,"fixer","shop");ResidentState fixer=ResidentSimulation.state(w,"fixer");
        assertThat(ResidentSimulation.availableDecisionOptions(w,"fixer",NOW))
            .anyMatch(o->"repair".equals(o.action())&&o.targetIds().contains("shop-workbench"));
        assertThat(ResidentSimulation.applyDecision(w,"fixer",fixer.revision,w.intentRevision,
            "shop","shop-workroom","repair","shop-workbench","把工作台修好",null,List.of(),NOW)).isTrue();
        step(w,1200);
        assertThat(bench.condition).isEqualTo("usable");assertThat(bench.usesSinceRepair).isZero();
        assertThat(w.events).anyMatch(e->"resource_repaired".equals(e.type())&&"shop-workbench".equals(e.positionId()));
    }

    @Test void oldSaveExpandsOnceWithoutResettingItsLife(){
        CompanionWorld w=town();
        w.simulationVersion=4;
        w.residents.removeIf(a->!List.of("owner","student","artist","gardener").contains(a.id()));
        w.residentStates.removeIf(r->!List.of("self","owner","student","artist","gardener").contains(r.id));
        w.locations.removeIf(l->!SetHolder.OLD_PLACES.contains(l.id()));
        w.positions.removeIf(p->!SetHolder.OLD_PLACES.contains(p.place));w.rooms.clear();
        w.objects.removeIf(o->!List.of("worktable","noticeboard","flowerbed").contains(o.id()));
        ResidentSimulation.state(w,"owner").relationships.put("student",7);
        w.objects.add(new WorldObject("keepsake","paper","home-owner","旧存档里的纸条","folded",null,"owner"));
        ResidentSeed.reconcileLife(w,NOW.plusSeconds(1));
        assertThat(w.residents).hasSize(25);assertThat(w.simulationVersion).isEqualTo(5);
        assertThat(ResidentSimulation.state(w,"owner").relationships.get("student")).isEqualTo(7);
        assertThat(w.objects).anyMatch(o->"keepsake".equals(o.id()));
        int residents=w.residents.size(),rooms=w.rooms.size(),objects=w.objects.size();
        ResidentSeed.reconcileLife(w,NOW.plusSeconds(2));
        assertThat(w.residents).hasSize(residents);assertThat(w.rooms).hasSize(rooms);assertThat(w.objects).hasSize(objects);
        assertThat(w.residents.stream().map(Actor::id)).doesNotHaveDuplicates();
    }

    private static final class SetHolder {
        static final java.util.Set<String> OLD_PLACES=new HashSet<>(List.of("street","cafe","garden","home-owner","home-student","home-artist","home-gardener","home-self"));
    }
}
