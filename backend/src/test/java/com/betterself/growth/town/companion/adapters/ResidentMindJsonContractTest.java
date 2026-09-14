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
            ResidentMind.memoryViews(w.memories.stream().filter(m->m.ownerId().equals("artist")).toList()),List.of(),ResidentMind.actorViews(List.of(ResidentSimulation.actor(w,"owner"))),List.of(),List.of(new ResidentMind.PersonHereView("owner","阿禾","熟，处得来",List.of())),List.of(new ResidentMind.KnownPlaceView("cafe","有六个独立窗边座位，适合安静工作",List.of("read","work"))),List.of(),List.of(),
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
        // The decision schema's action enum is now scoped to this call's own context.availableActions()
        // (see QwenResidentMind.decideMetered's own comment: a schema that always advertised every
        // action that is EVER legal somewhere used to silently contradict the "pick only from
        // availableActions" instruction, and a model facing that contradiction followed the schema -
        // see the "continue"/"continue_home" rejection cluster this was written to fix). A broad
        // availableActions list here keeps this test's own assertions meaningful without pinning the
        // schema back to a static global set.
        // change_work/close_cafe/invite are gone from this list: ResidentSimulation.availableActions
        // never puts them in a real menu any more (they are asked on their own moment - see
        // Occasions - or, for invite, as react's own fourth answer), so a schema fixture that still
        // carried them was pinning behaviour nothing real ever exercises. none is added because it is
        // the one action availableActions always includes now.
        var broadAvailableActions=List.of("none","observe","rest","study","work","read","make","sleep","propose",
            "continue","resume","request_drink","open_cafe","continue_home","tend","join","away","create","help");
        var context=new ResidentMind.Context("artist","14:00","sunny",ResidentMind.actorView(ResidentSimulation.actor(w,"artist")),state.goal,
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            null,null,null,List.of(),
            state.occupation,null,broadAvailableActions,null,List.of(),false,List.of(),
            "open",null,null,null,null);

        new QwenResidentMind(provider,new ObjectMapper().findAndRegisterModules(),"qwen",true).decide(context);

        assertThat(captured.get().schemaJson()).doesNotContain("offer_assist","offer_delegate","offer_takeover","accept_work");
        // close_cafe dropped from this assertion for the same reason it was dropped above: it can
        // never actually be in availableActions, so schema-contains-close_cafe was never protecting
        // anything real. none takes its place - the one entry this schema must always carry.
        assertThat(captured.get().schemaJson()).contains("continue","resume","sleep","request_drink","open_cafe","continue_home","none");
        assertThat(captured.get().instruction()).contains("只能在两人当面的结构化对话回合里协商","availableActions是这次真能做的动作名","不要猜测或要求任何隐藏数值");
    }

    /** Direct regression test for the dominant real cause found behind the reject-rate exam: with a
     * resident waiting in the cafe on an already-requested drink (a "rest" plan), the cafe starting to
     * close withdraws "continue"/"continue_home" from availableActions with no substitute offered, and
     * the model - seeing a schema whose action enum always listed every action that is EVER legal
     * anywhere - kept answering "continue"/"continue_home" anyway, which ResidentDirector.applyDecision
     * then silently rejected every time because {@code c.availableActions().contains(decision.action())}
     * is the very first check it runs. Scoping the schema enum to this call's own availableActions (see
     * QwenResidentMind.decideMetered) cannot make the model choose differently, but it does stop the
     * schema itself from advertising an action this call can never actually apply. */
    @Test void decisionSchemaOmitsAnActionThisCallsAvailableActionsDoesNotOffer() {
        var captured=new AtomicReference<QwenProvider.StructuredPrompt>();
        QwenProvider provider=new QwenProvider(){
            public StructuredResult generateStructured(StructuredPrompt prompt){captured.set(prompt);return new StructuredResult("{\"action\":\"rest\",\"place\":\"cafe\",\"targetId\":null,\"reason\":\"继续等饮料\",\"speech\":\"\",\"evidenceIds\":[],\"projectTitle\":null,\"objectKind\":null}","fake","request",1,1,1);}
            public StreamMetadata stream(ChatPrompt prompt,Consumer<String> consumer){throw new UnsupportedOperationException();}
            public Classification classify(ClassificationPrompt prompt){throw new UnsupportedOperationException();}
        };
        var now=Instant.parse("2026-09-08T06:00:00Z");var w=CompanionRules.join("decision-menu-contract","我","Asia/Shanghai",now,true);var state=ResidentSimulation.state(w,"artist");
        // Exactly the "cafe is closing while I'm waiting on a drink" shape: "continue"/"continue_home"
        // are deliberately absent from availableActions this call, "rest" is the real substitute.
        var narrowAvailableActions=List.of("observe","rest","study","sleep","away");
        var context=new ResidentMind.Context("artist","14:00","sunny",ResidentMind.actorView(ResidentSimulation.actor(w,"artist")),state.goal,
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            null,null,null,List.of(),
            state.occupation,null,narrowAvailableActions,null,List.of(),false,List.of(),
            "closing",null,null,null,null);

        new QwenResidentMind(provider,new ObjectMapper().findAndRegisterModules(),"qwen",true).decide(context);

        String actionEnum=captured.get().schemaJson();
        assertThat(actionEnum).doesNotContain("continue_home").doesNotContain("\"continue\"").doesNotContain("propose").doesNotContain("tend");
        assertThat(actionEnum).contains("\"observe\"","\"rest\"","\"study\"","\"sleep\"","\"away\"");
        assertThat(captured.get().instruction()).contains("这次没列出的就是真的做不到","这时改选实际列出的选项");
    }
}
