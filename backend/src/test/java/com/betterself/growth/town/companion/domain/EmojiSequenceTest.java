package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class EmojiSequenceTest {
    @Test void acceptsUnicodeEmojiSequencesWithoutAThemeDictionary(){
        for(String value:List.of("🪼🪐🧩","👩🏽‍🎨","🏳️‍🌈","🇨🇳🇮🇸","1️⃣#️⃣*️⃣","🫆","👨‍👩‍👧‍👦 🌧️"))assertThat(EmojiSequence.valid(value)).as(value).isTrue();
        assertThat(EmojiSequence.valid(null)).isTrue();
    }
    @Test void rejectsTextControlsAndOverlongSequences(){
        for(String value:List.of(""," ","花","happy","☕ hello","🫖\n","🦋🪐🪁🧩","\u200d","<script>"))assertThat(EmojiSequence.valid(value)).as(value).isFalse();
    }
    @Test void storesTheModelsExactEmojiAndKeepsOldConstructorsCompatible(){
        Instant now=Instant.parse("2026-09-08T06:00:00Z");
        var w=CompanionRules.join("emoji-life","我","Asia/Shanghai",now,true);
        var conversation=w.conversations.stream().filter(c->c.mode.equals("model")&&c.status.equals("active")).findFirst().orElseThrow();
        var operation=ConversationLifecycle.reserveTurn(w,conversation,now);
        var reply=new ConversationLifecycle.Utterance("要是水母会收集星星，会不会把月亮也误装进去？",false,"好奇","none",null,List.of(),"🪼🪐");
        assertThat(ConversationLifecycle.applyTurn(w,operation,reply,now.plusSeconds(1))).isTrue();
        assertThat(conversation.turns.getLast().emoji()).isEqualTo("🪼🪐");
        assertThat(new CompanionWorld.Turn("owner","旧记录",now).emoji()).isNull();
        assertThat(new ConversationLifecycle.Utterance("旧接口",false,"平静","none",null,List.of()).emoji()).isNull();
    }
}
