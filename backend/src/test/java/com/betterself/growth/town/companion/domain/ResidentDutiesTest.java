package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ResidentDutiesTest {
    private final Instant start=Instant.parse("2026-09-14T00:00:00Z"); // 08:00 local

    @Test void everyResidentIsPlantedOnceWithTheirOwnClockAndASeedMemory(){
        var w=CompanionRules.join("duties-planted","住客","Asia/Shanghai",start,true);
        CompanionRules.advance(w,start.plusSeconds(6));
        for(var r:w.residentStates){
            if("self".equals(r.id))continue;
            assertThat(r.dutiesSeeded).as(r.id).isTrue();
            assertThat(r.duties).as(r.id).hasSizeBetween(2,4);
            assertThat(r.dutiesVersion).as(r.id).isEqualTo(ResidentDuties.VERSION);
            assertThat(w.memories).as(r.id).anyMatch(m->m.ownerId().equals(r.id)&&"seed".equals(m.sourceType())&&"work".equals(m.topicId()));
            for(Duty d:r.duties){
                assertThat(TownPlaces.contains(w,d.place)).as(r.id+" "+d.place).isTrue();
                assertThat(!TownPlaces.isHome(d.place)||d.place.equals(TownPlaces.homeOf(r.id))).as(r.id+" "+d.place).isTrue();
                assertThat(ResidentDuties.PLAN_ACTIONS).as(r.id).contains(d.action);
                assertThat(d.toId==null||ResidentSimulation.state(w,d.toId)!=null).as(r.id+" to "+d.toId).isTrue();
            }
        }
        assertThat(w.residentStates.stream().filter(r->!"self".equals(r.id)).map(r->r.usualWakeMinute).distinct().count()).isGreaterThan(5);
        long seedMemories=w.memories.stream().filter(m->"work".equals(m.topicId())&&"seed".equals(m.sourceType())).count();
        CompanionRules.advance(w,start.plusSeconds(12));
        assertThat(w.memories.stream().filter(m->"work".equals(m.topicId())&&"seed".equals(m.sourceType())).count()).isEqualTo(seedMemories);
    }

    @Test void aWorldPlantedFromAnOlderTableGetsTheNewDutiesButNotASecondMemory(){
        var w=CompanionRules.join("duties-upgrade","住客","Asia/Shanghai",start,true);
        CompanionRules.advance(w,start.plusSeconds(6));
        var scholar=ResidentSimulation.state(w,"scholar");var fixer=ResidentSimulation.state(w,"fixer");
        scholar.dutiesVersion=1;scholar.duties.removeLast();
        fixer.dutiesVersion=1;fixer.duties.clear(); // changed occupation since
        long memories=w.memories.stream().filter(m->"work".equals(m.topicId())).count();
        CompanionRules.advance(w,start.plusSeconds(12));
        assertThat(scholar.duties).hasSize(ResidentDuties.SEEDS.get("scholar").duties().size());
        assertThat(scholar.duties).extracting(d->d.startMinute).isSorted();
        assertThat(fixer.duties).isEmpty();
        assertThat(w.memories.stream().filter(m->"work".equals(m.topicId())).count()).isEqualTo(memories);
    }

    @Test void theFirstDaysMoodWearsOff(){
        var w=CompanionRules.join("settle-in","住客","Asia/Shanghai",start,true);
        CompanionRules.advance(w,start.plusSeconds(6));
        assertThat(ResidentSimulation.state(w,"scholar").mood).startsWith("刚搬来");
        w.simulatedAt=start.plusSeconds(7*3600);
        CompanionRules.advance(w,start.plusSeconds(7*3600+6));
        assertThat(ResidentSimulation.state(w,"scholar").mood).isEqualTo("如常");
    }

    /** A duty whose place and action no decision can ever offer is a promise the world cannot keep. */
    @Test void everyDutyCanBeChosenDuringItsOwnHours(){
        List<String> missing=new ArrayList<>();
        for(var entry:ResidentDuties.SEEDS.entrySet()){
            String id=entry.getKey();
            for(Duty d:entry.getValue().duties()){
                Instant at=start.minusSeconds(8*3600).plusSeconds(60L*((d.startMinute+d.endMinute)/2)); // local midnight + midpoint
                var w=CompanionRules.join("duty-"+id+"-"+d.startMinute,"住客","Asia/Shanghai",at,true);
                CompanionRules.advance(w,at.plusSeconds(6));
                int minute=ResidentDuties.localMinute(w,at);
                boolean cafeHours=minute>=w.cafeOpenMinute&&minute<w.cafeCloseMinute;
                w.cafeStatus=cafeHours?"open":"closed";
                var r=ResidentSimulation.state(w,id);r.plan=null;
                String elsewhere=d.place.equals("street")?TownPlaces.homeOf(id):"street";
                for(int i=0;i<w.residents.size();i++){var a=w.residents.get(i);if(id.equals(a.id()))w.residents.set(i,new Actor(a.id(),a.name(),a.role(),elsewhere,"idle","",a.x(),a.y(),at.plusSeconds(300)));}
                String modelPlace=d.place;
                boolean offered=ResidentSimulation.availableDecisionOptions(w,id,at.plusSeconds(6)).stream()
                    .anyMatch(o->o.action().equals(d.action)&&o.place().equals(modelPlace));
                if(!offered)missing.add(id+" "+ResidentDuties.clock(d.startMinute)+" "+d.action+"@"+d.place+"（"+d.what+"）");
            }
        }
        assertThat(missing).isEmpty();
    }
}
