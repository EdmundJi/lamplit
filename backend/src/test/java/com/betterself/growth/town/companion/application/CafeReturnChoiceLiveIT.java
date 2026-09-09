package com.betterself.growth.town.companion.application;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.town.companion.adapters.QwenResidentMind;
import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Two controlled Qwen choices for the cafe-return regression; neither outcome is forced. */
@EnabledIfEnvironmentVariable(named="COMPANION_LIVE_MODEL_TEST",matches="true")
class CafeReturnChoiceLiveIT {
    @Test void qwenSeesBothTheRetainedOperatorsReturnPathAndAnOpenCafeAsAnOrdinaryPlace()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();String model=required("QWEN_MODEL");
        var provider=new QwenHttpProvider(json,required("QWEN_BASE_URL"),required("QWEN_API_KEY"),model,Duration.ofSeconds(60),Duration.ofSeconds(70),true,"qwen");
        var mind=new QwenResidentMind(provider,json,"qwen",true,"qwen",false,false,false);
        Instant morning=Instant.parse("2026-09-09T01:05:00Z");

        var pausedWorld=CompanionRules.join("controlled-retained-operator","体验审阅","Asia/Shanghai",morning,true);pausedWorld.conversations.clear();pausedWorld.serviceRequests.clear();
        pausedWorld.cafeOperatorId="owner";pausedWorld.cafeOperating=false;pausedWorld.cafeStatus="closed";
        var owner=ResidentSimulation.state(pausedWorld,"owner");owner.plan=null;owner.suspendedAction=null;move(pausedWorld,"owner","home-owner","idle","在家看着窗外",morning.plusSeconds(600));
        ResidentMind.Context retained=context(pausedWorld,"owner",morning);
        assertThat(retained.cafeScheduleCue()).isEqualTo("到了咖啡馆平常开门时间；目前经营暂停，门仍关着");
        assertThat(retained.cafeRoleFacts()).contains("我是咖啡馆当前经营者，经营权和吧台设备责任仍在我这里。");
        assertThat(retained.availableActions()).contains("open_cafe");
        var retainedChoice=mind.decideMetered(retained);assertValid(retained,retainedChoice,model);

        Instant openMorning=morning.plusSeconds(300);
        var openWorld=CompanionRules.join("controlled-open-cafe","体验审阅","Asia/Shanghai",openMorning,true);openWorld.conversations.clear();openWorld.serviceRequests.clear();
        openWorld.cafeOperatorId="owner";openWorld.cafeOperating=true;openWorld.cafeStatus="open";
        var student=ResidentSimulation.state(openWorld,"student");student.plan=null;student.suspendedAction=null;move(openWorld,"student","home-student","idle","在家想下一步",openMorning.plusSeconds(600));
        ResidentMind.Context visitor=context(openWorld,"student",openMorning);
        assertThat(visitor.knownPlaces()).filteredOn(place->place.id().equals("cafe")).singleElement().satisfies(place->assertThat(place.description()).contains("六个独立窗边座位","安静读书"));
        var visitorChoice=mind.decideMetered(visitor);assertValid(visitor,visitorChoice,model);

        Map<String,Object> report=new LinkedHashMap<>();report.put("provider","qwen");report.put("actualModel",retainedChoice.usage().model());report.put("reasoningTokens",retainedChoice.usage().reasoningTokens()+visitorChoice.usage().reasoningTokens());
        report.put("retainedOperatorInput",retained);report.put("retainedOperatorChoice",retainedChoice.value());report.put("ordinaryResidentInput",visitor);report.put("ordinaryResidentChoice",visitorChoice.value());
        Path out=Path.of("..","output","town-life-review","cafe-return");Files.createDirectories(out);json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("qwen-controlled.json").toFile(),report);
        System.out.println("Cafe return Qwen choices: "+json.writeValueAsString(Map.of("actualModel",retainedChoice.usage().model(),"reasoningTokens",report.get("reasoningTokens"),"retainedOperator",retainedChoice.value(),"ordinaryResident",visitorChoice.value())));
    }

    private static ResidentMind.Context context(CompanionWorld world,String residentId,Instant at){
        var director=new ResidentDirector(new SnapshotStore(world),new DisabledMind(),Clock.fixed(at,ZoneOffset.UTC));try{return director.perspective(world,residentId,at,List.of());}finally{director.close();}
    }
    private static void assertValid(ResidentMind.Context context,ResidentMind.Result<ResidentMind.Decision> result,String model){assertThat(context.availableActions()).contains(result.value().action());assertThat(result.usage().provider()).isEqualTo("qwen");assertThat(result.usage().model()).isEqualTo(model);assertThat(result.usage().reasoningContentPresent()).isFalse();assertThat(result.usage().reasoningTokens()).isZero();}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;}
    private static void move(CompanionWorld world,String id,String place,String activity,String label,Instant until){for(int i=0;i<world.residents.size();i++){var actor=world.residents.get(i);if(id.equals(actor.id()))world.residents.set(i,new CompanionWorld.Actor(actor.id(),actor.name(),actor.role(),place,activity,label,actor.x(),actor.y(),until));}}
    private static final class DisabledMind implements ResidentMind {public boolean enabled(){return false;}public Decision decide(Context context){throw new UnsupportedOperationException();}}
    private record SnapshotStore(CompanionWorld world) implements WorldStore {public CompanionWorld read(long id){return world;}public CompanionWorld update(long id,java.util.function.Supplier<CompanionWorld> initial,java.util.function.UnaryOperator<CompanionWorld> change){return change.apply(world);}public boolean ownsTask(long id,String task){return false;}public String timezone(long id){return world.timezone;}}
}
