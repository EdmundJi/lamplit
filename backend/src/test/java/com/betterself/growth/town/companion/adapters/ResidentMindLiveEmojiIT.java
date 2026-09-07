package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.ai.QwenHttpProvider;
import com.betterself.growth.town.companion.application.ResidentMind;
import com.betterself.growth.town.companion.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in live contract probe. Credentials stay in the environment, never JVM properties or logs. */
@EnabledIfEnvironmentVariable(named="COMPANION_LIVE_MODEL_TEST",matches="true")
class ResidentMindLiveEmojiIT {
    @Test void modelInventsItsOwnEmojiForTheActualUtterance()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var provider=new QwenHttpProvider(json,System.getenv("QWEN_BASE_URL"),System.getenv("QWEN_API_KEY"),System.getenv("QWEN_MODEL"),Duration.ofSeconds(35));
        var mind=new QwenResidentMind(provider,json,"qwen",true);
        var now=Instant.parse("2026-09-08T06:00:00Z");var w=CompanionRules.join("live-emoji-fixture","测试住客","Asia/Shanghai",now,true);
        var self=ResidentSimulation.actor(w,"artist");var state=ResidentSimulation.state(w,"artist");
        var transcript=List.of(new CompanionWorld.Turn("owner","刚才那片云像只忘带壳的蜗牛。你会给它画什么？",now,"rules"));
        var context=new ResidentMind.Context(w.id,"artist",state.revision,0,now,"14:00","sunny",self,state.goal,state.mood,state.thought,state.energy,state.social,state.relationships,w.memories.stream().filter(m->m.ownerId().equals("artist")).toList(),List.of(),List.of(),List.of(),transcript);
        var result=mind.generateTurn(new ResidentMind.DialogueRequest(context,"live-contract",1,"live-operation","阿禾","随口聊聊"));
        assertThat(result.text()).isNotBlank();assertThat(result.emoji()).isNotBlank();assertThat(EmojiSequence.valid(result.emoji())).isTrue();
        System.out.println("Live model contract: "+json.writeValueAsString(Map.of("model",System.getenv("QWEN_MODEL"),"text",result.text(),"emoji",result.emoji())));
    }
}
