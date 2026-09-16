package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyRhythmTest {
    /** One CompanionRules.advance call simulates at most sixty seconds (ten six-second steps), so a
     * test that waits out a real walk has to keep calling it rather than naming a distant instant.
     * Walking across the town takes minutes now, not the three-to-twenty seconds it used to. */
    private void advanceTo(CompanionWorld w,Instant target){
        while(w.updatedAt.isBefore(target))
            CompanionRules.advance(w,w.updatedAt.plusSeconds(54).isBefore(target)?w.updatedAt.plusSeconds(54):target);
    }
    private static final Instant DAY = Instant.parse("2026-09-09T04:00:00Z"); // 12:00 Shanghai

    @Test void nighttimeArrivalStartsWithAClosedCafeAndNoManufacturedConversation(){
        Instant night=Instant.parse("2026-09-08T16:30:00Z"); // 00:30 Shanghai
        CompanionWorld w=CompanionRules.join("night-arrival","我","Asia/Shanghai",night,true);
        assertThat(w.cafeStatus).isEqualTo("closed");
        assertThat(w.conversations).noneMatch(c->"active".equals(c.status));
        assertThat(w.residents).noneMatch(a->"cafe".equals(a.place()));
        for(String id:TownPlaces.RESIDENT_IDS)assertThat(TownPlaces.position(w,TownPlaces.homeOf(id)+"-desk")).isNotNull();
    }

    @Test void anOldJsonSaveGetsDefaultHoursStatusEnergyClockAndHomeDesks()throws Exception{
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        CompanionWorld original=CompanionRules.join("rhythm-legacy","我","Asia/Shanghai",DAY,true);
        ObjectNode tree=(ObjectNode)json.valueToTree(original);
        tree.remove(List.of("cafeOpenMinute","cafeCloseMinute","cafeStatus","cafeStatusChangedAt"));
        tree.withArray("residentStates").forEach(node->{ObjectNode state=(ObjectNode)node;state.remove("energyUpdatedAt");state.remove(List.of("sleepScheduleSeeded","usualSleepMinute","usualWakeMinute"));});
        CompanionWorld legacy=json.treeToValue(tree,CompanionWorld.class);
        legacy.positions.removeIf(position->position.id.endsWith("-desk"));
        CompanionRules.advance(legacy,DAY.plusSeconds(6));
        assertThat(legacy.cafeOpenMinute).isEqualTo(540);
        assertThat(legacy.cafeCloseMinute).isEqualTo(1260);
        assertThat(legacy.cafeStatus).isEqualTo("open");
        assertThat(legacy.residentStates).filteredOn(r->!"self".equals(r.id)).allMatch(r->r.energyUpdatedAt!=null);
        // An old save heals to a usual clock: each resident's own from ResidentDuties where one was
        // planted, the plain 23:00/07:00 default otherwise.
        assertThat(legacy.residentStates).filteredOn(r->!"self".equals(r.id)).allMatch(r->{
            var seed=ResidentDuties.SEEDS.get(r.id);
            return r.sleepScheduleSeeded&&(seed==null?r.usualSleepMinute==1380&&r.usualWakeMinute==420:r.usualSleepMinute==seed.sleepMinute()&&r.usualWakeMinute==seed.wakeMinute());
        });
        for(String id:TownPlaces.RESIDENT_IDS)assertThat(TownPlaces.position(legacy,TownPlaces.homeOf(id)+"-desk")).isNotNull();
    }

    @Test void scheduledClosingIsAPerceptionUntilTheOperatorActuallyChoosesIt(){
        Instant beforeClose=Instant.parse("2026-09-09T12:59:00Z");
        Instant afterClose=beforeClose.plusSeconds(120);
        CompanionWorld w=CompanionRules.join("schedule-cue","我","Asia/Shanghai",beforeClose,true);
        assertThat(w.cafeStatus).isEqualTo("open");
        assertThat(ResidentSimulation.cafeScheduleCue(w,"owner",afterClose)).contains("打烊");
        assertThat(w.cafeStatus).isEqualTo("open");
        assertThat(ResidentSimulation.configureCafeHours(w,8*60+30,20*60+30)).isTrue();
        assertThat(w.cafeOpenMinute).isEqualTo(510);
        assertThat(w.cafeCloseMinute).isEqualTo(1230);
    }

    @Test void realClosingStopsServiceMovesAvatarWithoutEndingFocusAndLetsPortableWorkContinueAtHome(){
        Instant closeAt=Instant.parse("2026-09-09T13:01:00Z");
        CompanionWorld w=CompanionRules.join("closing-flow","我","Asia/Shanghai",closeAt.minusSeconds(120),true);
        w.conversations.stream().filter(c->"active".equals(c.status)).forEach(c->ConversationLifecycle.finish(w,c,closeAt.minusSeconds(30),"测试准备"));
        moveHome(w,"artist",closeAt);moveHome(w,"gardener",closeAt);
        ResidentState owner=ResidentSimulation.state(w,"owner"),student=ResidentSimulation.state(w,"student");
        owner.plan=new Plan("owner-work","work","cafe",null,"把今天的账记完",closeAt,closeAt.plusSeconds(900));
        student.plan=new Plan("student-study","study","cafe",null,"把这一章读完",closeAt,closeAt.plusSeconds(720));
        ResidentSimulation.replaceActor(w,"owner","cafe","work","把今天的账记完",owner.plan.endsAt());
        ResidentSimulation.replaceActor(w,"student","cafe","study","把这一章读完",student.plan.endsAt());
        TownPlaces.release(w,"owner");TownPlaces.release(w,"student");TownPlaces.claim(w,"student","cafe","seat",closeAt);
        Focus focus=new Focus("task-1",closeAt.minusSeconds(300),closeAt.plusSeconds(1500));w.focus=focus;
        Actor avatar=w.avatar;w.avatar=new Actor(avatar.id(),avatar.name(),avatar.role(),"cafe","focus","还在专注",avatar.x(),avatar.y(),focus.endsAt());
        TownPlaces.release(w,"self");TownPlaces.claim(w,"self","cafe","table",closeAt);
        w.serviceRequests.clear();CafeService.request(w,student,closeAt.minusSeconds(20));int requests=w.serviceRequests.size();

        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"cafe","close_cafe",null,"今天做到这里，准备收店","今天收了，明天再来。",List.of(),closeAt)).isTrue();
        assertThat(w.cafeStatus).isEqualTo("closing");
        assertThat(w.serviceRequests).allMatch(request->"abandoned".equals(request.status));
        CafeService.request(w,student,closeAt.plusSeconds(1));
        assertThat(w.serviceRequests).hasSize(requests);
        assertThat(ResidentSimulation.cafeNotice(w,"student")).contains("今天收了");
        assertThat(w.focus).isSameAs(focus);
        assertThat(w.avatar.place()).isEqualTo(TownPlaces.homeOf("self"));
        assertThat(ResidentSimulation.state(w,"self").positionId).isEqualTo("home-self-desk");

        ResidentSimulation.PortableAction studentWork=ResidentSimulation.portableAction(w,"student",closeAt);
        assertThat(studentWork.action()).isEqualTo("study");assertThat(studentWork.reason()).isEqualTo("把这一章读完");assertThat(studentWork.remainingSeconds()).isEqualTo(720);
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,"home","continue_home",null,"回自己桌前把这章读完",null,List.of(),closeAt)).isTrue();
        assertThat(owner.plan.action()).isEqualTo("work");
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"home","continue_home",null,"回家记完剩下的账",null,List.of(),closeAt)).isTrue();
        assertThat(student.plan.action()).isEqualTo("travel");assertThat(student.desiredAction).isEqualTo("study");assertThat(student.desiredDurationSeconds).isEqualTo(720);

        w.updatedAt=closeAt;w.simulatedAt=closeAt;
        // 21:01 Shanghai is already dark, and the student's own room light is still off (LightService),
        // so arrival there inserts a short "开灯" switch_light activity before "study" actually resumes -
        // the extra 120s (over the old +12 margin) covers that real activity, not slack in the test.
        advanceTo(w,student.plan.endsAt().plusSeconds(140));
        assertThat(w.cafeStatus).isEqualTo("closed");
        assertThat(student.plan.action()).isEqualTo("study");assertThat(student.plan.place()).isEqualTo(TownPlaces.homeOf("student"));
        assertThat(student.plan.reason()).isEqualTo("把这一章读完");
        assertThat(Duration.between(student.plan.startedAt(),student.plan.endsAt()).getSeconds()).isEqualTo(720);
        assertThat(student.positionId).isEqualTo("home-student-desk");
        assertThat(owner.plan.action()).isEqualTo("work");assertThat(owner.positionId).isEqualTo("home-owner-desk");
        assertThat(w.focus).isSameAs(focus);
    }

    @Test void closedCafeRejectsNewModelMovementButTheOperatorCanPhysicallyOpenIt(){
        Instant morning=Instant.parse("2026-09-08T23:30:00Z"); // 07:30 Shanghai
        CompanionWorld w=CompanionRules.join("open-flow","我","Asia/Shanghai",morning,true);
        ResidentState artist=ResidentSimulation.state(w,"artist"),owner=ResidentSimulation.state(w,"owner");
        assertThat(w.cafeStatus).isEqualTo("closed");
        assertThat(ResidentSimulation.applyDecision(w,"artist",artist.revision,w.intentRevision,"cafe","observe",null,"去店里看看",null,List.of(),morning)).isFalse();
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"cafe","open_cafe",null,"今天想早点开门",null,List.of(),morning)).isTrue();
        assertThat(owner.plan.action()).isEqualTo("travel");
        w.updatedAt=morning;w.simulatedAt=morning;
        advanceTo(w,owner.plan.endsAt().plusSeconds(6));
        assertThat(owner.plan.action()).isEqualTo("open_cafe");assertThat(w.cafeStatus).isEqualTo("closed");
        advanceTo(w,owner.plan.endsAt().plusSeconds(12));
        assertThat(w.cafeStatus).isEqualTo("open");
    }

    @Test void energyUsesElapsedTimeSleepIsOneContinuousPlanAndLowEnergyOnlyBecomesAPerception(){
        CompanionWorld one=CompanionRules.join("energy-one","我","Asia/Shanghai",DAY,true);
        CompanionWorld many=CompanionRules.join("energy-many","我","Asia/Shanghai",DAY,true);
        ResidentState a=ResidentSimulation.state(one,"artist"),b=ResidentSimulation.state(many,"artist");
        prepareWork(one,a,DAY);prepareWork(many,b,DAY);
        ResidentSimulation.settleEnergy(one,a,DAY.plusSeconds(3600));
        for(int seconds=600;seconds<=3600;seconds+=600)ResidentSimulation.settleEnergy(many,b,DAY.plusSeconds(seconds));
        assertThat(a.energy).isCloseTo(b.energy,org.assertj.core.data.Offset.offset(0.000001));
        assertThat(a.energy).isCloseTo(92.0,org.assertj.core.data.Offset.offset(0.000001));

        ResidentState tired=ResidentSimulation.state(one,"student");tired.energy=7;tired.energyUpdatedAt=DAY;tired.plan=null;
        ResidentSimulation.replaceActor(one,"student",TownPlaces.homeOf("student"),"idle","坐着发困",DAY.plusSeconds(60));
        assertThat(ResidentSimulation.salientPerceptions(one,"student",DAY)).containsExactly("困得几乎做不了需要专注的事");
        assertThat(ResidentSimulation.availableActions(one,"student",DAY)).contains("sleep");
        assertThat(tired.plan).isNull(); // perception does not choose on the resident's behalf

        Instant night=Instant.parse("2026-09-09T15:00:00Z"); // 23:00 Shanghai
        ResidentState normal=ResidentSimulation.state(one,"owner");normal.energy=70;normal.plan=null;
        ResidentSimulation.replaceActor(one,"owner",TownPlaces.homeOf("owner"),"idle","收好手边的东西",night.plusSeconds(60));
        assertThat(ResidentSimulation.routineCues(one,"owner",night)).containsExactly("到了我平常睡觉的时间");
        assertThat(ResidentSimulation.salientPerceptions(one,"owner",night)).isEmpty();
        assertThat(normal.plan).isNull();
        tired.revision++;one.intentRevision=0;
        assertThat(ResidentSimulation.applyDecision(one,"student",tired.revision,one.intentRevision,"home","sleep",null,"太困了，先睡",null,List.of(),night)).isTrue();
        // Her own room is dark and its light is still off (LightService), so the decision lands on a
        // short "开灯" switch_light activity first, exactly like arriving anywhere else dark does -
        // step past it before she is actually asleep.
        assertThat(tired.plan.action()).isEqualTo("switch_light");
        ResidentSimulation.step(one,night.plusSeconds(130));
        assertThat(tired.plan.action()).isEqualTo("sleep");
        assertThat(Duration.between(tired.plan.startedAt(),tired.plan.endsAt()).toHours()).isGreaterThanOrEqualTo(7);
        ResidentSimulation.settleEnergy(one,tired,tired.plan.endsAt());
        assertThat(tired.energy).isGreaterThan(75);
    }

    @Test void continueKeepsTheExactPlanAndSleepIsNotInterruptedByWaitingCustomers(){
        CompanionWorld w=CompanionRules.join("continue-plan","我","Asia/Shanghai",DAY,true);
        w.conversations.stream().filter(c->"active".equals(c.status)).forEach(c->ConversationLifecycle.finish(w,c,DAY,"测试准备"));
        ResidentState owner=ResidentSimulation.state(w,"owner"),student=ResidentSimulation.state(w,"student");
        Plan existing=new Plan("same-plan","make",TownPlaces.homeOf("owner"),null,"把没写完的两行记完",DAY,DAY.plusSeconds(1800));owner.plan=existing;
        ResidentSimulation.replaceActor(w,"owner",TownPlaces.homeOf("owner"),"make",existing.reason(),existing.endsAt());
        assertThat(ResidentSimulation.applyDecision(w,"owner",owner.revision,w.intentRevision,"home","continue",null,"接着写",null,List.of(),DAY.plusSeconds(60))).isTrue();
        assertThat(owner.plan).isSameAs(existing);assertThat(owner.plan.endsAt()).isEqualTo(DAY.plusSeconds(1800));

        owner.plan=new Plan("night-sleep","sleep",TownPlaces.homeOf("owner"),null,"已经睡着",DAY,DAY.plusSeconds(3600));
        ResidentSimulation.replaceActor(w,"owner",TownPlaces.homeOf("owner"),"sleep","已经睡着",owner.plan.endsAt());
        ResidentSimulation.replaceActor(w,"student","cafe","rest","等一杯热的",DAY.plusSeconds(3600));
        w.serviceRequests.clear();CafeService.request(w,student,DAY);owner.dutyPressure=100;
        w.updatedAt=DAY;w.simulatedAt=DAY;
        CompanionRules.advance(w,DAY.plusSeconds(60));
        assertThat(owner.plan.action()).isEqualTo("sleep");
        assertThat(w.serviceRequests.getFirst().status).isIn("waiting","abandoned");
        assertThat(w.serviceRequests.getFirst().status).isNotIn("preparing","delivered","consumed");
    }

    @Test void aPortableCafeTaskPausedBeforeSleepCanResumeAtHomeAfterTheCafeCloses(){
        CompanionWorld w=CompanionRules.join("resume-after-sleep","我","Asia/Shanghai",DAY,true);
        w.conversations.stream().filter(c->"active".equals(c.status)).forEach(c->ConversationLifecycle.finish(w,c,DAY,"测试准备"));
        ResidentState student=ResidentSimulation.state(w,"student");student.plan=null;
        SuspendedAction paused=new SuspendedAction();paused.plan=new Plan("paused-study","study","cafe",null,"把这一章读完",DAY,DAY.plusSeconds(640));paused.pausedAt=DAY;student.suspendedAction=paused;
        w.cafeStatus="closed";w.cafeStatusChangedAt=DAY;
        ResidentSimulation.replaceActor(w,"student",TownPlaces.homeOf("student"),"idle","刚醒来",DAY.plusSeconds(60));TownPlaces.release(w,"student");
        var view=ResidentSimulation.pausedAction(w,"student",DAY.plusSeconds(40));
        assertThat(view.place()).isEqualTo(TownPlaces.homeOf("student"));assertThat(view.remainingSeconds()).isEqualTo(640);
        assertThat(ResidentSimulation.availableActions(w,"student",DAY.plusSeconds(40))).contains("resume");
        assertThat(ResidentSimulation.applyDecision(w,"student",student.revision,w.intentRevision,"home","resume",null,"在自己桌前接着读",null,List.of(),DAY.plusSeconds(40))).isTrue();
        assertThat(student.plan.action()).isEqualTo("study");assertThat(student.plan.place()).isEqualTo(TownPlaces.homeOf("student"));
        assertThat(Duration.between(student.plan.startedAt(),student.plan.endsAt()).getSeconds()).isEqualTo(640);
        assertThat(student.positionId).isEqualTo("home-student-desk");
    }

    private static void moveHome(CompanionWorld w,String id,Instant at){
        ResidentState r=ResidentSimulation.state(w,id);r.plan=new Plan("home-"+id,"rest",TownPlaces.homeOf(id),null,"在家",at,at.plusSeconds(3600));
        ResidentSimulation.replaceActor(w,id,TownPlaces.homeOf(id),"rest","在家",r.plan.endsAt());TownPlaces.release(w,id);TownPlaces.claim(w,id,TownPlaces.homeOf(id),null,at);
    }
    private static void prepareWork(CompanionWorld w,ResidentState r,Instant at){
        r.energy=100;r.energyUpdatedAt=at;r.plan=new Plan("work","work",TownPlaces.homeOf(r.id),null,"工作",at,at.plusSeconds(7200));
        ResidentSimulation.replaceActor(w,r.id,TownPlaces.homeOf(r.id),"work","工作",r.plan.endsAt());
    }
}
