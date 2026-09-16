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
        assertThat(b.revision).isEqualTo(1);assertThat(b.residents).hasSize(ResidentPersonas.all().size()+6);
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
        // Bounded per resident, not by a flat number: the 16/12 these were is what a town of six
        // produced, and docs/01 第二版 took the town to 25. What must stay true is that forty days away
        // does not produce forty days of catch-up - so the bound scales with how many people there are
        // and with nothing else. A regression that made recovery unbounded in TIME still fails here.
        int residents=w.residentStates.size();
        assertThat(w.memories.size()-memories).as("四十天不在，补算的量随人口有界，不随天数").isLessThanOrEqualTo(3*residents);
        assertThat(w.events.size()-events).as("同上").isLessThanOrEqualTo(2*residents);
        assertThat(w.offlineSummary).isNotBlank();
        var tokyo=CompanionRules.join("a","我","Asia/Tokyo",Instant.parse("2026-09-08T16:00:00Z"));
        assertThat(tokyo.period).isEqualTo("night");
        assertThat(tokyo.residents).anyMatch(r->TownPlaces.isHome(r.place()));
        assertThat(tokyo.cafeStatus).isEqualTo("closed");
        assertThat(tokyo.residents).noneMatch(r->r.place().equals("cafe"));
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
    @Test void aResidentsSleepDecisionUsesTheirOwnHomeNotASharedOne(){
        var start=Instant.parse("2026-09-08T14:50:00Z"); // 22:50 in Asia/Shanghai
        var w=CompanionRules.join("sleep-world","我","Asia/Shanghai",start,true);
        java.util.Map<String,String> sleepingAt=new java.util.HashMap<>();
        for(var r:w.residentStates){
            if(r.id.equals("self"))continue;
            assertThat(ResidentSimulation.applyDecision(w,r.id,r.revision,w.intentRevision,"home","sleep",null,"今晚想睡了",null,java.util.List.of(),start)).isTrue();
            sleepingAt.put(r.id,ResidentSimulation.actor(w,r.id).place());
        }
        assertThat(sleepingAt).isNotEmpty();
        // Each sleeper is in their own bedroom, never the single shared "home" the old bug produced.
        sleepingAt.forEach((id,place)->assertThat(place).isEqualTo(TownPlaces.homeOf(id)));
        // 22:50 is dark, and every one of these bedrooms' own lights is still off (LightService), so
        // "sleep" lands each of them on a short "开灯" switch_light activity first - step past it
        // before anybody has actually claimed a bed.
        ResidentSimulation.step(w,start.plusSeconds(130));
        // Two flat-mates deliberately share one address, so distinct *places* is no longer the claim.
        // What still has to hold - and is what the original four-in-one-bed bug actually violated -
        // is that no two sleepers are ever in the same bed.
        var beds=sleepingAt.keySet().stream().map(id->ResidentSimulation.state(w,id).positionId).toList();
        assertThat(beds).doesNotContainNull().doesNotHaveDuplicates();
    }
    @Test void aPlaceThatIsFullChangesWhatAResidentDoesInsteadOfSteppingOnSomeone(){
        // Per 04-decisions.md's "能站的地方都能去": standing/observing/passing through no longer
        // claims a named position at all (positionId just stays null), so it can never itself be
        // turned away for lack of room - only the handful of actions that still need a genuinely
        // owned, capacity-limited spot (a bed, the owner's counter, the student's window seat) can.
        // "study" is this test's stand-in for that: same full-cafe setup as before, but through an
        // action that still claims a seat, so "the whole place is genuinely full" is still reachable.
        var w=world();
        for(var c:w.conversations)if(c.participantIds.contains("gardener"))c.status="ended";
        // Stand the gardener in the cafe, which owns none of its two positions for them.
        for(int i=0;i<w.residents.size();i++){var a=w.residents.get(i);
            if(a.id().equals("gardener"))w.residents.set(i,new CompanionWorld.Actor(a.id(),a.name(),a.role(),"cafe","observe",a.label(),a.x(),a.y(),now.plusSeconds(200)));}
        TownPlaces.position(w,"cafe-worktable").capacity=0; // the shared table is out
        for(int index=2;index<=6;index++)TownPlaces.position(w,"cafe-window-"+index).capacity=0;
        TownPlaces.claim(w,"student","cafe","seat",now); // the window seat's real owner is using it
        var gardener=ResidentSimulation.state(w,"gardener");gardener.plan=null;
        boolean applied=ResidentSimulation.applyDecision(w,"gardener",gardener.revision,w.intentRevision,"cafe","study",null,"想去咖啡馆看看","",
            java.util.List.of(w.memories.stream().filter(m->m.ownerId().equals("gardener")).findFirst().orElseThrow().id()),now);
        assertThat(applied).isTrue();
        // The whole cafe is genuinely full: the gardener waits rather than being placed on top of anyone.
        assertThat(gardener.plan.action()).isEqualTo("wait");
        assertThat(ResidentSimulation.actor(w,"gardener").place()).isEqualTo("cafe");
        assertThat(gardener.positionId).isNull();
    }
    @Test void anOlderSaveWithoutPlacesOrAnAvatarStateIsRepairedOnTheNextAdvance(){
        var w=world();
        // Simulate a save written before this batch: no location/position catalog, no avatar state,
        // and a resident still parked at the old single shared "home".
        w.simulationVersion=2;w.locations.clear();w.positions.clear();
        w.residentStates.removeIf(r->r.id.equals("self"));
        for(int i=0;i<w.residents.size();i++){var a=w.residents.get(i);
            if(a.id().equals("student"))w.residents.set(i,new CompanionWorld.Actor(a.id(),a.name(),a.role(),"home","sleep","睡着了",a.x(),a.y(),now.plusSeconds(200)));}
        ResidentSimulation.state(w,"student").positionId=null;
        CompanionRules.advance(w,now.plusSeconds(6));
        assertThat(w.locations).isNotEmpty();
        assertThat(w.positions).isNotEmpty();
        assertThat(w.residentStates).anyMatch(r->r.id.equals("self"));
        // The bug this fixes: everyone piling into one literal "home" place.
        assertThat(ResidentSimulation.actor(w,"student").place()).isEqualTo("home-student");
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

    /** The old autopilot was a wall-clock wheel (epochSecond/120 % 8): once the user's own explicit
     * arrangement ran out, the avatar cycled through water/rest/walk/flowers/study every two minutes
     * regardless of what it had just been doing. The new base is the user's own last real request
     * (docs/04's "化身的 dayPlan 由用户自己的 intent 生成"), so it keeps doing the SAME thing across
     * repeated idle-fallback ticks instead of being reshuffled by the clock. */
    @Test void avatarContinuesTheUsersLastRealArrangementInsteadOfCyclingAWheel(){
        var w=world();
        // An explicit "walk" arrangement - the street bench is unowned, so nothing about seat
        // ownership contention can explain what happens to the position below.
        CompanionRules.submit(w,new CompanionWorld.Intent("walk-01","walk","explicit",null,25,now),now);
        assertThat(w.avatar.activity()).isEqualTo("walk");
        assertThat(w.avatar.place()).isEqualTo("street");
        String seat=ResidentSimulation.state(w,"self").positionId;
        assertThat(seat).isNotNull();
        assertThat(TownPlaces.position(w,seat).kind).isEqualTo("bench");
        // The explicit intent's own 120-second window elapses and the idle fallback takes over.
        CompanionRules.advance(w,now.plusSeconds(121));
        assertThat(w.avatar.activity()).as("still walking - the last real request, not a wheel phase").isEqualTo("walk");
        assertThat(ResidentSimulation.state(w,"self").positionId).as("same bench, no seat swap").isEqualTo(seat);
        // Well past the length of a full eight-phase wheel cycle (16 minutes) - the old autopilot
        // would have cycled through every other activity several times over by now.
        CompanionRules.advance(w,now.plusSeconds(1400));
        assertThat(w.avatar.activity()).isEqualTo("walk");
    }

    /** "喝口水不该换一张桌子" (docs/04): an activity that needs no owned, capacity-limited spot must
     * release whatever it was holding rather than have {@code TownPlaces.claim} hand out a random
     * other one just because it was asked with a null kind. */
    @Test void drinkingWaterReleasesTheSeatInsteadOfClaimingAnotherOne(){
        var w=world();
        CompanionRules.submit(w,new CompanionWorld.Intent("focus-01","focus","explicit","task",25,now),now);
        assertThat(ResidentSimulation.state(w,"self").positionId).as("the focus session claims a real seat").isNotNull();
        CompanionRules.advance(w,now.plusSeconds(1600)); // past the 25-minute focus session
        assertThat(w.avatar.activity()).isEqualTo("water");
        assertThat(ResidentSimulation.state(w,"self").positionId).as("released, not reseated at random").isNull();
    }

}
