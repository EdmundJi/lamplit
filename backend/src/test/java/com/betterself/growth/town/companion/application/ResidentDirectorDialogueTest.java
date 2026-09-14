package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.*;
import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;

class ResidentDirectorDialogueTest {
    private static ResidentDirector oneAtATime(WorldStore store,ResidentMind mind,Clock clock){
        return new ResidentDirector(store,mind,clock,100000,(userId,day,callType,inputTokens,outputTokens)->{},8,64,12,1);
    }
    @Test void routesEverySpeakerAndEachRecollectionThroughIndependentModelOperations()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("generated-dialogue","私密用户名字","Asia/Shanghai",clock.instant(),true);
        var c=world.conversations.stream().filter(t->t.mode.equals("model")&&t.status.equals("active")).findFirst().orElseThrow();
        var store=new Store(world);var turns=new AtomicInteger();var summaries=new AtomicInteger();
        List<String> speakers=Collections.synchronizedList(new ArrayList<>());
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){throw new AssertionError("A generated conversation must not go through plan-decision insertion");}
            public ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
                assertThat(store.transaction.get()).isFalse();
                assertThat(request.perspective().memories()).allMatch(m->m.ownerId().equals(request.perspective().residentId()));
                assertThat(request.perspective().toString()).doesNotContain("私密用户名字");
                speakers.add(request.perspective().residentId());int turn=turns.incrementAndGet();
                assertThat(request.perspective().conversation()).hasSize(turn-1);
                return new ConversationLifecycle.Utterance(turn==1?"我想留一张空白书签，让来的人写一句没说完的话。":"我愿意帮忙画书签。现在先去找纸，回头见。",turn==2,"期待",turn==2?"accept":"none",null,List.of());
            }
            public ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){
                assertThat(store.transaction.get()).isFalse();
                assertThat(request.transcript()).hasSize(2).allMatch(t->t.source().equals("model"));
                assertThat(request.conversationMemories()).allMatch(m->m.ownerId().equals(request.perspective().residentId()));
                summaries.incrementAndGet();
                return new ConversationLifecycle.Recollection("我记住了和"+request.partnerName()+"讨论的那张空白书签。还没做出来，但对方的话让我想认真试试。","期待",List.of(request.conversationMemories().getFirst().id()));
            }
        };
        var director=oneAtATime(store,mind,clock);
        try {
            driveUntil(director,1,world,()->c.turns.size()==1);
            clock.now=clock.now.plusSeconds(7);
            driveUntil(director,1,world,()->c.status.equals("ended"));
            driveUntil(director,1,world,()->c.summarizedParticipants.size()==2);
            assertThat(speakers).containsExactly("owner","artist");
            assertThat(turns).hasValue(2);assertThat(summaries).hasValue(2);
            assertThat(c.turns).extracting(Turn::text).containsExactly("我想留一张空白书签，让来的人写一句没说完的话。","我愿意帮忙画书签。现在先去找纸，回头见。");
            assertThat(c.recollectionSources).containsEntry("owner","model").containsEntry("artist","model");
        }finally{director.close();}
    }

    @Test void lifeConversationCanOfferThenAcceptATakeoverThroughTwoResidentsOwnTurns()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("work-dialogue","住客","Asia/Shanghai",clock.instant(),true);
        var conversation=world.conversations.stream().filter(c->c.mode.equals("model")&&c.status.equals("active")).findFirst().orElseThrow();
        conversation.topicId="life";
        var store=new Store(world);var calls=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){throw new AssertionError("A work agreement must be negotiated in dialogue turns");}
            public ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
                int call=calls.incrementAndGet();
                assertThat(request.topicTitle()).isEqualTo("眼前的生活和工作");
                assertThat(request.perspective().careerIntent()).isNotNull();
                assertThat(request.perspective().occupation()).isNotBlank();
                assertThat(request.perspective().cafeOperatorId()).isEqualTo("owner");
                if(call==1){
                    assertThat(request.perspective().residentId()).isEqualTo("owner");
                    assertThat(request.perspective().workArrangements()).isEmpty();
                    return new ConversationLifecycle.Utterance("我最近确实累了。你愿不愿意试着接手咖啡馆？",false,"有点忐忑","none",null,List.of(),"☕","offer_takeover","artist");
                }
                assertThat(request.perspective().residentId()).isEqualTo("artist");
                var offer=request.perspective().workArrangements().stream().filter(a->"proposed".equals(a.status())).findFirst().orElseThrow();
                assertThat(offer.kind()).isEqualTo("takeover");assertThat(offer.proposerId()).isEqualTo("owner");assertThat(offer.workerId()).isEqualTo("artist");
                return new ConversationLifecycle.Utterance("我愿意接手，但会按自己的节奏试一阵。",true,"认真","none",null,List.of(),"☕🎨","accept_work",offer.id());
            }
        };
        var director=oneAtATime(store,mind,clock);
        try{
            driveUntil(director,1,world,()->world.workArrangements.size()==1);
            var offer=world.workArrangements.getFirst();assertThat(offer.status).isEqualTo("proposed");
            assertThat(ResidentSimulation.cafeOperatorId(world)).isEqualTo("owner");
            clock.now=clock.now.plusSeconds(7);
            driveUntil(director,1,world,()->"active".equals(offer.status));
            assertThat(calls).hasValue(2);
            assertThat(ResidentSimulation.cafeOperatorId(world)).isEqualTo("artist");
            assertThat(ResidentSimulation.state(world,"artist").occupation).isEqualTo("经营咖啡馆");
            assertThat(ResidentSimulation.mayTend(world,"artist")).isTrue();
            assertThat(conversation.turns).extracting(Turn::speakerId).containsExactly("owner","artist");
        }finally{director.close();}
    }

    @Test void invalidStructuredWorkActionFailsTheTurnWithoutCreatingAuthority()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("invalid-work-dialogue","住客","Asia/Shanghai",clock.instant(),true);
        var conversation=world.conversations.stream().filter(c->c.mode.equals("model")&&c.status.equals("active")).findFirst().orElseThrow();
        conversation.topicId="life";var store=new Store(world);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){throw new AssertionError();}
            public ConversationLifecycle.Utterance generateTurn(DialogueRequest request){return new ConversationLifecycle.Utterance("我已经把店送给他了。",false,"笃定","none",null,List.of(),null,"invent_agreement","artist");}
        };
        var director=oneAtATime(store,mind,clock);
        try{
            driveUntil(director,2,world,()->"fallback".equals(conversation.mode));
            assertThat(world.workArrangements).isEmpty();
            assertThat(conversation.turns).isEmpty();
            assertThat(ResidentSimulation.cafeOperatorId(world)).isEqualTo("owner");
        }finally{director.close();}
    }
    @Test void tiredResidentCanEndTheConversationThenChooseSleepAsTheirOwnNextAction()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("tired-dialogue","住客","Asia/Shanghai",clock.instant(),true);
        var conversation=world.conversations.stream().filter(c->c.mode.equals("model")&&c.status.equals("active")).findFirst().orElseThrow();
        ResidentSimulation.state(world,"owner").energy=15;world.serviceRequests.clear();
        var store=new Store(world);var decisions=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){
                // Residents each think on their own cooldown now, so neighbours legitimately get asked
                // too - this fixture used to assume the town-wide round robin would only ever reach
                // the owner. Keep the claim (a tired owner ends the talk and then picks sleep himself)
                // and stop asserting that nobody else in town is allowed to think.
                if(!"owner".equals(context.residentId()))
                    return new Decision("observe",context.self().place(),null,"先看看周围","",List.of(),null,null);
                decisions.incrementAndGet();
                assertThat(context.salientPerceptions()).contains("已经很累，注意力很难维持");
                assertThat(context.availableActions()).contains("sleep");
                return new Decision("sleep","home",null,"眼睛已经睁不开了，回去睡","",List.of(),null,null);
            }
            public ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
                assertThat(request.perspective().residentId()).isEqualTo("owner");
                assertThat(request.perspective().salientPerceptions()).contains("已经很累，注意力很难维持");
                return new ConversationLifecycle.Utterance("我有点撑不住了，先回去睡。",true,"困倦","none",null,List.of(),null,"none",null);
            }
            public ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){return new ConversationLifecycle.Recollection("刚才说自己太困，先回去了。","困倦",List.of(request.conversationMemories().getFirst().id()));}
        };
        var director=oneAtATime(store,mind,clock);
        try{
            driveUntil(director,3,world,()->"ended".equals(conversation.status));
            driveUntil(director,3,world,()->conversation.summarizedParticipants.size()==2);
            clock.now=clock.now.plusSeconds(13);
            driveUntil(director,3,world,()->{var plan=ResidentSimulation.state(world,"owner").plan;return decisions.get()==1&&plan!=null&&Set.of("travel","sleep").contains(plan.action());});
            var owner=ResidentSimulation.state(world,"owner");
            assertThat(owner.plan.action()).isIn("travel","sleep");
            if("travel".equals(owner.plan.action()))assertThat(owner.desiredAction).isEqualTo("sleep");
        }finally{director.close();}
    }
    /** The live bug reproduced end to end through the real director/model pipeline: a fake mind plays
     * out the exact reported transcript (顾雁/时安 at 梧桐学院, here owner/artist) and never once sets
     * leave=true - the conversation ends only because ConversationLifecycle's rule-level exit
     * (docs/04-decisions.md 2026-09-14) notices the second speaker's own last two turns are both token
     * acknowledgements. If the rule were missing this test would hang: the mind is written to fail the
     * assertion on any turn past the fourth ("老谭那书……是讲哪方面的？" - the real question the live
     * transcript still asked - must never be requested), and driveUntil would time out with the
     * conversation still active. */
    @Test void aSpeakerStuckOnTokenAcknowledgementsEndsTheConversationThroughTheRealDirectorPipeline()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("token-ack-exit","住客","Asia/Shanghai",clock.instant(),true);
        var conversation=world.conversations.stream().filter(c->c.mode.equals("model")&&c.status.equals("active")).findFirst().orElseThrow();
        var store=new Store(world);var turns=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){throw new AssertionError("A generated conversation must not go through plan-decision insertion");}
            public ConversationLifecycle.Utterance generateTurn(DialogueRequest request){
                int turn=turns.incrementAndGet();
                String text=switch(turn){
                    case 1->"好，那我先安静待会儿。";
                    case 2->"（翻过一页，没抬头）嗯。";
                    case 3->"（低头翻书，没抬头）嗯。";
                    case 4->"（翻过一页，没抬头）嗯。";
                    default->throw new AssertionError("规则应在第4轮后结束对话，不该再向模型要第"+turn+"轮");
                };
                // leave is always false: only the rule, never the model's own cooperation, may end this.
                return new ConversationLifecycle.Utterance(text,false,"专注","none",null,List.of());
            }
            public ConversationLifecycle.Recollection summarizeConversation(SummaryRequest request){
                return new ConversationLifecycle.Recollection("刚才的对话就这样停在那儿了。","平常",List.of(request.conversationMemories().getFirst().id()));
            }
        };
        var director=oneAtATime(store,mind,clock);
        try{
            driveUntil(director,1,world,()->turns.get()>=1);
            clock.now=clock.now.plusSeconds(7);
            driveUntil(director,1,world,()->turns.get()>=2);
            clock.now=clock.now.plusSeconds(7);
            driveUntil(director,1,world,()->turns.get()>=3);
            clock.now=clock.now.plusSeconds(7);
            driveUntil(director,1,world,()->turns.get()>=4);
            driveUntil(director,1,world,()->"ended".equals(conversation.status));
            assertThat(turns).as("must not have been asked for a fifth turn").hasValue(4);
            assertThat(conversation.turns).hasSize(4);
            assertThat(conversation.endReason).isEqualTo("对方接连只是应一声，不再多问，各自去忙");
            // Same per-pair protection any other ending already gets (ResidentLifeTest.
            // someoneWhoJustTalkedIsLeftAloneForAWhile / SOCIAL_RECOVERY_SECONDS): both participants'
            // lastSocialAt is stamped at conversation end, so they are not immediately re-paired.
            var endedAt=conversation.endedAt;
            for(String id:conversation.participantIds)
                assertThat(ResidentSimulation.state(world,id).lastSocialAt).as("%s not re-engageable immediately",id).isEqualTo(endedAt);
            driveUntil(director,1,world,()->conversation.summarizedParticipants.size()==2);
        }finally{director.close();}
    }

    @Test void operatorCanCloseAndAnotherResidentCanCarryPortableWorkHomeThroughDirector()throws Exception {
        // 21:10 Asia/Shanghai - ten minutes past the hour this shop usually stops, which is what
        // makes closing a question at all. At 20:59 it is not one, and the occasion is not raised.
        var clock=new MutableClock(Instant.parse("2026-09-08T13:10:00Z"));
        var world=CompanionRules.join("director-closing","住客","Asia/Shanghai",clock.instant(),true);world.conversations.clear();world.serviceRequests.clear();world.cafeStatus="open";
        var owner=ResidentSimulation.state(world,"owner");owner.plan=null;
        var artist=ResidentSimulation.state(world,"artist");artist.plan=new Plan("portable-sketch","make","cafe",null,"把窗边那张小稿收尾",clock.instant().minusSeconds(120),clock.instant().plusSeconds(600));artist.suspendedAction=null;
        move(world,"artist","cafe","make","还在画小稿",clock.instant().plusSeconds(600));
        for(var r:world.residentStates)if(Set.of("student","gardener").contains(r.id))r.plan=new Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",clock.instant(),clock.instant().plusSeconds(600));
        move(world,"owner","cafe","idle","在吧台后面",clock.instant().plusSeconds(600));
        // Closing for the day is no longer one entry in the ordinary menu (142 offers, 0 taken - see
        // Occasions); the rules recognise the moment the hour has passed and put it as its own
        // question. Raising it is what advance() would do on any ordinary tick.
        Occasions.scan(world,clock.instant());
        assertThat(world.pendingOccasions).extracting(p->p.key).contains("close_cafe");
        var store=new Store(world);var decisions=new AtomicInteger();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public ConsiderDraft consider(ConsiderRequest request){
                assertThat(request.key()).isEqualTo("close_cafe");
                assertThat(request.perspective().residentId()).isEqualTo("owner");
                return new ConsiderDraft("close_cafe","到打烊时间了","今天先打烊，我要关灯了。",List.of());
            }
            public Decision decide(Context context){
                decisions.incrementAndGet();
                assertThat(context.residentId()).isEqualTo("artist");assertThat(context.portableAction()).isNotNull();assertThat(context.availableActions()).contains("continue_home");
                return new Decision("continue_home","home",null,"把没画完的带回去","",List.of(),null,null);
            }
        };
        // Pinned to one call in flight: this test's mind asserts the decision it is asked for belongs
        // to the artist, and since docs/04-decisions.md 「只并行"想"，写世界仍串行」 several residents may
        // now be asked at once, an unpinned director would hand a second worker somebody else's
        // decision and that assertion would fire from inside the model stub. What this test is about -
        // the operator closing up while another resident carries portable work home - is unrelated to
        // how many calls are in flight; concurrency has its own test (ResidentDirectorParallelTest).
        var director=new ResidentDirector(store,mind,clock,100000,(userId,day,callType,inputTokens,outputTokens)->{},8,64,12,1);
        try{
            driveUntil(director,51,world,()->"closing".equals(world.cafeStatus));
            // The second half asks specifically what the interrupted artist does. With 25 residents,
            // park the now-finished operator and every other neighbour so this remains that test,
            // rather than depending on the six-person roster order it was originally written for.
            for(var r:world.residentStates)if(!Set.of("artist","self").contains(r.id))
                r.plan=new Plan("park-after-close-"+r.id,"sleep",TownPlaces.homeOf(r.id),null,"睡着",clock.instant(),clock.instant().plusSeconds(600));
            clock.now=clock.now.plusSeconds(13);
            driveUntil(director,51,world,()->decisions.get()==1&&Set.of("travel","make").contains(artist.plan.action()));
            assertThat(world.cafeStatus).isEqualTo("closing");
            assertThat(artist.plan.place()).isIn("home-artist","cafe");
            if("travel".equals(artist.plan.action())){assertThat(artist.desiredAction).isEqualTo("make");assertThat(artist.desiredDurationSeconds).isEqualTo(587);}
        }finally{director.close();}
    }

    @Test void closedCafeOperatorCanChooseToOpenThroughDirector()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T01:00:00Z"));
        var world=CompanionRules.join("director-opening","住客","Asia/Shanghai",clock.instant(),true);world.conversations.clear();world.serviceRequests.clear();world.cafeStatus="closed";world.cafeOperating=true;
        var owner=ResidentSimulation.state(world,"owner");owner.plan=null;owner.suspendedAction=null;move(world,"owner","home-owner","idle","在家",clock.instant().plusSeconds(600));
        for(var r:world.residentStates)if(!Set.of("owner","self").contains(r.id))r.plan=new Plan("park-"+r.id,"sleep","home-"+r.id,null,"睡着",clock.instant(),clock.instant().plusSeconds(600));
        var store=new Store(world);
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){assertThat(context.cafeStatus()).isEqualTo("closed");assertThat(context.availableActions()).contains("open_cafe");return new Decision("open_cafe","cafe",null,"去把门打开","",List.of(),null,null);}
        };
        var director=oneAtATime(store,mind,clock);
        try{driveUntil(director,52,world,()->owner.plan!=null&&Set.of("travel","open_cafe").contains(owner.plan.action()));}
        finally{director.close();}
        assertThat(owner.desiredAction==null?owner.plan.action():owner.desiredAction).isEqualTo("open_cafe");
    }

    /** "谁在用什么" (docs/04-decisions.md 2026-09-14 「对话出口与物品使用状态」) reaching an actual
     * resident's own decision context through the real director pipeline, not just a direct call to
     * {@code perspective()} - proof the wiring is correct, not only the derivation
     * ({@code PositionUseViewTest} already covers that in isolation). 阿禾 (owner) has been reading at
     * the cafe's shared table for forty minutes, holding a book; 知夏 (artist), asked to decide what
     * she does next while sharing that same room, receives it - the same shape the live report
     * (顾雁/时安, reused as owner/artist elsewhere in this file) asks a neighbour be able to see. */
    @Test void aNeighbourSharingARoomReceivesWhatSomeoneIsUsingInTheirOwnDecisionContext()throws Exception {
        var clock=new MutableClock(Instant.parse("2026-09-08T06:00:00Z"));
        var world=CompanionRules.join("position-use-context","住客","Asia/Shanghai",clock.instant(),true);
        world.conversations.clear();world.serviceRequests.clear();
        var owner=ResidentSimulation.state(world,"owner");
        Position table=TownPlaces.position(world,"cafe-worktable");
        move(world,"owner",table.place,"read","看书",clock.instant().plusSeconds(3600));
        owner.activitySince=clock.instant().minusSeconds(40*60);
        TownPlaces.claimExact(world,"owner","cafe-worktable",clock.instant());
        owner.roomId=table.roomId;
        owner.plan=new Plan("owner-read","read","cafe",null,"看书",clock.instant().minusSeconds(2400),clock.instant().plusSeconds(600));owner.suspendedAction=null;
        // This test is only about what ARTIST is handed; owner must stay put reading exactly where
        // seated above, not become a second decision candidate the fake mind's own fallback branch
        // would otherwise reassign (needsDecision is true for an ordinary "read" plan whose fingerprint
        // was never recorded) and in doing so move them off cafe-worktable before artist is ever asked.
        owner.lastDecisionRequestedAt=clock.instant();
        world.objects.add(new WorldObject("taner-book","book","cafe","cafe-main","书","open",null,"owner","owner"));

        var artist=ResidentSimulation.state(world,"artist");artist.plan=null;artist.suspendedAction=null;
        move(world,"artist","cafe","observe","四下看看",clock.instant().plusSeconds(600));
        artist.roomId=table.roomId;
        for(var r:world.residentStates)if(!Set.of("owner","artist","self").contains(r.id))
            r.plan=new Plan("park-"+r.id,"sleep",TownPlaces.homeOf(r.id),null,"睡着",clock.instant(),clock.instant().plusSeconds(600));

        var store=new Store(world);var seen=new AtomicReference<List<ResidentSimulation.PositionUseView>>();
        ResidentMind mind=new ResidentMind(){
            public boolean enabled(){return true;}
            public Decision decide(Context context){
                if(!"artist".equals(context.residentId()))return new Decision("observe",context.self().place(),null,"先看看周围","",List.of(),null,null);
                seen.set(context.positionUses());
                return new Decision("observe","cafe",null,"看看周围","",List.of(),null,null);
            }
        };
        var director=oneAtATime(store,mind,clock);
        try{driveUntil(director,1,world,()->seen.get()!=null);}
        finally{director.close();}

        var ownerUse=seen.get().stream().filter(v->v.occupantId().equals("owner")).findFirst().orElseThrow();
        assertThat(ownerUse.label()).isEqualTo("那张长桌");
        assertThat(ownerUse.activity()).isEqualTo("看书");
        assertThat(ownerUse.minutesSoFar()).isEqualTo(40);
        assertThat(ownerUse.heldObjectLabel()).isEqualTo("书");
        // "顾雁 在长桌 看书 40分钟" - the exact shape, in this town's own resident and words.
        assertThat(ownerUse.occupantName()+" 在"+ownerUse.label()+" "+ownerUse.activity()+" "+ownerUse.minutesSoFar()+"分钟")
            .isEqualTo(ResidentSimulation.actor(world,"owner").name()+" 在那张长桌 看书 40分钟");
    }

    private static void move(CompanionWorld world,String id,String place,String activity,String label,Instant until){for(int i=0;i<world.residents.size();i++){var actor=world.residents.get(i);if(id.equals(actor.id()))world.residents.set(i,new Actor(actor.id(),actor.name(),actor.role(),place,activity,label,actor.x(),actor.y(),until));}}
    private static void driveUntil(ResidentDirector director,long userId,CompanionWorld world,BooleanSupplier condition)throws Exception{
        await(()->{if(condition.getAsBoolean())return true;director.consider(userId,world);return condition.getAsBoolean();});
    }
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);assertThat(condition.getAsBoolean()).isTrue();}
    static class MutableClock extends Clock {
        volatile Instant now;MutableClock(Instant now){this.now=now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    static class Store implements WorldStore {
        CompanionWorld world;ThreadLocal<Boolean> transaction=ThreadLocal.withInitial(()->false);
        Store(CompanionWorld world){this.world=world;}
        public CompanionWorld read(long id){return world;}
        public synchronized CompanionWorld update(long id,Supplier<CompanionWorld> initial,UnaryOperator<CompanionWorld> change){transaction.set(true);try{return world=change.apply(world);}finally{transaction.set(false);}}
        public boolean ownsTask(long id,String task){return false;}
        public String timezone(long id){return "Asia/Shanghai";}
    }
}
