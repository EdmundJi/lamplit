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

/** Two Qwen-only choices over the canonical qualitative context. No fallback provider is present. */
@EnabledIfEnvironmentVariable(named="COMPANION_LIVE_MODEL_TEST",matches="true")
class ResidentContextChoiceLiveIT {
    @Test void qwenChoosesFromNightRoutineAndDaytimeFatigueWithoutInternalGauges()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();String base=required("QWEN_BASE_URL"),key=required("QWEN_API_KEY"),model=required("QWEN_MODEL");
        var provider=new QwenHttpProvider(json,base,key,model,Duration.ofSeconds(60),Duration.ofSeconds(70),true,"qwen");
        var mind=new QwenResidentMind(provider,json,"qwen",true,"qwen",false,false,false);
        Instant day=Instant.parse("2026-09-09T06:00:00Z"),night=Instant.parse("2026-09-09T15:30:00Z");
        var world=CompanionRules.join("context-choice-live","体验审阅","Asia/Shanghai",day,true);world.conversations.clear();world.serviceRequests.clear();
        var artist=ResidentSimulation.state(world,"artist");artist.goal=null;artist.suspendedAction=null;
        move(world,"artist","home-artist","make","还在画窗边那张小稿",night.plusSeconds(900));
        var store=new SnapshotStore(world);var perspectiveDirector=new ResidentDirector(store,new DisabledMind(),Clock.fixed(day,ZoneOffset.UTC));
        ResidentMind.Context nightContext,tiredContext;
        try{
            artist.energy=70;artist.plan=new CompanionWorld.Plan("same-drawing","make","home-artist",null,"把窗边那张小稿收尾",night.minusSeconds(120),night.plusSeconds(900));
            nightContext=perspectiveDirector.perspective(world,"artist",night,List.of());
            artist.energy=15;artist.plan=new CompanionWorld.Plan("same-drawing","make","home-artist",null,"把窗边那张小稿收尾",day.minusSeconds(120),day.plusSeconds(900));
            tiredContext=perspectiveDirector.perspective(world,"artist",day,List.of());
        }finally{perspectiveDirector.close();}

        assertThat(nightContext.salientPerceptions()).isEmpty();assertThat(nightContext.routineCues()).containsExactly("到了我平常睡觉的时间");
        assertThat(tiredContext.salientPerceptions()).containsExactly("已经很累，注意力很难维持");assertThat(tiredContext.routineCues()).isEmpty();
        var nightChoice=mind.decideMetered(nightContext);var tiredChoice=mind.decideMetered(tiredContext);
        assertValid(nightContext,nightChoice,model);assertValid(tiredContext,tiredChoice,model);

        Map<String,Object> report=new LinkedHashMap<>();report.put("provider","qwen");report.put("actualModel",nightChoice.usage().model());
        report.put("reasoningTokens",nightChoice.usage().reasoningTokens()+tiredChoice.usage().reasoningTokens());
        report.put("nightRoutine",nightContext.routineCues());report.put("nightChoice",nightChoice.value());
        report.put("dayFatigue",tiredContext.salientPerceptions());report.put("dayChoice",tiredChoice.value());
        Path out=Path.of("target","context-contract");Files.createDirectories(out);json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("qwen-choices.json").toFile(),report);
        System.out.println("Qwen qualitative context choices: "+json.writeValueAsString(report));
    }

    @Test void qwenChoosesAgainAfterTheNightWorkItContinuedHasActuallyFinished()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();String base=required("QWEN_BASE_URL"),key=required("QWEN_API_KEY"),model=required("QWEN_MODEL");
        var provider=new QwenHttpProvider(json,base,key,model,Duration.ofSeconds(60),Duration.ofSeconds(70),true,"qwen");
        var mind=new QwenResidentMind(provider,json,"qwen",true,"qwen",false,false,false);
        Instant night=Instant.parse("2026-09-09T15:30:00Z");
        var world=CompanionRules.join("night-followup-live","体验审阅","Asia/Shanghai",night,true);world.conversations.clear();world.serviceRequests.clear();
        var artist=ResidentSimulation.state(world,"artist");artist.goal=null;artist.suspendedAction=null;artist.energy=70;
        artist.plan=new CompanionWorld.Plan("same-drawing","make","home-artist",null,"把窗边那张小稿收尾",night.minusSeconds(120),night.plusSeconds(900));
        move(world,"artist","home-artist","make","还在画窗边那张小稿",night.plusSeconds(900));
        assertThat(ResidentSimulation.applyDecision(world,"artist",artist.revision,world.intentRevision,"home","continue",null,"先把最后几笔补完","",List.of(),night)).isTrue();
        world.simulatedAt=night;world.updatedAt=night;Instant after=night.plusSeconds(901);CompanionRules.advance(world,after);
        assertThat(artist.plan).isNull();
        var store=new SnapshotStore(world);var perspectiveDirector=new ResidentDirector(store,new DisabledMind(),Clock.fixed(after,ZoneOffset.UTC));ResidentMind.Context context;
        try{context=perspectiveDirector.perspective(world,"artist",after,List.of());}finally{perspectiveDirector.close();}
        assertThat(context.currentPlan()).isNull();assertThat(context.salientPerceptions()).isEmpty();assertThat(context.routineCues()).containsExactly("到了我平常睡觉的时间");
        var choice=mind.decideMetered(context);assertValid(context,choice,model);
        Map<String,Object> report=new LinkedHashMap<>();report.put("provider","qwen");report.put("actualModel",choice.usage().model());report.put("reasoningContentPresent",choice.usage().reasoningContentPresent());report.put("reasoningTokens",choice.usage().reasoningTokens());report.put("input",context);report.put("choice",choice.value());
        Path out=Path.of("target","context-contract");Files.createDirectories(out);json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("qwen-night-after-completion.json").toFile(),report);
        System.out.println("Qwen after completed night work: "+json.writeValueAsString(Map.of("actualModel",choice.usage().model(),"reasoningTokens",choice.usage().reasoningTokens(),"choice",choice.value())));
    }

    private static void assertValid(ResidentMind.Context context,ResidentMind.Result<ResidentMind.Decision> result,String model){
        assertThat(context.availableActions()).contains(result.value().action());
        assertThat(result.usage().provider()).isEqualTo("qwen");assertThat(result.usage().model()).isEqualTo(model);
        assertThat(result.usage().reasoningContentPresent()).isFalse();assertThat(result.usage().reasoningTokens()).isZero();
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;}
    private static void move(CompanionWorld world,String id,String place,String activity,String label,Instant until){for(int i=0;i<world.residents.size();i++){var actor=world.residents.get(i);if(id.equals(actor.id()))world.residents.set(i,new CompanionWorld.Actor(actor.id(),actor.name(),actor.role(),place,activity,label,actor.x(),actor.y(),until));}}
    private static final class DisabledMind implements ResidentMind {public boolean enabled(){return false;}public Decision decide(Context context){throw new UnsupportedOperationException();}}
    private record SnapshotStore(CompanionWorld world) implements WorldStore {
        public CompanionWorld read(long userId){return world;}
        public CompanionWorld update(long userId,java.util.function.Supplier<CompanionWorld> initial,java.util.function.UnaryOperator<CompanionWorld> change){return change.apply(world);}
        public boolean ownsTask(long userId,String taskId){return false;}public String timezone(long userId){return world.timezone;}
    }
}
