package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class CompanionRulesTest {
    final Instant now=Instant.parse("2026-09-08T02:00:00Z");
    CompanionWorld world(){return CompanionRules.join("world-1","我","Asia/Shanghai",now);}
    @Test void independentWorldsAndDuplicateAdvance(){
        var a=world();var b=world();CompanionRules.advance(a,now.plusSeconds(300));
        long revision=a.revision;int diary=a.diary.size(),memories=a.memories.size();
        CompanionRules.advance(a,now.plusSeconds(300));
        assertThat(a.revision).isEqualTo(revision);assertThat(a.diary).hasSize(diary);assertThat(a.memories).hasSize(memories);
        assertThat(b.revision).isEqualTo(1);assertThat(b.residents).hasSize(4);
    }
    @Test void explicitFocusDefersPassingIdeaAndCancelPreventsLateExecution(){
        var w=world();
        CompanionRules.submit(w,new CompanionWorld.Intent("focus-01","focus","explicit","private-task",25,now),now);
        CompanionRules.submit(w,new CompanionWorld.Intent("flowers-01","flowers","passing",null,25,now),now);
        CompanionRules.advance(w,now.plusSeconds(300));assertThat(w.avatar.activity()).isEqualTo("focus");assertThat(w.intents.get(1).status).isEqualTo("pending");
        CompanionRules.cancel(w,"flowers-01",now.plusSeconds(301));
        CompanionRules.advance(w,now.plusSeconds(1600));assertThat(w.focus).isNull();assertThat(w.intents.get(0).status).isEqualTo("done");
        assertThat(w.intents.get(1).status).isEqualTo("cancelled");assertThat(w.avatar.activity()).isEqualTo("water");
        assertThat(w.memories).noneMatch(m->m.text().contains("private-task"));
    }
    @Test void explicitSupersedesFocusAndReplayDoesNotRestartIt(){
        var w=world();var focus=new CompanionWorld.Intent("focus-01","focus","explicit","task",25,now);
        CompanionRules.submit(w,focus,now);CompanionRules.submit(w,new CompanionWorld.Intent("rest-01","rest","explicit",null,25,now),now);
        assertThat(w.focus).isNull();assertThat(focus.status).isEqualTo("cancelled");
        CompanionRules.submit(w,new CompanionWorld.Intent("focus-01","focus","explicit","task",25,now),now);
        assertThat(w.focus).isNull();assertThat(w.intents).hasSize(2);
    }
    @Test void offlineRecoveryIsBoundedAndTimezoneAllowsDifferentResidentHabits(){
        var w=world();int memories=w.memories.size(),events=w.events.size();
        CompanionRules.advance(w,now.plus(Duration.ofDays(40)));
        assertThat(w.memories.size()-memories).isLessThanOrEqualTo(16);
        assertThat(w.events.size()-events).isLessThanOrEqualTo(12);
        assertThat(w.offlineSummary).isNotBlank();
        var tokyo=CompanionRules.join("a","我","Asia/Tokyo",Instant.parse("2026-09-08T16:00:00Z"));
        assertThat(tokyo.period).isEqualTo("night");
        assertThat(tokyo.residents).anyMatch(r->r.place().equals("home"));
        assertThat(tokyo.residents).anyMatch(r->r.place().equals("cafe"));
    }
    @Test void aModelCannotActOnCancelledIntentOrSomeoneElsesEvidence(){
        var w=world();var r=ResidentSimulation.state(w,"owner");long revision=r.revision;
        var mine=w.memories.stream().filter(m->m.ownerId().equals("owner")).findFirst().orElseThrow();
        var theirs=w.memories.stream().filter(m->m.ownerId().equals("gardener")).findFirst().orElseThrow();
        assertThat(ResidentSimulation.applyDecision(w,r.id,revision,w.intentRevision,"cafe","observe",null,"先听听对方", "我想先听听你的想法。",java.util.List.of(theirs.id()),now)).isFalse();
        CompanionRules.submit(w,new CompanionWorld.Intent("thought-01","walk","passing",null,25,now),now);
        long intentRevision=w.intentRevision;CompanionRules.cancel(w,"thought-01",now);
        assertThat(ResidentSimulation.applyDecision(w,r.id,revision,intentRevision,"cafe","observe",null,"先听听对方", "我想先听听你的想法。",java.util.List.of(mine.id()),now)).isFalse();
    }
    @Test void modelProposalIsAnIdeaUntilSomeoneActuallyWorksOnIt(){
        var w=world();var r=ResidentSimulation.state(w,"artist");
        var evidence=w.memories.stream().filter(m->m.ownerId().equals(r.id)).findFirst().orElseThrow();
        assertThat(ResidentSimulation.proposeDecision(w,r.id,r.revision,w.intentRevision,"garden","画一张雨滴的地图","poster","想把刚听到的故事画下来",java.util.List.of(evidence.id()),now)).isTrue();
        var proposal=w.projects.getLast();assertThat(proposal.status).isEqualTo("idea");assertThat(proposal.progress).isZero();
        assertThat(w.objects).noneMatch(o->proposal.id.equals(o.projectId()));
    }
    @Test void retainedMemoriesKeepTheirEvidenceAvailableAfterAnHour(){
        var w=world();
        for(int second=6;second<=3600;second+=6)CompanionRules.advance(w,now.plusSeconds(second));
        var ids=w.memories.stream().map(CompanionWorld.Memory::id).collect(java.util.stream.Collectors.toSet());
        assertThat(w.memories).allSatisfy(memory->assertThat(ids).containsAll(memory.evidenceIds()));
    }

    @Test void invitationsDoNotLoopAndReflectionsAreSpecificAndUnique(){
        var w=world();
        java.util.Map<String,java.time.Instant> previous=new java.util.HashMap<>();
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int second=6;second<=2400;second+=6){
            CompanionRules.advance(w,now.plusSeconds(second));
            for(var conversation:w.conversations)if(seen.add(conversation.id)){
                String pair=conversation.participantIds.stream().sorted().collect(java.util.stream.Collectors.joining(":"));
                String key=conversation.topicId+":"+pair;
                var prior=previous.put(key,conversation.startedAt);
                if(prior!=null)assertThat(java.time.Duration.between(prior,conversation.startedAt).getSeconds()).isGreaterThanOrEqualTo(900);
            }
        }
        var reflections=w.memories.stream().filter(m->m.sourceType().equals("reflection")).toList();
        assertThat(reflections).isNotEmpty();
        assertThat(reflections.stream().map(m->m.ownerId()+":"+m.text()).toList()).doesNotHaveDuplicates();
        assertThat(reflections).noneMatch(m->m.text().equals("我开始觉得，可以把自己的小愿望交给邻居们一起想；但这只是我现在的感觉。"));
    }
    @Test void duplicateSavedReflectionsAreCollapsedWithoutBreakingEvidence(){
        var w=world();
        w.memories.add(new CompanionWorld.Memory("old-a","owner","owner","reflection",now,"同一个旧看法","reading-night",java.util.List.of(),8));
        w.memories.add(new CompanionWorld.Memory("old-b","owner","owner","reflection",now,"同一个旧看法","reading-night",java.util.List.of(),8));
        w.memories.add(new CompanionWorld.Memory("citation","owner","owner","observed",now,"保留来源","reading-night",java.util.List.of("old-b"),5));
        CompanionRules.advance(w,now.plusSeconds(6));
        assertThat(w.memories.stream().filter(m->m.text().equals("同一个旧看法"))).hasSize(1);
        assertThat(w.memories.stream().filter(m->m.id().equals("citation")).findFirst().orElseThrow().evidenceIds()).containsExactly("old-a");
    }

}
