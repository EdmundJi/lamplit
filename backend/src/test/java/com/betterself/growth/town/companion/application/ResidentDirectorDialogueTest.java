package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;

class ResidentDirectorDialogueTest {
    @Test void routesEverySpeakerAndEachRecollectionThroughIndependentModelOperations()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("generated-dialogue","私密用户名字","Asia/Shanghai",clock.instant(),true);
        var c=world.conversations.stream().filter(t->t.mode.equals("model")&&t.status.equals("active")).findFirst().orElseThrow();
        var store=new Store(world);var turns=new AtomicInteger();var summaries=new AtomicInteger();
        List<String> speakers=Collections.synchronizedList(new ArrayList<>());
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){throw new AssertionError("A generated conversation must not go through plan-decision insertion");}
            public ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
                assertThat(store.transaction.get()).isFalse();
                assertThat(request.perspective().memories()).allMatch(m->m.ownerId().equals(request.perspective().residentId()));
                assertThat(request.perspective().toString()).doesNotContain("私密用户名字");
                speakers.add(request.perspective().residentId());int turn=turns.incrementAndGet();
                assertThat(request.perspective().conversation()).hasSize(turn-1);
                return new ConversationLifecycle.Utterance(turn==1?"我想留一张空白书签，让来的人写一句没说完的话。":"我愿意帮忙画书签。现在先去找纸，回头见。",turn==2,"期待",turn==2?"accept":"none",null,List.of());
            }
            public ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){
                assertThat(store.transaction.get()).isFalse();
                assertThat(request.transcript()).hasSize(2).allMatch(t->t.source().equals("model"));
                assertThat(request.conversationMemories()).allMatch(m->m.ownerId().equals(request.perspective().residentId()));
                summaries.incrementAndGet();
                return new ConversationLifecycle.Recollection("我记住了和"+request.partnerName()+"讨论的那张空白书签。还没做出来，但对方的话让我想认真试试。","期待",List.of(request.conversationMemories().getFirst().id()));
            }
        };
        var director=new ResidentDirector(store,mind,clock);
        try {
            await(()->{director.consider(1,world);return c.turns.size()==1;});
            clock.now=clock.now.plusSeconds(7);
            await(()->{director.consider(1,world);return c.status.equals("ended");});
            await(()->{director.consider(1,world);return c.summarizedParticipants.size()==2;});
            assertThat(speakers).containsExactly("owner","artist");
            assertThat(turns).hasValue(2);assertThat(summaries).hasValue(2);
            assertThat(c.turns).extracting(Turn::text).containsExactly("我想留一张空白书签，让来的人写一句没说完的话。","我愿意帮忙画书签。现在先去找纸，回头见。");
            assertThat(c.recollectionSources).containsEntry("owner","model").containsEntry("artist","model");
        }finally{director.close();}
    }
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);assertThat(condition.getAsBoolean()).isTrue();}
    static class MutableClock extends Clock {
        volatile Instant now;MutableClock(Instant now){this.now=now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    static class Store implements WorldStore {
        CompanionWorld world;ThreadLocal<Boolean> transaction=ThreadLocal.withInitial(()->false);
        Store(CompanionWorld world){this.world=world;}
        public CompanionWorld read(long id){return world;}
        public synchronized CompanionWorld update(long id,Supplier<CompanionWorld> initial,UnaryOperator<CompanionWorld> change){transaction.set(true);try{return world=change.apply(world);}finally{transaction.set(false);}}
        public boolean ownsTask(long id,String task){return false;}
        public String timezone(long id){return "Asia/Shanghai";}
    }
}
