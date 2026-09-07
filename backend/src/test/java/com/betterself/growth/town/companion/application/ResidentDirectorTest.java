package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
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
        var unavailable=new ResidentDirector(failedStore,new ResidentMind(){public boolean enabled(){return true;}public Decision decide(Context c){throw new IllegalStateException("network unavailable");}},Clock.fixed(now,ZoneOffset.UTC));
        try{unavailable.consider(1,failedStore.world);assertThat(failedStore.finished.await(2,TimeUnit.SECONDS)).isTrue();assertThat(failedStore.world.residentStates).allMatch(r->r.plan!=null);assertThat(failedStore.world.modelStatus).contains("习惯");}
        finally{unavailable.close();}
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
