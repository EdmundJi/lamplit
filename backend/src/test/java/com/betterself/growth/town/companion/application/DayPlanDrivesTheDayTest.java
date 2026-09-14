package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

/** Smallville's loop, on our terms: the resident plans the day soon after waking, and every later
 * decision is told what they meant to be doing now and which option does it. The rules never move
 * them there - the decision does. Before this, a plan was three lines nobody read back. */
class DayPlanDrivesTheDayTest {
    @Test void awakeResidentPlansFirstAndTheDecisionIsShownThePlannedOption()throws Exception {
        var clock=new ResidentDirectorDialogueTest.MutableClock(Instant.parse("2026-09-14T01:05:00Z")); // 09:05 local
        var world=CompanionRules.join("day-plan-drives","住客","Asia/Shanghai",clock.instant(),true);
        world.conversations.clear();world.pendingOccasions.clear();world.pendingEncounters.clear();
        for(var r:world.residentStates)if(!Set.of("scholar","self").contains(r.id))
            r.plan=new Plan("park-"+r.id,"sleep",TownPlaces.homeOf(r.id),null,"睡着",clock.instant(),clock.instant().plusSeconds(3600));
        var scholar=ResidentSimulation.state(world,"scholar");
        scholar.plan=null;scholar.dayPlan=null;scholar.usualWakeMinute=6*60;scholar.usualSleepMinute=22*60;scholar.sleepScheduleSeeded=true;
        move(world,"scholar",TownPlaces.homeOf("scholar"),"idle","刚醒",clock.instant().plusSeconds(300));
        world.askedOccasions.put("scholar|change_work",clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()+"|junction");

        List<String> calls=new CopyOnWriteArrayList<>();
        List<List<String>> cuesSeen=new CopyOnWriteArrayList<>();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public boolean plansDays(){return true;}
            public DayPlanDraft planDay(DayPlanRequest request){
                if(request.perspective().residentId().equals("scholar"))calls.add("dayplan");
                return new DayPlanDraft(List.of(
                    new SegmentDraft("09:00","11:00","garden","observe","去花园坐坐，理理思路"),
                    new SegmentDraft("12:00","13:00","home","rest","回家歇午"),
                    new SegmentDraft("15:00","17:00","academy","read","去学堂翻旧书")),List.of());
            }
            public Decision decide(Context context){
                if(!context.residentId().equals("scholar"))return new Decision("none",null,null,"","",List.of(),null,null);
                calls.add("decision");cuesSeen.add(context.routineCues());
                String cue=context.routineCues().stream().filter(c->c.contains("选项")).findFirst().orElse(null);
                if(cue==null)return new Decision("none",null,null,"","",List.of(),null,null);
                String id=cue.substring(cue.indexOf("选项")+2,cue.indexOf("就是"));
                var option=context.decisionOptions().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();
                return new Decision(option.id(),option.action(),option.place(),option.roomId(),option.targetId(),"照早上想的去","",List.of(),null,null);
            }
        };
        var store=new ResidentDirectorDialogueTest.Store(world);
        var director=new ResidentDirector(store,mind,clock,100000,(u,d,t,i,o)->{},8,64,12,1);
        try{
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(System.nanoTime()<deadline&&!(scholar.plan!=null&&"garden".equals(scholar.plan.place()))){
                director.consider(1,world);Thread.sleep(5);
            }
        }finally{director.close();}

