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
        // Nothing is appended on the way out. This used to assert the opposite - that the last turn's
        // source was "rules" - back when a timeout made the rules say one of six canned lines on the
        // resident's behalf. Asserted as the property rather than as "no rules turn": what matters is
        // that the only thing in this conversation is what somebody actually said.
        assertThat(c.turns).hasSize(1);
        assertThat(c.turns).allMatch(t->t.source().equals("model"));
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

    // ---- recollection and reflection are two different clocks ---------------------------------

    private CompanionWorld.Conversation twoTurnConversation(CompanionWorld w,String place,String a,String b,Instant at){
        var c=new CompanionWorld.Conversation();
        c.id="conv-test-"+w.conversations.size()+"-"+at.toEpochMilli();
        c.place=place;c.topicId="life";c.status="active";c.mode="model";
        c.participantIds=new ArrayList<>(List.of(a,b));
        c.startedAt=at;c.updatedAt=at;
        c.turns.add(new CompanionWorld.Turn(a,"随口聊了两句家常",at,"model"));
        c.turns.add(new CompanionWorld.Turn(b,"嗯，是这样",at.plusSeconds(3),"model"));
        w.conversations.add(c);
        return c;
    }

    /** The exact bug reported: applySummary used to also stamp r.lastReflectionAt, so an ordinary
     * "I remember that exchange" recollection silently reset the same three-hour clock a real,
     * evidence-gated reflection needs to clear. This asserts the two remain independent while
     * everything else applySummary already did (thought, mood, the reflection-tier memory, the
     * bookkeeping) still happens exactly as before. */
    @Test void applyingAConversationSummaryUpdatesThoughtAndMoodButNeverAdvancesTheReflectionClock(){
        var w=world();w.conversations.clear();
        CompanionWorld.ResidentState artist=ResidentSimulation.state(w,"artist");
        Instant baseline=now.minusSeconds(1800);
        artist.lastReflectionAt=baseline;
        var c=twoTurnConversation(w,"cafe","artist","owner",now);
        ConversationLifecycle.finish(w,c,now.plusSeconds(6),"聊完了");
        var op=reserveSummary(w,c,"artist",now.plusSeconds(7));
        assertThat(op).isNotNull();
        var evidence=c.turnMemoryIds.get("artist");
        assertThat(evidence).isNotEmpty();
        assertThat(applySummary(w,op,new Recollection("我记得随口聊了几句家常。","平常",evidence),now.plusSeconds(8))).isTrue();
        assertThat(artist.thought).isEqualTo("我记得随口聊了几句家常。"); // unchanged: applySummary still writes the recollection as this resident's current thought
        assertThat(artist.mood).isEqualTo("平常"); // unchanged
        assertThat(artist.lastReflectionAt).isEqualTo(baseline); // changed: the reflection clock is no longer touched here
        assertThat(w.memories).anyMatch(m->m.ownerId().equals("artist")&&"reflection".equals(m.sourceType())&&m.text().contains("随口聊了几句家常"));
    }

    /** The scenario the task asks for directly: a resident who talks far more often than once every
     * three hours must still, eventually, actually reach the reflection threshold. Before the fix
     * above, every one of these frequent summaries would have pushed lastReflectionAt forward to
     * "now", so the three-hour gap needsReflection requires could never accumulate - a chatty
     * resident could go a whole simulated day without ever being able to reflect on anything. */
    @Test void aResidentWhoTalksOftenStillEventuallyReachesTheReflectionThreshold(){
        var w=world();w.conversations.clear();
        CompanionWorld.ResidentState artist=ResidentSimulation.state(w,"artist");
        Instant baseline=now;
        artist.lastReflectionAt=baseline;
        // Isolate this test's own accounting from whatever the warm start already seeded.
        w.memories=new ArrayList<>(w.memories.stream().filter(m->!(m.ownerId().equals("artist")&&m.at().isAfter(baseline))).toList());
        Instant at=baseline;
        for(int i=0;i<4;i++){
            at=at.plusSeconds(3600); // a fresh conversation every hour - four times more often than the 3h reflection gap
            var c=twoTurnConversation(w,"cafe","artist","owner",at);
            ConversationLifecycle.finish(w,c,at.plusSeconds(6),"聊完了");
            var op=reserveSummary(w,c,"artist",at.plusSeconds(7));
            assertThat(applySummary(w,op,new Recollection("又聊了几句家常。","平常",c.turnMemoryIds.get("artist")),at.plusSeconds(8))).isTrue();
            // Confirms the fix holds across repeated summaries, not just a single one.
            assertThat(artist.lastReflectionAt).as("round %d",i).isEqualTo(baseline);
        }
        assertThat(ResidentSimulation.needsReflection(w,"artist",at.plusSeconds(9))).isTrue();
    }

    @Test void fallbackRecollectionDoesNotAttributeYourOwnOnlyLineToTheOtherPerson(){
        // One real turn, and then the model goes away before anyone answers. The setup used to be
        // "reserve a turn, fail it, let the rules speak" - that produced a single line without the
        // model ever being involved, which is no longer a thing that can happen (see
        // ConversationLifecycle's fallback branch). The property under test is unchanged: when the
        // only line in the conversation is your own, your recollection must say 我当时说, not
        // attribute it to the person who never got to answer.
        var w=world();var c=active(w);var op=reserveTurn(w,c,now);
        assertThat(applyTurn(w,op,say("我记得那次雨停以后，你还在给花盆搬家。",false,"none"),now.plusSeconds(1))).isTrue();
        var unanswered=reserveTurn(w,c,now.plusSeconds(8));
        failTurn(w,unanswered,now.plusSeconds(9));tick(w,c,now.plusSeconds(20));
        assertThat(c.turns).hasSize(1);
        var speaker=c.turns.getFirst().speakerId();
        assertThat(w.memories.stream().filter(m->m.ownerId().equals(speaker)&&m.sourceId().equals(c.id)))
            .isNotEmpty().allMatch(m->m.text().contains("我当时说")&&!m.text().contains("我记得对方说"));
    }


    // ---- rule-level exit: a speaker stuck on placeholder replies (docs/04-decisions.md 2026-09-14) --

    /** The exact live transcript that reported this bug: 顾雁 asks something real, 时安 (reading) only
     * ever answers with a token grunt, and the model kept the exchange going because ending it used to
     * rely solely on leave=true. isTokenAcknowledgement is the rule that would have caught this without
     * the model's cooperation - tested here against every line from that transcript, not a paraphrase. */
    @Test void isTokenAcknowledgementMatchesTheExactLiveTranscript(){
        assertThat(isTokenAcknowledgement("嗯。")).as("bare filler").isTrue();
        assertThat(isTokenAcknowledgement("（翻过一页，没抬头）嗯。")).as("stage direction plus filler").isTrue();
        assertThat(isTokenAcknowledgement("（低头翻书，没抬头）嗯。")).as("different stage direction, same filler").isTrue();
        assertThat(isTokenAcknowledgement("行")).as("bare word, no punctuation at all").isTrue();
        assertThat(isTokenAcknowledgement("好的")).as("two-character bare word").isTrue();
        assertThat(isTokenAcknowledgement("老谭那书……是讲哪方面的？")).as("a real question, never a token ack").isFalse();
        assertThat(isTokenAcknowledgement("好，那我先安静待会儿。")).as("a full sentence, not a placeholder").isFalse();
        // ASCII parentheses must strip exactly like full-width （）.
        assertThat(isTokenAcknowledgement("(翻过一页，没抬头)嗯。")).as("ASCII stage direction").isTrue();
        assertThat(isTokenAcknowledgement(null)).isFalse();
        // A short reply that still asks something is never a token ack, however few characters remain.
        assertThat(isTokenAcknowledgement("行？")).as("short but a real question").isFalse();
    }

    @Test void isNearDuplicateCatchesTheSameLineRepeatedAcrossStageDirections(){
        assertThat(isNearDuplicate("（翻过一页，没抬头）嗯。","（翻过一页，没抬头）嗯。")).as("literally repeated").isTrue();
        assertThat(isNearDuplicate("（翻过一页，没抬头）嗯。","（低头翻书，没抬头）嗯。")).as("same filler under a different stage direction").isTrue();
        assertThat(isNearDuplicate("好，那我先安静待会儿。","老谭那书……是讲哪方面的？")).as("genuinely different content").isFalse();
        assertThat(isNearDuplicate("嗯。",null)).isFalse();
    }

    /** The live transcript reproduced turn by turn through the real applyTurn pipeline, with a fake mind
     * that never sets leave=true - so the only thing that can end this conversation is the rule itself.
     * 时安's own last two turns (turn 2 and turn 4) are both token acknowledgements, so the conversation
     * must end right there, exactly like a leave=true would have, before 顾雁's turn 5 ("老谭那书……是讲哪
     * 方面的？") is ever reached. */
    @Test void aSpeakerStuckOnTwoTokenAcknowledgementsEndsTheConversationLikeLeaveTrueWould(){
        var w=world();var c=active(w);
        var turn1=reserveTurn(w,c,now);
        assertThat(applyTurn(w,turn1,say("好，那我先安静待会儿。",false,"none"),now.plusSeconds(1))).isTrue();
        var turn2=reserveTurn(w,c,now.plusSeconds(7));
        assertThat(applyTurn(w,turn2,say("（翻过一页，没抬头）嗯。",false,"none"),now.plusSeconds(8))).isTrue();
        assertThat(c.status).as("one placeholder reply alone does not end anything").isEqualTo("active");
        var turn3=reserveTurn(w,c,now.plusSeconds(14));
        // The other speaker's own second turn is also a token ack, but it is not the SAME speaker's
        // last two - the rule must not fire on turn3 here.
        assertThat(applyTurn(w,turn3,say("（低头翻书，没抬头）嗯。",false,"none"),now.plusSeconds(15))).isTrue();
        assertThat(c.status).as("a different speaker's placeholder reply does not end it either").isEqualTo("active");
        var turn4=reserveTurn(w,c,now.plusSeconds(21));
        assertThat(applyTurn(w,turn4,say("（翻过一页，没抬头）嗯。",false,"none"),now.plusSeconds(22))).isTrue();
        assertThat(c.status).as("this speaker's own last two turns are both token acks").isEqualTo("ended");
        assertThat(c.endReason).as("distinguishable from an ordinary leave=true goodbye").isEqualTo("对方接连只是应一声，不再多问，各自去忙");
        assertThat(c.turns).hasSize(4);
        // Turn 5 (the real question the live transcript still asked) must never be reachable: the
        // conversation is over, so a fifth reservation cannot be made.
        assertThat(reserveTurn(w,c,now.plusSeconds(30))).isNull();
        // Same guarantee any other ending already carries (ResidentLifeTest.someoneWhoJustTalkedIsLeftAloneForAWhile):
        // finish() stamps lastSocialAt for both participants, so ResidentSimulation.greetable blocks an
        // immediate re-pairing of this exact pair for SOCIAL_RECOVERY_SECONDS regardless of how the
        // conversation ended - a rule-ended chat gets no less cooldown than a leave=true one.
        for(String id:c.participantIds)assertThat(ResidentSimulation.state(w,id).lastSocialAt).isEqualTo(now.plusSeconds(22));
    }

    /** Two turns that are near-duplicates but each individually longer than the four-CJK-character
     * token-ack threshold must still end the conversation - isNearDuplicate is not redundant with
     * isTokenAcknowledgement. */
    @Test void repeatingTheSameLongerLineTwiceAlsoEndsTheConversation(){
        var w=world();var c=active(w);
        var turn1=reserveTurn(w,c,now);
        assertThat(applyTurn(w,turn1,say("今天天气不错，适合出门走走看看。",false,"none"),now.plusSeconds(1))).isTrue();
        var turn2=reserveTurn(w,c,now.plusSeconds(7));
        assertThat(applyTurn(w,turn2,say("（想着别的事）今天天气不错，适合出门走走看看。",false,"none"),now.plusSeconds(8))).isTrue();
        assertThat(c.status).isEqualTo("active");
        var turn3=reserveTurn(w,c,now.plusSeconds(14));
        assertThat(applyTurn(w,turn3,say("是啊，确实挺好的。",false,"none"),now.plusSeconds(15))).isTrue();
        assertThat(c.status).isEqualTo("active");
        var turn4=reserveTurn(w,c,now.plusSeconds(21));
        assertThat(applyTurn(w,turn4,say("今天天气不错，适合出门走走看看。",false,"none"),now.plusSeconds(22))).isTrue();
        assertThat(c.status).as("this speaker repeated their own earlier line verbatim").isEqualTo("ended");
        assertThat(c.endReason).isEqualTo("对方接连只是应一声，不再多问，各自去忙");
    }

    @Test void whenNobodyEverSpokeTheRulesDoNotSpeakForThem(){
        // The guarantee this file exists to protect, and the one that was quietly missing until
        // 2026-09-11: a conversation nobody managed to say anything in leaves *nothing* behind.
        //
        // It used to leave a great deal. The rules waited eight seconds and then said one of six
        // canned lines on the resident's behalf, and appendSpeech turned that single string into a
        // dialogue turn, a memory for the speaker ("我对X说：…"), a memory for the listener
        // ("X当面说：…"), that resident's visible activity, and - through fallbackSummary - a
        // reflection quoting it. So the model was not mistaking our sentence for something they
        // said; in their memory they really had said it, and it would answer questions about it.
        //
        // Two people who had never seen this repository each read a stretch of the town's life and
        // both ranked two of those six strings as the town's clearest rules.
        var w=world();var c=active(w);var op=reserveTurn(w,c,now);
        failTurn(w,op,now.plusSeconds(1));
        assertThat(c.mode).isEqualTo("fallback");
        tick(w,c,now.plusSeconds(10));

        assertThat(c.status).as("两个人还是各自走开了，谈话要结束").isEqualTo("ended");
        assertThat(c.turns).as("谁也没开口，就不该有任何一句话").isEmpty();
        for(String id:c.participantIds){
            assertThat(w.memories).as("%s 不该记得说过或听过任何话",id)
                .noneMatch(m->m.ownerId().equals(id)&&m.sourceId().equals(c.id));
            assertThat(ResidentSimulation.actor(w,id).activity()).as("%s 不该停在 talk 上",id).isNotEqualTo("talk");
        }
        assertThat(c.summarizedParticipants).as("没有话可回忆").isEmpty();
    }
}
