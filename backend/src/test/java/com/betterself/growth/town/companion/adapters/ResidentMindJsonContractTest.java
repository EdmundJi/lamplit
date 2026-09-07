package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenHttpProvider;
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
import static org.assertj.core.api.Assertions.*;

class ResidentMindJsonContractTest {
    @Test void deepSeekCompatibleHttpContractReturnsFreeformEmojiWithoutReplacingIt()throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var requestBody=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String content=json.writeValueAsString(new ConversationLifecycle.Utterance("我觉得那片云像一只忘了带壳的蜗牛。",false,"好笑","none",null,List.of(),"☁️🐌"));
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
            var body=json.readTree(requestBody.get());assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(body.path("messages").get(0).path("content").asText()).contains("emoji");
        }finally{server.stop(0);}
    }
}