        assertThat(calls).first().isEqualTo("dayplan");
        assertThat(scholar.dayPlan.segments).extracting(s->s.place).containsExactly("garden",TownPlaces.homeOf("scholar"),"academy");
        assertThat(cuesSeen).anyMatch(cues->cues.stream().anyMatch(c->c.startsWith("今天这会儿的打算")&&c.contains("花园")));
        assertThat(scholar.plan).isNotNull();
        assertThat(scholar.plan.place()).isEqualTo("garden");
    }

    @Test void aDaytimeSleeperIsNotAskedUntilTheyWakeAndThenPlansFirst()throws Exception {
        var clock=new ResidentDirectorDialogueTest.MutableClock(Instant.parse("2026-09-14T03:17:00Z")); // 11:17 local
        var world=CompanionRules.join("day-sleeper","住客","Asia/Shanghai",clock.instant(),true);
        world.conversations.clear();world.pendingOccasions.clear();world.pendingEncounters.clear();
        for(var r:world.residentStates)if(!Set.of("librarian","self").contains(r.id)){
            r.plan=new Plan("park-"+r.id,"sleep",TownPlaces.homeOf(r.id),null,"睡着",clock.instant(),clock.instant().plusSeconds(7200));
            r.dayPlan=new DayPlan();r.dayPlan.day="2026-09-14";
        }
        var librarian=ResidentSimulation.state(world,"librarian");
        librarian.plan=new Plan("nap","sleep",TownPlaces.homeOf("librarian"),null,"睡一觉",clock.instant(),clock.instant().plusSeconds(5400));
        librarian.dayPlan=null;librarian.usualWakeMinute=390;librarian.usualSleepMinute=1380;librarian.sleepScheduleSeeded=true;
        move(world,"librarian",TownPlaces.homeOf("librarian"),"sleep","睡着",clock.instant().plusSeconds(5400));
        List<String> planned=new CopyOnWriteArrayList<>();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public boolean plansDays(){return true;}
            public DayPlanDraft planDay(DayPlanRequest request){planned.add(request.perspective().residentId());
                return new DayPlanDraft(List.of(new SegmentDraft("13:00","14:00","academy","read","回书角"),new SegmentDraft("15:00","16:00","academy","work","整理书"),new SegmentDraft("18:00","19:00","home","rest","回家歇着")),List.of());}
            public Decision decide(Context context){return new Decision("none",null,null,"","",List.of(),null,null);}
        };
        var director=new ResidentDirector(new ResidentDirectorDialogueTest.Store(world),mind,clock,100000,(u,d,t,i,o)->{},8,64,12,1);
        try{
            for(int i=0;i<20;i++){director.consider(1,world);Thread.sleep(5);}
            assertThat(planned).isEmpty();
            move(world,"librarian",TownPlaces.homeOf("librarian"),"idle","醒了",clock.instant().plusSeconds(300));librarian.plan=null;
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(System.nanoTime()<deadline&&planned.isEmpty()){director.consider(1,world);Thread.sleep(5);}
        }finally{director.close();}
        assertThat(planned).containsExactly("librarian");
    }

    @Test void beingThereDuringTheStretchMarksItDone(){
        var at=Instant.parse("2026-09-14T01:30:00Z"); // 09:30 local
        var world=CompanionRules.join("day-plan-done","住客","Asia/Shanghai",at,true);
        var scholar=ResidentSimulation.state(world,"scholar");
        assertThat(ResidentSimulation.applyDayPlan(world,"scholar",scholar.revision,List.of(
            new ResidentSimulation.PlannedSegment(9*60,11*60,"garden","observe","去花园坐坐"),
            new ResidentSimulation.PlannedSegment(12*60,13*60,"home","rest","回家歇午"),
            new ResidentSimulation.PlannedSegment(15*60,17*60,"academy","read","去学堂翻旧书")),List.of(),at)).isTrue();
        move(world,"scholar","garden","observe","在花园坐着",at.plusSeconds(600));
        world.simulatedAt=at;world.updatedAt=at;
        CompanionRules.advance(world,at.plusSeconds(12));
        assertThat(scholar.dayPlan.segments.getFirst().status).isEqualTo("done");
        assertThat(scholar.dayPlan.segments.get(1).status).isEqualTo("pending");
    }

    @Test void aPlanNamingSomebodyElsesHomeOrAnUnknownActionIsRefused(){
        var at=Instant.parse("2026-09-14T01:30:00Z");
        var world=CompanionRules.join("day-plan-refused","住客","Asia/Shanghai",at,true);
        var scholar=ResidentSimulation.state(world,"scholar");
        assertThat(ResidentSimulation.applyDayPlan(world,"scholar",scholar.revision,List.of(
            new ResidentSimulation.PlannedSegment(9*60,11*60,TownPlaces.homeOf("owner"),"observe","去阿禾家"),
            new ResidentSimulation.PlannedSegment(12*60,13*60,"home","rest","回家歇午"),
            new ResidentSimulation.PlannedSegment(15*60,17*60,"academy","read","去学堂")),List.of(),at)).isFalse();
        assertThat(ResidentSimulation.applyDayPlan(world,"scholar",scholar.revision,List.of(
            new ResidentSimulation.PlannedSegment(9*60,11*60,"garden","fly","飞一会儿"),
            new ResidentSimulation.PlannedSegment(12*60,13*60,"home","rest","回家歇午"),
            new ResidentSimulation.PlannedSegment(15*60,17*60,"academy","read","去学堂")),List.of(),at)).isFalse();
    }

    private static void move(CompanionWorld world,String id,String place,String activity,String label,Instant until){
        for(int i=0;i<world.residents.size();i++){var a=world.residents.get(i);if(id.equals(a.id()))world.residents.set(i,new Actor(a.id(),a.name(),a.role(),place,activity,label,a.x(),a.y(),until));}
    }
}
