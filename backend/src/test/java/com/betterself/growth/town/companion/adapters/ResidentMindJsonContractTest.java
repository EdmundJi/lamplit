package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class ResidentMindJsonContractTest {
    @Test void deepSeekCompatibleHttpContractReturnsFreeformEmojiWithoutReplacingIt()throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var requestBody=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            // A response produced before workAction/workTarget existed must remain readable.
            String content="{\"text\":\"我觉得那片云像一只忘了带壳的蜗牛。\",\"leave\":false,\"feeling\":\"好笑\",\"stance\":\"none\",\"adjustment\":null,\"evidenceIds\":[],\"emoji\":\"☁️🐌\"}";
            byte[] response=json.writeValueAsBytes(Map.of("model","deepseek-v4-flash","choices",List.of(Map.of("message",Map.of("content",content)))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var provider=new QwenHttpProvider(json,"http://127.0.0.1:"+server.getAddress().getPort(),"unit-test-provider-key","deepseek-v4-flash",Duration.ofSeconds(2));
            var mind=new QwenResidentMind(provider,json,"qwen",true);
            var now=Instant.parse("2026-09-08T06:00:00Z");var w=CompanionRules.join("emoji-contract","我","Asia/Shanghai",now,true);
            var self=ResidentSimulation.actor(w,"artist");var state=ResidentSimulation.state(w,"artist");
            var context=new ResidentMind.Context(w.id,"artist",state.revision,0,now,"14:00","sunny",self,state.goal,state.mood,state.thought,state.energy,state.social,state.relationships,w.memories.stream().filter(m->m.ownerId().equals("artist")).toList(),List.of(),List.of(),List.of(),List.of());
            var result=mind.generateTurn(new ResidentMind.DialogueRequest(context,"conversation",0,"operation","阿禾","随口聊聊"));
            assertThat(result.emoji()).isEqualTo("☁️🐌");assertThat(EmojiSequence.valid(result.emoji())).isTrue();
            assertThat(result.workAction()).isNull();assertThat(result.workTarget()).isNull();
            var body=json.readTree(requestBody.get());assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(body.path("messages").get(0).path("content").asText()).contains("emoji","workAction","workTarget");
        }finally{server.stop(0);}
    }

    @Test void dialogueContractCarriesPerceivedWorkStateAndParsesAnExplicitOffer() {
        var json=new ObjectMapper().findAndRegisterModules();var captured=new AtomicReference<QwenProvider.StructuredPrompt>();
        QwenProvider provider=new QwenProvider(){
            public StructuredResult generateStructured(StructuredPrompt prompt){
                captured.set(prompt);
                return new StructuredResult("{\"text\":\"高峰时我可以替你照看一会儿吧台，你觉得呢？\",\"leave\":false,\"feeling\":\"愿意试试\",\"stance\":\"none\",\"adjustment\":null,\"evidenceIds\":[],\"emoji\":\"☕\",\"workAction\":\"offer_assist\",\"workTarget\":\"owner\"}","fake","request",10,5,1);
            }
            public StreamMetadata stream(ChatPrompt prompt,Consumer<String> consumer){throw new UnsupportedOperationException();}
            public Classification classify(ClassificationPrompt prompt){throw new UnsupportedOperationException();}
        };
        var now=Instant.parse("2026-09-08T06:00:00Z");var w=CompanionRules.join("work-contract","我","Asia/Shanghai",now,true);
        var self=ResidentSimulation.actor(w,"artist");var state=ResidentSimulation.state(w,"artist");
        var arrangement=new ResidentMind.WorkArrangementView("work-42","delegate","cafe","owner","artist","proposed","想请你替我照看吧台",now.toString(),null,null);
        var context=new ResidentMind.Context("artist","14:00","sunny",ResidentMind.actorView(self),state.goal,List.of(),List.of(),
            ResidentMind.memoryViews(w.memories.stream().filter(m->m.ownerId().equals("artist")).toList()),ResidentMind.actorViews(List.of(ResidentSimulation.actor(w,"owner"))),List.of(),List.of(new ResidentMind.PersonHereView("owner","阿禾","熟，处得来",List.of())),List.of(new ResidentMind.KnownPlaceView("cafe","有六个独立窗边座位，适合安静工作",List.of("read","work"))),List.of(),List.of(),
            new ResidentMind.LifeIntentView(state.lifeIntent.id,state.lifeIntent.goalId,state.lifeIntent.purpose,state.lifeIntent.status,state.lifeIntent.formedAt.toString(),state.lifeIntent.updatedAt.toString(),state.lifeIntent.lastActedAt==null?null:state.lifeIntent.lastActedAt.toString()),
            new ResidentMind.LifeIntentView(state.careerIntent.id,state.careerIntent.goalId,state.careerIntent.purpose,state.careerIntent.status,state.careerIntent.formedAt.toString(),state.careerIntent.updatedAt.toString(),state.careerIntent.lastActedAt==null?null:state.careerIntent.lastActedAt.toString()),
            ResidentMind.planView(state.plan,now),List.of(arrangement),state.occupation,null,List.of("observe","rest"),"owner",List.of("我是咖啡馆当前经营者"),false,List.of(new ResidentMind.ServiceRequestView("drink-7","student","water","waiting","cafe")),"open",null,null,null,null);
        var result=new QwenResidentMind(provider,json,"qwen",true).generateTurn(new ResidentMind.DialogueRequest(context,"life-talk",0,"op-1","阿禾","眼前的生活和工作"));

        assertThat(result.workAction()).isEqualTo("offer_assist");assertThat(result.workTarget()).isEqualTo("owner");
        assertThat(captured.get().schemaJson()).contains("offer_assist","reject_work","end_work","workTarget");
        assertThat(captured.get().instruction()).contains("careerIntent","work-42","drink-7","不能代替对方同意","社会生活常识","六个独立窗边座位","我是咖啡馆当前经营者");
        assertThat(captured.get().instruction()).doesNotContain("life-talk","op-1","\"energy\":","\"social\":","\"relationships\":","\"importance\":","\"progress\":","\"x\":","\"y\":");
    }

    @Test void ordinaryDecisionSchemaDoesNotOfferAnOutOfConversationAgreementShortcut() {
        var captured=new AtomicReference<QwenProvider.StructuredPrompt>();
        QwenProvider provider=new QwenProvider(){
            public StructuredResult generateStructured(StructuredPrompt prompt){captured.set(prompt);return new StructuredResult("{\"action\":\"rest\",\"place\":\"home\",\"targetId\":null,\"reason\":\"今天太累，先休息\",\"speech\":\"\",\"evidenceIds\":[],\"projectTitle\":null,\"objectKind\":null}","fake","request",1,1,1);}
            public StreamMetadata stream(ChatPrompt prompt,Consumer<String> consumer){throw new UnsupportedOperationException();}
            public Classification classify(ClassificationPrompt prompt){throw new UnsupportedOperationException();}
        };
        var now=Instant.parse("2026-09-08T06:00:00Z");var w=CompanionRules.join("decision-contract","我","Asia/Shanghai",now,true);var state=ResidentSimulation.state(w,"artist");
        var context=new ResidentMind.Context(w.id,"artist",state.revision,0,now,"14:00","sunny",ResidentSimulation.actor(w,"artist"),state.goal,state.mood,state.thought,state.energy,state.social,state.relationships,List.of(),List.of(),List.of(),List.of(),List.of());

        new QwenResidentMind(provider,new ObjectMapper().findAndRegisterModules(),"qwen",true).decide(context);

        assertThat(captured.get().schemaJson()).doesNotContain("offer_assist","offer_delegate","offer_takeover","accept_work");
        assertThat(captured.get().schemaJson()).contains("continue","resume","sleep","request_drink","open_cafe","close_cafe","continue_home");
        assertThat(captured.get().instruction()).contains("只能在两人当面的结构化对话回合里协商","必须从availableActions选择","不要猜测或要求任何隐藏数值");
    }
}
