package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;

/** A "none" answer to an occasion must survive being written back. JdbcWorldStore only persists a
 * world whose revision moved; the live-object test stores never noticed, and a real 25-person town
 * re-asked the same six residents 1,083 times in seventeen minutes. */
class ConsiderAnswerPersistsTest {
    @Test void answeredOccasionIsNotAskedAgainAfterReload()throws Exception {
        var clock=new ResidentDirectorDialogueTest.MutableClock(Instant.parse("2026-09-14T02:33:11Z"));
        var world=CompanionRules.join("consider-persists","住客","Asia/Shanghai",clock.instant(),true);
        world.conversations.clear();world.serviceRequests.clear();world.pendingOccasions.clear();
        for(var r:world.residentStates)if(!Set.of("messenger","self").contains(r.id))
            r.plan=new Plan("park-"+r.id,"sleep",TownPlaces.homeOf(r.id),null,"睡着",clock.instant(),clock.instant().plusSeconds(3600));
        var messenger=ResidentSimulation.state(world,"messenger");messenger.plan=null;
        world.projects.removeIf(p->"messenger".equals(p.ownerId));
        Occasions.scan(world,clock.instant());
        assertThat(world.pendingOccasions).extracting(p->p.residentId+"|"+p.key).containsExactly("messenger|change_work");

        var store=new RevisionGatedStore(world);
        var asked=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public ConsiderDraft consider(ConsiderRequest request){asked.incrementAndGet();return new ConsiderDraft("none",null,null,List.of());}
            public Decision decide(Context context){return new Decision("none",null,null,"","",List.of(),null,null);}
        };
        var director=new ResidentDirector(store,mind,clock,100000,(userId,day,callType,inputTokens,outputTokens)->{},8,64,12,1);
        try{
            for(int i=0;i<40;i++){director.consider(1,store.read(1));Thread.sleep(10);}
        }finally{director.close();}
        assertThat(asked.get()).isEqualTo(1);
        assertThat(store.read(1).pendingOccasions).isEmpty();
    }

    @Test void anEncounterAnswerThatCannotBeUsedIsNotAskedAgain()throws Exception {
        var clock=new ResidentDirectorDialogueTest.MutableClock(Instant.parse("2026-09-14T03:00:00Z"));
        var world=CompanionRules.join("react-consumed","住客","Asia/Shanghai",clock.instant(),true);
        world.conversations.clear();world.pendingOccasions.clear();world.pendingEncounters.clear();
        for(var r:world.residentStates)if(!"self".equals(r.id))
            r.plan=new Plan("park-"+r.id,"sleep",TownPlaces.homeOf(r.id),null,"睡着",clock.instant(),clock.instant().plusSeconds(3600));
        var messenger=ResidentSimulation.state(world,"messenger");
        var pending=new PendingEncounter();pending.id="pe-test";pending.residentId="messenger";pending.otherId="trader";
        pending.place="board";pending.at=clock.instant();pending.residentRevision=messenger.revision;
        world.pendingEncounters.add(pending);
        var store=new RevisionGatedStore(world);
        var asked=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            // A blank reason is refused by applyReaction before it ever looks at the pending encounter.
            public ReactDraft react(ReactRequest request){asked.incrementAndGet();return new ReactDraft("none","",List.of());}
            public Decision decide(Context context){return new Decision("none",null,null,"","",List.of(),null,null);}
        };
        var director=new ResidentDirector(store,mind,clock,100000,(userId,day,callType,inputTokens,outputTokens)->{},8,64,12,1);
        try{for(int i=0;i<40;i++){director.consider(1,store.read(1));Thread.sleep(10);}}
        finally{director.close();}
        assertThat(asked.get()).isEqualTo(1);
        assertThat(store.read(1).pendingEncounters).isEmpty();
    }

    /** JdbcWorldStore's contract without the database: every read is a fresh decode, and an update is
     * written back only when the operation moved {@code revision}. */
    static class RevisionGatedStore implements WorldStore {
        private static final JsonMapper JSON=JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        private String saved;
        RevisionGatedStore(CompanionWorld world){saved=encode(world);}
        public synchronized CompanionWorld read(long id){return decode(saved);}
        public synchronized CompanionWorld update(long id,Supplier<CompanionWorld> initial,UnaryOperator<CompanionWorld> change){
            CompanionWorld w=decode(saved);long revision=w.revision;
            w=change.apply(w);
            if(w.revision!=revision)saved=encode(w);
            return w;
        }
        public boolean ownsTask(long id,String task){return false;}
        public String timezone(long id){return "Asia/Shanghai";}
        private static String encode(CompanionWorld w){try{return JSON.writeValueAsString(w);}catch(Exception e){throw new IllegalStateException(e);}}
        private static CompanionWorld decode(String s){try{return JSON.readValue(s,CompanionWorld.class);}catch(Exception e){throw new IllegalStateException(e);}}
    }
}
