package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResidentContextSnapshotTest {
    @Test void exportsTheCanonicalNormalAndTiredInputsForTheSameResidentAndTask()throws Exception {
        Instant now=Instant.parse("2026-09-09T06:00:00Z");
        var world=CompanionRules.join("context-snapshot","体验审阅","Asia/Shanghai",now,true);
        world.conversations.clear();world.serviceRequests.clear();
        world.objects.add(new CompanionWorld.WorldObject("ledger-draft","paper","cafe","账本草稿","progress-75",null));
        var owner=ResidentSimulation.state(world,"owner");
        owner.suspendedAction=null;
        owner.plan=new CompanionWorld.Plan("ledger-work","work","cafe",null,"把账本最后一页写完",now.minusSeconds(120),now.plusSeconds(600));
        for(int i=0;i<world.residents.size();i++){var actor=world.residents.get(i);if("owner".equals(actor.id()))world.residents.set(i,new CompanionWorld.Actor(actor.id(),actor.name(),actor.role(),"cafe","work","还在写账本",actor.x(),actor.y(),now.plusSeconds(600)));}
        var store=new SnapshotStore(world);var director=new ResidentDirector(store,new DisabledMind(),Clock.fixed(now,ZoneOffset.UTC));
        ResidentMind.Context normal,tired,nightRoutine;
        try{
            owner.energy=70;normal=director.perspective(world,"owner",now,List.of());
            owner.energy=15;tired=director.perspective(world,"owner",now,List.of());
            Instant night=Instant.parse("2026-09-09T15:30:00Z");owner.energy=70;
            owner.plan=new CompanionWorld.Plan("ledger-work","work","cafe",null,"把账本最后一页写完",night.minusSeconds(120),night.plusSeconds(600));
            nightRoutine=director.perspective(world,"owner",night,List.of());
        }finally{director.close();}

        assertThat(normal.currentPlan()).isEqualTo(tired.currentPlan());
        assertThat(normal.currentPlan().startedAt()).contains("T");
        assertThat(normal.currentPlan().remainingSeconds()).isEqualTo(600);
        assertThat(normal.memories()).allSatisfy(memory->assertThat(memory.at()).contains("T"));
        assertThat(normal.knownProjects()).allSatisfy(project->assertThat(project.stage()).isIn("刚开始","做了一些","进行中","大体完成","已经完成"));
        assertThat(normal.visibleObjects()).filteredOn(object->object.id().equals("ledger-draft")).extracting(ResidentMind.WorldObjectView::state).containsExactly("大体完成");
        assertThat(normal.salientPerceptions()).isEmpty();
        assertThat(tired.salientPerceptions()).containsExactly("已经很累，注意力很难维持");
        assertThat(nightRoutine.salientPerceptions()).isEmpty();
        assertThat(nightRoutine.routineCues()).containsExactly("到了我平常睡觉的时间");
        var json=new ObjectMapper().findAndRegisterModules();Path out=Path.of("target","context-contract");Files.createDirectories(out);
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("normal.json").toFile(),normal);
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("tired.json").toFile(),tired);
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("night-routine.json").toFile(),nightRoutine);
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("stub-decisions.json").toFile(),java.util.Map.of(
            "normal",new ResidentMind.Decision("continue","cafe",null,"这页还没写完，接着写","",List.of(),null,null),
            "tired",new ResidentMind.Decision("sleep","home",null,"眼睛已经睁不开了，先回去睡","",List.of(),null,null),
            "source","contract-stub"));
    }

    private static final class DisabledMind implements ResidentMind {
        public boolean enabled(){return false;}
        public Decision decide(Context context){throw new UnsupportedOperationException();}
    }
    private record SnapshotStore(CompanionWorld world) implements WorldStore {
        public CompanionWorld read(long userId){return world;}
        public CompanionWorld update(long userId,java.util.function.Supplier<CompanionWorld> initial,java.util.function.UnaryOperator<CompanionWorld> change){return change.apply(world);}
        public boolean ownsTask(long userId,String taskId){return false;}
        public String timezone(long userId){return world.timezone;}
    }
}
