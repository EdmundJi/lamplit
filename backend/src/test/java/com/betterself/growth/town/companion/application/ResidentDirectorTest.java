package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;

class ResidentDirectorTest {
    final Instant now=Instant.parse("2026-09-08T06:00:00Z");
    @Test void modelSeesOnePerspectiveOutsideTransactionAndLateCancelDiscardsReply()throws Exception {
        var world=CompanionRules.join("model-test","我","Asia/Shanghai",now);
        var intent=new CompanionWorld.Intent("private-idea","walk","passing",null,25,now);intent.text="PRIVATE_NEVER_SEND_TO_NPC";
        CompanionRules.submit(world,intent,now);
        var store=new FakeStore(world);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){
                assertThat(store.transaction.get()).isFalse();
                assertThat(c.memories()).allMatch(m->m.ownerId().equals(c.residentId()));
                assertThat(c.nearby()).allMatch(a->a.place().equals(c.self().place()));
                assertThat(c.toString()).doesNotContain("PRIVATE_NEVER_SEND_TO_NPC");
                started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
                return new Decision("observe",c.self().place(),null,"先听完邻居的话","你刚才说的主意，我想认真听一听。",List.of(c.memories().getFirst().id()),null,null);
            }
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try {
            director.consider(1,world);assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            store.update(1,null,w->{CompanionRules.cancel(w,intent.id,now);return w;});release.countDown();
            assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(world.events).noneMatch(e->e.type().equals("thought"));
            assertThat(world.modelStatus).contains("过时");
            assertThat(world.intents.getFirst().status).isEqualTo("cancelled");
        } finally {release.countDown();director.close();}
    }
    @Test void modelFailureLeavesPlansRunningAndAValidReplyCanLand()throws Exception {
        var w=CompanionRules.join("model-valid","我","Asia/Shanghai",now);var store=new FakeStore(w);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){return new Decision("observe",c.self().place(),null,"我想先听听知夏对颜色的想法","我们先挑一种你最想留下的颜色，好吗？",List.of(c.memories().getFirst().id()),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(1,w);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();assertThat(w.events).anyMatch(e->e.type().equals("thought"));}
        finally{director.close();}
        var failedStore=new FakeStore(CompanionRules.join("model-fails","我","Asia/Shanghai",now));
        var plansBeforeFailure=failedStore.world.residentStates.stream().filter(r->!r.id.equals("self")).map(r->r.plan==null?null:r.plan.id()).toList();
        var unavailable=new ResidentDirector(failedStore,new ResidentMind(){public boolean enabled(){return true;}public Decision decide(Context c){throw new IllegalStateException("network unavailable");}},Clock.fixed(now,ZoneOffset.UTC));
        try{unavailable.consider(1,failedStore.world);assertThat(failedStore.finished.await(2,TimeUnit.SECONDS)).isTrue();assertThat(failedStore.world.residentStates.stream().filter(r->!r.id.equals("self")).map(r->r.plan==null?null:r.plan.id()).toList()).containsExactlyElementsOf(plansBeforeFailure);assertThat(failedStore.world.modelStatus).contains("习惯");}
        finally{unavailable.close();}
    }
    @Test void avatarIsPerceivedByNearbyResidentsButNeverCarriesUserText()throws Exception {
        var world=CompanionRules.join("avatar-nearby","我","Asia/Shanghai",now);
        var intent=new CompanionWorld.Intent("secret-thought-01","thought","explicit",null,25,now);
        intent.text="PRIVATE_TODO_DO_NOT_SHOW_NPC";intent.resolvedKind="visit"; // sends the avatar to "cafe"
        CompanionRules.submit(world,intent,now);
        assertThat(world.avatar.place()).isEqualTo("cafe");
        assertThat(world.avatar.label()).doesNotContain("PRIVATE_TODO_DO_NOT_SHOW_NPC");
        // Make "gardener" the sole, deterministic model-decision candidate, standing where the avatar now is.
        for(int i=0;i<world.residents.size();i++) {
            var a=world.residents.get(i);
            if(a.id().equals("gardener"))world.residents.set(i,new CompanionWorld.Actor(a.id(),a.name(),a.role(),"cafe","observe","看看周围",a.x(),a.y(),now.plusSeconds(200)));
        }
        ResidentSimulation.state(world,"gardener").plan=new CompanionWorld.Plan("test-plan","observe","cafe",null,"看看周围",now,now.plusSeconds(200));
        for(var r:world.residentStates)if(!r.id.equals("gardener")&&!r.id.equals("self"))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"测试中睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);
        var captured=new ResidentMind.Context[1];
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){captured[0]=c;return new Decision("observe",c.self().place(),null,"先看看四周","",List.of(c.memories().getFirst().id()),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(1,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}
        finally{director.close();}
        var context=captured[0];
        assertThat(context).isNotNull();
        assertThat(context.residentId()).isEqualTo("gardener");
        assertThat(context.nearby()).anyMatch(a->a.id().equals("self")&&a.place().equals("cafe"));
        assertThat(context.toString()).doesNotContain("PRIVATE_TODO_DO_NOT_SHOW_NPC");
    }
    @Test void privateAffectionAndWhetherItWasEverSaidAloudNeverReachAnotherResidentsContext()throws Exception {
        var world=CompanionRules.join("private-affection","我","Asia/Shanghai",now);
        // Student's own private feelings about a third resident, and whether she has ever let that
        // show - only student's own future context should ever see these.
        var student=ResidentSimulation.state(world,"student");
        student.relationships.put("artist",97);
        student.affectionExpressed.put("artist",true);
        // Make "gardener" the sole, deterministic model-decision candidate, same setup as
        // avatarIsPerceivedByNearbyResidentsButNeverCarriesUserText above.
        for(int i=0;i<world.residents.size();i++) {
            var a=world.residents.get(i);
            if(a.id().equals("gardener"))world.residents.set(i,new CompanionWorld.Actor(a.id(),a.name(),a.role(),"cafe","observe","看看周围",a.x(),a.y(),now.plusSeconds(200)));
        }
        ResidentSimulation.state(world,"gardener").plan=new CompanionWorld.Plan("test-plan","observe","cafe",null,"看看周围",now,now.plusSeconds(200));
        for(var r:world.residentStates)if(!r.id.equals("gardener")&&!r.id.equals("self"))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"测试中睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);
        var captured=new ResidentMind.Context[1];
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){captured[0]=c;return new Decision("observe",c.self().place(),null,"先看看四周","",List.of(c.memories().getFirst().id()),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(1,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}
        finally{director.close();}
        var context=captured[0];
        assertThat(context).isNotNull();
        assertThat(context.residentId()).isEqualTo("gardener");
        // Internal gauges and another resident's private relationship state are absent from the
        // model contract itself, rather than merely being set to an innocuous-looking value.
        assertThat(Arrays.stream(ResidentMind.Context.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("energy","social","curiosity","relationships","extroversion","conscientiousness","sensitivity","volatility","dutyPressure");
        assertThat(context.toString()).doesNotContain("affectionExpressed");
    }
    @Test void normalResidentWithoutAPlanReachesTheModelWithoutInternalGauges()throws Exception {
        var world=CompanionRules.join("qualitative-context","我","Asia/Shanghai",now,true);world.conversations.clear();world.serviceRequests.clear();
        var owner=ResidentSimulation.state(world,"owner");owner.plan=null;owner.energy=70;
        for(var r:world.residentStates)if(!Set.of("owner","self").contains(r.id))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        var captured=new ResidentMind.Context[1];var store=new FakeStore(world);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){captured[0]=c;return new Decision("rest","home",null,"先回去坐一会儿","",List.of(c.memories().getFirst().id()),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(41,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}finally{director.close();}

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].residentId()).isEqualTo("owner");
        assertThat(captured[0].salientPerceptions()).isEmpty();
        assertThat(captured[0].availableActions()).contains("sleep","rest");
        assertThat(Arrays.stream(ResidentMind.Context.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("worldId","revision","intentRevision","at","mood","thought","energy","social","curiosity","relationships","dutyPressure");
        assertThat(Arrays.stream(ResidentMind.ActorView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("x","y","until");
        assertThat(Arrays.stream(ResidentMind.MemoryView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("importance");
    }
    @Test void tiredPerceptionLetsTheModelContinueWithoutResettingTheCurrentPlan()throws Exception {
        var world=CompanionRules.join("continue-context","我","Asia/Shanghai",now,true);world.conversations.clear();world.serviceRequests.clear();
        var owner=ResidentSimulation.state(world,"owner");owner.energy=15;
        var plan=new CompanionWorld.Plan("work-in-progress","work","cafe",null,"把账本最后一页写完",now.minusSeconds(120),now.plusSeconds(600));owner.plan=plan;
        for(int i=0;i<world.residents.size();i++){var actor=world.residents.get(i);if("owner".equals(actor.id()))world.residents.set(i,new CompanionWorld.Actor(actor.id(),actor.name(),actor.role(),"cafe","work","还在写账本",actor.x(),actor.y(),now.plusSeconds(600)));}
        for(var r:world.residentStates)if(!Set.of("owner","self").contains(r.id))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);var calls=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){calls.incrementAndGet();assertThat(c.salientPerceptions()).contains("已经很累，注意力很难维持");assertThat(c.availableActions()).contains("continue","sleep");return new Decision("continue","cafe",null,"这页只剩一点，先写完再说","",List.of(),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(42,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}finally{director.close();}

        assertThat(calls).hasValue(1);
        assertThat(owner.plan).isSameAs(plan);
        assertThat(owner.plan.endsAt()).isEqualTo(now.plusSeconds(600));
    }
    @Test void pausedWorkIsVisibleAndTheModelCanResumeItsAuthoritativeRemainder()throws Exception {
        var world=CompanionRules.join("resume-context","我","Asia/Shanghai",now,true);world.conversations.clear();world.serviceRequests.clear();
        var owner=ResidentSimulation.state(world,"owner");owner.energy=70;owner.plan=null;
        var pausedPlan=new CompanionWorld.Plan("paused-ledger","work","cafe",null,"把账本最后一页写完",now.minusSeconds(300),now.plusSeconds(300));
        owner.suspendedAction=new CompanionWorld.SuspendedAction();owner.suspendedAction.plan=pausedPlan;owner.suspendedAction.pausedAt=now;owner.suspendedAction.desiredDurationSeconds=300;
        for(var r:world.residentStates)if(!Set.of("owner","self").contains(r.id))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);var captured=new ResidentMind.Context[1];
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){captured[0]=c;return new Decision("resume","cafe",null,"账本还没写完，接着来","",List.of(),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(43,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}finally{director.close();}

        assertThat(captured[0].pausedAction()).isEqualTo(new ResidentMind.PausedActionView("work","cafe","把账本最后一页写完",600));
        assertThat(captured[0].availableActions()).contains("resume");
        assertThat(owner.suspendedAction).isNull();
        assertThat(owner.plan.action()).isIn("travel","work");
        if("travel".equals(owner.plan.action())){assertThat(owner.desiredAction).isEqualTo("work");assertThat(owner.desiredDurationSeconds).isEqualTo(600);}
    }
    @Test void portableCafeWorkCanResumeAtHomeAfterTheCafeHasClosed()throws Exception {
        var world=CompanionRules.join("resume-home-context","我","Asia/Shanghai",now,true);world.conversations.clear();world.serviceRequests.clear();world.cafeStatus="closed";
        var owner=ResidentSimulation.state(world,"owner");owner.energy=70;owner.plan=null;
        var pausedPlan=new CompanionWorld.Plan("paused-ledger-home","work","cafe",null,"把账本最后一页写完",now.minusSeconds(300),now.plusSeconds(300));
        owner.suspendedAction=new CompanionWorld.SuspendedAction();owner.suspendedAction.plan=pausedPlan;owner.suspendedAction.pausedAt=now;owner.suspendedAction.desiredDurationSeconds=300;
        for(int i=0;i<world.residents.size();i++){var actor=world.residents.get(i);if("owner".equals(actor.id()))world.residents.set(i,new CompanionWorld.Actor(actor.id(),actor.name(),actor.role(),"home-owner","idle","刚睡醒",actor.x(),actor.y(),now.plusSeconds(600)));}
        for(var r:world.residentStates)if(!Set.of("owner","self").contains(r.id))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);var captured=new ResidentMind.Context[1];
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){captured[0]=c;return new Decision("resume","home",null,"在家把剩下这页写完","",List.of(),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(44,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}finally{director.close();}

        assertThat(captured[0].pausedAction()).isEqualTo(new ResidentMind.PausedActionView("work","home","把账本最后一页写完",600));
        assertThat(captured[0].availableActions()).contains("resume");
        assertThat(owner.suspendedAction).isNull();
        assertThat(owner.plan.action()).isEqualTo("work");
        assertThat(owner.plan.place()).isEqualTo("home-owner");
        assertThat(Duration.between(now,owner.plan.endsAt()).getSeconds()).isEqualTo(600);
    }
    @Test void retainedOperatorSeesAReopenPathWhileFormerOwnerOnlySeesOrdinaryCafeUses() {
        Instant morning=Instant.parse("2026-09-09T01:05:00Z");
        var world=CompanionRules.join("cafe-return-context","我","Asia/Shanghai",morning,true);world.conversations.clear();world.cafeOperatorId="owner";world.cafeOperating=false;world.cafeStatus="closed";
        var director=new ResidentDirector(new FakeStore(world),new ResidentMind(){public boolean enabled(){return false;}public Decision decide(Context context){throw new UnsupportedOperationException();}},Clock.fixed(morning,ZoneOffset.UTC));
        try{
            var retained=director.perspective(world,"owner",morning,List.of());
            assertThat(retained.cafeRoleFacts()).contains("我是咖啡馆当前经营者，经营权和吧台设备责任仍在我这里。","我熟悉咖啡馆、吧台和日常开关门方式。","我之前暂停了经营，咖啡馆目前已经关门。");
            assertThat(retained.cafeScheduleCue()).isEqualTo("到了咖啡馆平常开门时间；目前经营暂停，门仍关着");
            assertThat(retained.availableActions()).contains("open_cafe");

            world.cafeOperatorId="artist";world.cafeOperating=true;world.cafeStatus="open";
            var former=director.perspective(world,"owner",morning,List.of());
            assertThat(former.cafeRoleFacts()).doesNotContain("我是咖啡馆当前经营者，经营权和吧台设备责任仍在我这里。");
            assertThat(former.availableActions()).doesNotContain("open_cafe","close_cafe");
            assertThat(former.knownPlaces()).filteredOn(place->place.id().equals("cafe")).singleElement().satisfies(place->{assertThat(place.description()).contains("六个独立窗边座位","安静读书","制作");assertThat(place.possibleActivities()).contains("read","work","make");});
        }finally{director.close();}
    }
    @Test void modelUsageIsRecordedPerUserWorldDayAndCallType()throws Exception {
        var world=CompanionRules.join("model-usage","我","Asia/Shanghai",now);
        var store=new FakeStore(world);
        List<Object[]> recorded=Collections.synchronizedList(new ArrayList<>());
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){throw new AssertionError("director must call the metered entry point");}
            public Result<Decision> decideMetered(Context c){
                var decision=new Decision("observe",c.self().place(),null,"先听完邻居的话","",List.of(c.memories().getFirst().id()),null,null);
                return new Result<>(decision,new Usage(120,45));
            }
        };
        ModelUsageRecorder recorder=(userId,day,callType,inputTokens,outputTokens)->recorded.add(new Object[]{userId,day,callType,inputTokens,outputTokens});
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC),128,recorder);
        try{director.consider(7,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}
        finally{director.close();}
        assertThat(recorded).hasSize(1);
        Object[] entry=recorded.get(0);
        assertThat(entry[0]).isEqualTo(7L);
        assertThat(entry[1]).isEqualTo(now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());
        assertThat(entry[2]).isEqualTo("decision");
        assertThat(entry[3]).isEqualTo(120);
        assertThat(entry[4]).isEqualTo(45);
    }
    @Test void modelDecisionWithoutMeasuredUsageRecordsNothingAndDoesNotCrash()throws Exception {
        // Mirrors both mock mode (never reaches the director at all) and any ResidentMind that only
        // implements the plain, unmetered methods: usage stays null and must never be reported as spend.
        var world=CompanionRules.join("model-no-usage","我","Asia/Shanghai",now);
        var store=new FakeStore(world);
        AtomicInteger recordCalls=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){return new Decision("observe",c.self().place(),null,"先听完邻居的话","",List.of(c.memories().getFirst().id()),null,null);}
        };
        ModelUsageRecorder recorder=(userId,day,callType,inputTokens,outputTokens)->recordCalls.incrementAndGet();
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC),128,recorder);
        try{director.consider(9,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}
        finally{director.close();}
        assertThat(recordCalls).hasValue(0);
    }
    @Test void perResidentThrottleLetsADifferentResidentDecideImmediatelyAfterAnother()throws Exception {
        // Item 1: the old world-global modelRequestedAt gate meant nobody else could think again for
        // the whole throttle window after ANY one resident's decision. With a per-resident cooldown, a
        // second resident who has never been throttled gets a chance in the very same instant.
        var world=CompanionRules.join("per-resident-throttle","我","Asia/Shanghai",now,true);world.conversations.clear();world.serviceRequests.clear();
        var owner=ResidentSimulation.state(world,"owner");owner.plan=null;
        var gardener=ResidentSimulation.state(world,"gardener");gardener.plan=null;
        for(var r:world.residentStates)if(!Set.of("owner","gardener","self").contains(r.id))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);
        List<String> decided=Collections.synchronizedList(new ArrayList<>());
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){decided.add(c.residentId());return new Decision("observe",c.self().place(),null,"先看看四周","",List.of(c.memories().getFirst().id()),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{
            director.consider(61,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();
            store.finished=new CountDownLatch(1);
            director.consider(61,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();
        }finally{director.close();}
        assertThat(decided).containsExactlyInAnyOrder("owner","gardener");
    }
    @Test void everyDispatchedDecisionRecordsWhyItWasTriggered()throws Exception {
        var world=CompanionRules.join("decision-trigger","我","Asia/Shanghai",now,true);world.conversations.clear();world.serviceRequests.clear();
        var owner=ResidentSimulation.state(world,"owner");owner.plan=null;owner.suspendedAction=null;
        for(var r:world.residentStates)if(!Set.of("owner","self").contains(r.id))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        var store=new FakeStore(world);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){return new Decision("observe",c.self().place(),null,"先看看四周","",List.of(c.memories().getFirst().id()),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(62,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}finally{director.close();}
        // Diagnostic-only (item 6): never read by any model, exposed purely for later export.
        assertThat(world.decisionTriggers).anyMatch(t->t.residentId().equals("owner")&&t.trigger().equals("plan_ended"));
    }
    @Test void avatarJoinsTheDecisionLoopWhenOptedInWithoutUserTextAndYieldsToAnExplicitIntent()throws Exception {
        // Item 7: off by default (see CompanionWorld.avatarAutonomyEnabled) - this test turns it on to
        // exercise the mechanism directly, extending the same marker-absence invariant
        // avatarIsPerceivedByNearbyResidentsButNeverCarriesUserText already checks for a nearby NPC's
        // context onto the avatar's OWN context, the one place user-supplied text is most likely to
        // leak now that the avatar is itself the one being asked to decide.
        var world=CompanionRules.join("avatar-autonomy","我","Asia/Shanghai",now);
        world.avatarAutonomyEnabled=true;world.conversations.clear();
        for(var r:world.residentStates)if(!r.id.equals("self"))r.plan=new CompanionWorld.Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",now,now.plusSeconds(600));
        // A finished explicit intent leaves user-typed text sitting in world state (Intent.feedback) -
        // the avatar's own decision context must never carry a single character of it even so.
        var intent=new CompanionWorld.Intent("secret-01","walk","explicit",null,25,now);
        intent.status="done";intent.text="PRIVATE_NEVER_SEND_TO_NPC_AVATAR";
        intent.feedback="记下这个念头了：PRIVATE_NEVER_SEND_TO_NPC_AVATAR。现在沿着小街慢慢散步。";
        world.intents.add(intent);
        assertThat(ResidentSimulation.selfIsFree(world)).isTrue();
        var captured=new ResidentMind.Context[1];var store=new FakeStore(world);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context c){captured[0]=c;return new Decision("rest","home",null,"先坐一会儿","",List.of(),null,null);}
        };
        var director=new ResidentDirector(store,mind,Clock.fixed(now,ZoneOffset.UTC));
        try{director.consider(71,world);assertThat(store.finished.await(2,TimeUnit.SECONDS)).isTrue();}finally{director.close();}
        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].residentId()).isEqualTo("self");
        assertThat(captured[0].toString()).doesNotContain("PRIVATE_NEVER_SEND_TO_NPC_AVATAR");
        var self=ResidentSimulation.state(world,"self");
        assertThat(self.plan).isNotNull();assertThat(self.plan.action()).isIn("travel","rest");
        if("travel".equals(self.plan.action()))assertThat(self.desiredAction).isEqualTo("rest");

        // An explicit user intent always pre-empts whatever the avatar's own model just chose.
        var explicit=new CompanionWorld.Intent("explicit-01","water","explicit",null,25,now);
        CompanionRules.submit(world,explicit,now);
        assertThat(ResidentSimulation.selfIsFree(world)).isFalse();
        CompanionRules.advance(world,now.plusSeconds(6));
        assertThat(self.plan).isNull();
    }
    static class FakeStore implements WorldStore {
        CompanionWorld world;
        ThreadLocal<Boolean> transaction=ThreadLocal.withInitial(()->false);
        CountDownLatch finished=new CountDownLatch(1);
        FakeStore(CompanionWorld w){world=w;}
        public CompanionWorld read(long id){return world;}
        public synchronized CompanionWorld update(long id,Supplier<CompanionWorld> initial,UnaryOperator<CompanionWorld> change){
            transaction.set(true);try{world=change.apply(world);if(world.modelStatus.contains("过时")||world.modelStatus.contains("补充")||world.modelStatus.contains("习惯"))finished.countDown();return world;}finally{transaction.set(false);}
        }
        public boolean ownsTask(long id,String task){return false;}
        public String timezone(long id){return "Asia/Shanghai";}
    }
}
