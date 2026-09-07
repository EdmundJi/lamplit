package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.betterself.growth.town.companion.domain.ConversationLifecycle.*;

class ConversationLifecycleTest {
    final Instant now=Instant.parse("2026-09-08T06:00:00Z");
    CompanionWorld world(){return CompanionRules.join("dialogue-world","住客","Asia/Shanghai",now,true);}
    CompanionWorld.Conversation active(CompanionWorld w){return w.conversations.stream().filter(c->c.mode.equals("model")&&c.status.equals("active")).findFirst().orElseThrow();}
    Utterance say(String text,boolean leave,String stance){return new Utterance(text,leave,"有一点期待",stance,null,List.of());}
    @Test void modelOwnsEveryTurnWithOperationIdentityAndAlternatingSpeakers(){
        var w=world();var c=active(w);assertThat(c.turns).isEmpty();
        var first=reserveTurn(w,c,now);assertThat(first.speakerId()).isEqualTo("owner");
        tick(w,c,now.plusSeconds(12));assertThat(c.turns).isEmpty();
        assertThat(applyTurn(w,first,say("我想请你画的不是海报，是你心里小街的一扇窗。",false,"none"),now.plusSeconds(13))).isTrue();
        assertThat(applyTurn(w,first,say("重复迟到的第一句",false,"none"),now.plusSeconds(14))).isFalse();
        assertThat(reserveTurn(w,c,now.plusSeconds(15))).isNull();
        var second=reserveTurn(w,c,now.plusSeconds(20));assertThat(second.speakerId()).isEqualTo("artist");
        assertThat(applyTurn(w,second,say("那我愿意帮忙，不过窗里要留一点没画完的地方。",false,"accept"),now.plusSeconds(21))).isTrue();
        assertThat(c.turns).extracting(CompanionWorld.Turn::source).containsExactly("model","model");
        assertThat(c.turns.getFirst().text()).contains("一扇窗");
        assertThat(ResidentSimulation.state(w,"artist").goal).isEqualTo(c.topicId);
        assertThat(c.stage).isEqualTo(1); // The legacy template stage never drives generated dialogue.
    }
    @Test void cancelledOrOutOfRangeRepliesCannotBecomeSpeech(){
        var w=world();var c=active(w);var op=reserveTurn(w,c,now);
        w.intentRevision++;
        assertThat(applyTurn(w,op,say("这句不该落地",false,"none"),now.plusSeconds(1))).isFalse();assertThat(c.turns).isEmpty();
        failTurn(w,op,now.plusSeconds(2));assertThat(c.mode).isEqualTo("fallback");
        var another=world();var d=active(another);var pending=reserveTurn(another,d,now);
        ResidentSimulation.replaceActor(another,"artist","home","rest","已经离开",now.plusSeconds(50));
        assertThat(applyTurn(another,pending,say("对方不在却听到了",false,"none"),now.plusSeconds(1))).isFalse();
        assertThat(d.turns).isEmpty();
    }
    @Test void timeoutReleasesBothPeopleWithoutOverwritingTheirRealWords(){
        var w=world();var c=active(w);var first=reserveTurn(w,c,now);
        assertThat(applyTurn(w,first,say("我记得那次雨停以后，你还在给花盆搬家。",false,"none"),now.plusSeconds(1))).isTrue();
        var second=reserveTurn(w,c,now.plusSeconds(8));
        tick(w,c,now.plusSeconds(54));assertThat(c.mode).isEqualTo("fallback");
        tick(w,c,now.plusSeconds(63));assertThat(c.status).isEqualTo("ended");
        assertThat(c.turns.getFirst().text()).contains("花盆搬家");assertThat(c.turns.getFirst().source()).isEqualTo("model");
        assertThat(c.turns.getLast().source()).isEqualTo("rules");
        assertThat(applyTurn(w,second,say("迟到了也不能插回来",false,"none"),now.plusSeconds(64))).isFalse();
        assertThat(c.summarizedParticipants).containsExactlyInAnyOrder("owner","artist");
    }
    @Test void eachRecollectionUsesOnlyItsOwnersActualConversationEvidence(){
        var w=world();var c=active(w);var first=reserveTurn(w,c,now);
        applyTurn(w,first,say("今天先聊到这里吧，我想留一杯茶给晚归的人。",true,"none"),now.plusSeconds(1));
        assertThat(c.status).isEqualTo("ended");
        var op=reserveSummary(w,c,"owner",now.plusSeconds(2));
        var wrong=c.turnMemoryIds.get("artist").getFirst();
        assertThat(applySummary(w,op,new Recollection("我记住了这次谈话。","安静",List.of(wrong)),now.plusSeconds(3))).isFalse();
        var own=c.turnMemoryIds.get("owner").getFirst();
        assertThat(applySummary(w,op,new Recollection("我说想给晚归的人留一杯茶。话说出口，才发现自己期待的是有人愿意坐下来。","期待",List.of(own)),now.plusSeconds(4))).isTrue();
        var otherOp=reserveSummary(w,c,"artist",now.plusSeconds(5));
        assertThat(applySummary(w,otherOp,new Recollection("我听见阿禾想为晚归的人留茶，觉得这件小事比一张热闹海报更像她。","亲近",List.of(wrong)),now.plusSeconds(6))).isTrue();
        assertThat(c.recollectionSources).containsEntry("owner","model").containsEntry("artist","model");
        assertThat(w.memories.stream().filter(m->m.sourceId().equals(c.id))).allMatch(m->m.evidenceIds().stream().allMatch(id->w.memories.stream().anyMatch(source->source.id().equals(id)&&source.ownerId().equals(m.ownerId()))));
    }
    @Test void aFuturePromiseNeverStartsWorkImmediatelyEvenIfTheModelLabelsItAccept(){
        var w=world();var c=active(w);var first=reserveTurn(w,c,now);
        applyTurn(w,first,say("想听听你对海报的主意。",false,"none"),now.plusSeconds(1));
        var previousGoal=ResidentSimulation.state(w,"artist").goal;
        var second=reserveTurn(w,c,now.plusSeconds(8));
        assertThat(applyTurn(w,second,say("我明天白天再开始画，今天先回去了。",true,"accept"),now.plusSeconds(9))).isTrue();
        assertThat(c.turns.getLast().text()).isEqualTo("我明天白天再开始画，今天先回去了。");
        assertThat(ResidentSimulation.state(w,"artist").goal).isEqualTo(previousGoal);
        assertThat(c.status).isEqualTo("ended");
    }

    @Test void fallbackRecollectionDoesNotAttributeYourOwnOnlyLineToTheOtherPerson(){
        var w=world();var c=active(w);var op=reserveTurn(w,c,now);
        failTurn(w,op,now.plusSeconds(1));tick(w,c,now.plusSeconds(10));
        var speaker=c.turns.getFirst().speakerId();
        assertThat(w.memories.stream().filter(m->m.ownerId().equals(speaker)&&m.sourceId().equals(c.id)))
            .isNotEmpty().allMatch(m->m.text().contains("我当时说")&&!m.text().contains("我记得对方说"));
    }

}
