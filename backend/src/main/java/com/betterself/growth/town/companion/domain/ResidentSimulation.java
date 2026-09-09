package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/** Small event-driven society. Plans persist; only completed actions change physical objects. */
public final class ResidentSimulation {
    private ResidentSimulation() {}
    /** Plan actions that make a resident ineligible to start or be pulled into a new conversation,
     * whether as initiator or partner - see the "tend" doc comment at its one use site below for why
     * "tend" belongs in this set even though it is not a passive/absent state like the other four. */
    private static final Set<String> UNAVAILABLE_FOR_CONVERSATION = Set.of("travel","sleep","rest","away","tend");

    /** Abstract positions along the shared street, in walking-seconds units - not frontend pixels (04:
     * 后端定结构，前端定像素). The owner's home sits right by the cafe he runs; the student's desk is a
     * few doors down; the cafe itself is the busiest, most central point; the far homes (artist,
     * gardener, the user's own avatar) sit toward the other end, roughly twenty seconds' walk away -
     * see {@link #travelSeconds} for how this becomes an actual travel duration. */
    private static final Map<String,Integer> STREET_POSITION = new LinkedHashMap<>();
    static {
        STREET_POSITION.put(TownPlaces.homeOf("owner"),0);
        STREET_POSITION.put("street",4);
        STREET_POSITION.put("cafe",4);
        STREET_POSITION.put(TownPlaces.homeOf("student"),8);
        STREET_POSITION.put("garden",14);
        STREET_POSITION.put(TownPlaces.homeOf("artist"),20);
        STREET_POSITION.put(TownPlaces.homeOf("gardener"),26);
        STREET_POSITION.put(TownPlaces.homeOf("self"),30);
        // 周野's own home (see ResidentSeed.initialize's "周野 gets a new home of his own, next to
        // 青叔's garden") - an explicit entry so his travel times are the same kind of deliberate
        // placement as everyone else's, rather than falling through to streetPosition()'s
        // hash-of-the-place-id fallback below (still deterministic, but not an authored position).
        STREET_POSITION.put(TownPlaces.homeOf("fixer"),16);
    }
    private static int streetPosition(String place){
        Integer known=STREET_POSITION.get(place);
        if(known!=null)return known;
        // A manually authored resident's home (see ResidentSeed.addResident) predates this table;
        // place it deterministically along the same span instead of failing on an unknown key.
        return Math.floorMod(place.hashCode(),30);
    }
    /** How many walking-seconds one unit of {@link #STREET_POSITION} is worth. The table is drawn in
     * abstract units; this turns it into a duration a person would recognise - the far end of the
     * street is about a ten-minute walk, next door about a minute.
     * <p>The first version of this used the table's units as seconds directly, which made the whole
     * town three to twenty seconds wide. That is not a walk, and it had a consequence beyond
     * flavour: an accelerated run advances roughly a hundred simulated seconds per tick, so every
     * journey began and ended inside a single tick. Nobody was ever observed on the street, which is
     * why {@link #maybeStreetEncounter} could not have found anyone there however it was written. A
     * walk has to last longer than the clock's own resolution to exist at all. */
    private static final int SECONDS_PER_STREET_UNIT = 20;
    /** Real walking duration between two places (item 2), from the abstract layout in
     * {@link #STREET_POSITION}. Bounded at both ends so a trip is never instant and never longer than
     * the map actually is. */
    static int travelSeconds(String from,String to){
        if(Objects.equals(from,to))return 60;
        int units=Math.abs(streetPosition(from)-streetPosition(to));
        return Math.max(60,Math.min(600,units*SECONDS_PER_STREET_UNIT));
    }
    /** How far apart, in the same abstract street units {@link #travelSeconds} already uses, two
     * residents' own homes sit. Read-only distance, never a duration - see
     * {@link #encounterCooldownSeconds} for the one place this feeds into. */
    private static int homeDistance(String a,String b){
        return Math.abs(streetPosition(TownPlaces.homeOf(a))-streetPosition(TownPlaces.homeOf(b)));
    }
    /** Item 4: a pair who live far apart on {@link #STREET_POSITION} gets fewer chances to ever share
     * a place at all - a fact this method does not try to fix (see {@link #travelSeconds}'s own note
     * on why a walk may never be shortened to fix that). What it does fix is the one thing rule-owned
     * and safe to change: once such a pair does happen to cross paths, the ordinary forty-minute
     * "we just met" cooldown taxes them exactly as much as it taxes two neighbours who bump into each
     * other constantly - for the far pair that tax can eat their one rare opportunity for the rest of
     * the day. A pair whose homes sit at least {@link #FAR_PAIR_DISTANCE_UNITS} street-units apart
     * gets half the ordinary cooldown instead, so a rare crossing is worth more, not less, than a
     * routine one. Nearby pairs are completely unaffected. */
    private static final int FAR_PAIR_DISTANCE_UNITS = 15;
    private static long encounterCooldownSeconds(String a,String b){
        return homeDistance(a,b)>=FAR_PAIR_DISTANCE_UNITS ? ENCOUNTER_COOLDOWN_SECONDS/2 : ENCOUNTER_COOLDOWN_SECONDS;
    }
    /** Mean-once-every-~40-simulated-minutes, purely time-and-identity-derived so replay stays
     * deterministic (never Math.random - see Personality's own abandonThreshold() for the same
     * discipline). One independent 1/40 chance per simulated minute gives that mean via a geometric
     * distribution. This is the "走神" (mind-wandering) signal item 5 asks for: a resident may notice
     * they want to reconsider what they are doing for no external reason at all, which is part of what
     * makes a purely event-driven world feel like it can still produce a sudden thought. */
    public static boolean driftDue(CompanionWorld w,ResidentState r,Instant now){
        if(r.plan==null)return false;
        long bucket=now.getEpochSecond()/60;
        long hash=Objects.hash(w.id,r.id,bucket);
        return Math.floorMod(hash,40)==0;
    }

    // ---- personality drift (item 2): real experience, not the clock, moves who someone is --------
    /** How far a single genuine event may nudge one dimension. Small on purpose - personality is not
     * mood; nobody should be able to point at one afternoon and see it move. */
    private static final double PERSONALITY_DRIFT_STEP = 1.0;
    /** How far a lifetime of real events may ever carry one dimension from where {@link Personality#of}
     * originally seeded it. This is the "never a gate" guarantee (item 2's own red line) made concrete:
     * however many events accumulate, owner's conscientiousness cannot drift down into artist's range
     * or past it - the four residents stay four different people, just slightly weathered ones. */
    private static final double PERSONALITY_DRIFT_LIFETIME_CAP = 12.0;
    /** Minimum simulated gap between two drifts of the SAME dimension for the SAME resident. Combined
     * with the small per-event step above, this is what keeps a day showing no visible change and a
     * week showing a real one: at most six nudges of at most one point each in a single day, per
     * dimension, and only when six genuinely separate qualifying events actually happened. */
    private static final long PERSONALITY_DRIFT_MIN_GAP_SECONDS = 4*3600L;

    private static double dimensionValue(ResidentState r,String dimension){
        return switch(dimension){case "extroversion"->r.extroversion;case "conscientiousness"->r.conscientiousness;case "sensitivity"->r.sensitivity;default->r.volatility;};
    }
    private static void setDimensionValue(ResidentState r,String dimension,double value){
        switch(dimension){case "extroversion"->r.extroversion=value;case "conscientiousness"->r.conscientiousness=value;case "sensitivity"->r.sensitivity=value;default->r.volatility=value;}
    }
    /** Nudges one of r's own four personality dimensions by signedStep (positive or negative), bounded
     * on three sides at once: never past 0/100, never further than {@link #PERSONALITY_DRIFT_LIFETIME_CAP}
     * from where this resident actually started, and never twice for the same dimension inside
     * {@link #PERSONALITY_DRIFT_MIN_GAP_SECONDS}. {@code cause} is a fixed, rule-authored word (never a
     * resident's own explanation - see {@link CompanionWorld.PersonalityDrift}'s own doc comment) and is
     * the only place any of this is recorded; nothing here writes into the resident's own memory, because
     * the rules do not get to tell a resident why they changed - only that, mechanically, they did.
     * A no-op for the avatar, which has no authored personality to drift (see Personality.of). */
    /** The two social outcomes that only a real, model-driven conversation can produce. They used to
     * be nudged from {@link #continueConversation}, which is dead code for any conversation that is
     * not literally {@code mode="rules"} - ConversationLifecycle.tick() short-circuits it, and every
     * real conversation is created with {@code mode="model"}. So the accept/decline drift existed,
     * was tested, and had never once fired: a measured day produced 17 personality nudges, 13 of them
     * from being alone for six hours, and not a single one from anything that happened between two
     * people.
     * <p>Who moves is deliberately not who spoke. Being accepted is a fact about the person who
     * asked; being turned down is also a fact about the person who asked. The one doing the accepting
     * or declining is just answering. */
    static void driftOnConversationOutcome(CompanionWorld w,String accepterId,String inviterId,boolean accepted,Instant at){
        if(accepted)driftPersonality(w,state(w,accepterId),"extroversion",PERSONALITY_DRIFT_STEP,"agreement",at);
        else driftPersonality(w,state(w,inviterId),"extroversion",-PERSONALITY_DRIFT_STEP,"declined",at);
    }
    private static void driftPersonality(CompanionWorld w,ResidentState r,String dimension,double signedStep,String cause,Instant at){
        if(r==null||"self".equals(r.id))return;
        Personality.of(r); // ensure this resident's own fields are seeded before nudging them
        Instant last=r.lastPersonalityDriftAt.get(dimension);
        if(last!=null&&Duration.between(last,at).getSeconds()<PERSONALITY_DRIFT_MIN_GAP_SECONDS)return;
        double current=dimensionValue(r,dimension);
        double initial=Personality.initial(r.id,dimension);
        double lo=Math.max(0,initial-PERSONALITY_DRIFT_LIFETIME_CAP), hi=Math.min(100,initial+PERSONALITY_DRIFT_LIFETIME_CAP);
        double bounded=Math.max(lo,Math.min(hi,current+signedStep));
        if(bounded==current)return; // already pinned at its own lifetime bound - nothing to record
        setDimensionValue(r,dimension,bounded);
        r.lastPersonalityDriftAt.put(dimension,at);
        w.personalityDrifts.add(new CompanionWorld.PersonalityDrift("pd-"+(++w.eventSequence),r.id,dimension,bounded-current,cause,at));
        while(w.personalityDrifts.size()>200)w.personalityDrifts.removeFirst();
    }
    /** Bounded diagnostic history of why a decision was actually triggered - see
     * {@link CompanionWorld#decisionTriggers}. Never read by any model. */
    public static void recordDecisionTrigger(CompanionWorld w,String residentId,String trigger,Instant at){
        w.decisionTriggers.add(new DecisionTrigger("dt-"+(++w.eventSequence),residentId,trigger,at));
        while(w.decisionTriggers.size()>300)w.decisionTriggers.removeFirst();
    }
    /** Per-pair cooldown for a rule-detected encounter (item 3): thirty to sixty simulated minutes was
     * the asked-for range; forty minutes is chosen as the middle of that range rather than an extreme -
     * long enough that two residents who happen to share a place for a normal 20-30 minute study/work
     * stretch are not re-greeted every few minutes (which is what "a greeting mill" would look like),
     * short enough that a second, later encounter the same afternoon is still possible. See the report
     * for how this was sanity-checked against a real accelerated run. */
    private static final long ENCOUNTER_COOLDOWN_SECONDS = 40*60;
    /** True only when the avatar is not currently under the user's own explicit control (no active
     * focus session, and no intent still pending or active - see CompanionRules.submit/advance, which
     * own that state directly on CompanionWorld). Gated additionally on
     * {@link CompanionWorld#avatarAutonomyEnabled}: see that field's javadoc and ResidentDirector's
     * report for why this stays an explicit opt-in this batch rather than always-on. */
    public static boolean selfIsFree(CompanionWorld w){
        if(!w.avatarAutonomyEnabled||w.focus!=null)return false;
        return w.intents.stream().noneMatch(i->Set.of("pending","active").contains(i.status));
    }
    static LifeIntent lifeIntent(CompanionWorld w,ResidentState r,String goalId,String purpose,String status,Instant at){
        // Intent ids are local descriptive state, never world events.  Keeping them out of the event
        // sequence preserves the deterministic social simulation/replay that already keys choices on
        // that sequence.
        LifeIntent intent=new LifeIntent();intent.id="life-"+r.id+"-"+at.toEpochMilli();intent.goalId=goalId;intent.purpose=purpose;intent.status=status;intent.formedAt=at;intent.updatedAt=at;return intent;
    }
    static void setLifeIntent(CompanionWorld w,ResidentState r,String goalId,String purpose,String status,Instant at){
        if(r.lifeIntent==null||!Objects.equals(r.lifeIntent.purpose,purpose)||!Objects.equals(r.lifeIntent.goalId,goalId))r.lifeIntent=lifeIntent(w,r,goalId,purpose,status,at);
        else {r.lifeIntent.status=status;r.lifeIntent.updatedAt=at;}
    }
    public static void advance(CompanionWorld w,Instant now) {
        ResidentSeed.initialize(w,now);
        reconcileLegacyPlaces(w,now);
        ResidentSeed.reconcileLife(w,now);
        CafeService.reconcileSchedule(w,now);
        deduplicateReflections(w);
        if(w.simulatedAt==null)w.simulatedAt=now;
        long elapsed=Duration.between(w.simulatedAt,now).getSeconds();
        if(elapsed>900) {
            // Reconcile needs gently, then at most 24 seconds of recent life. No offline model calls.
            w.simulatedAt=now.minusSeconds(24);
            for(ResidentState r:w.residentStates){r.energy=Math.max(35,r.energy);r.social=Math.max(40,r.social);r.energyUpdatedAt=w.simulatedAt;}
            for(Conversation c:w.conversations)if(c.status.equals("active"))ConversationLifecycle.finish(w,c,now,"离开期间这段谈话已经告一段落");
        }
        int steps=0;
        while(!w.simulatedAt.plusSeconds(6).isAfter(now)&&steps++<10){w.simulatedAt=w.simulatedAt.plusSeconds(6);step(w,w.simulatedAt);}
    }
    static void step(CompanionWorld w,Instant at) {
        CafeService.reconcileSchedule(w,at);
        ConversationLifecycle.recoverSummaries(w,at);
        // The coffee/water chain has its own clock, independent of whose plan is currently running:
        // a request keeps waiting, gets picked up, or goes cold on real elapsed time even while its
        // requester has already moved on to something else while they wait. See CafeService.
        CafeService.tick(w,at);
        for(Conversation c:new ArrayList<>(w.conversations))if(c.status.equals("active"))continueConversation(w,c,at);
        for(ResidentState r:w.residentStates) {
            // The avatar's own state is present so it can be perceived and can hold a position. Its
            // activity is user-driven (CompanionRules) by default; when CompanionWorld.avatarAutonomyEnabled
            // is on AND it is not currently under the user's own explicit control (selfIsFree), it runs
            // through the exact same plan-completion/decision loop as any NPC below - see selfIsFree's
            // javadoc and ResidentDirector's report for why this stays an explicit opt-in this batch.
            if(r.id.equals("self")) {
                if(!selfIsFree(w)) {
                    // Under explicit user control right now: never race CompanionRules for the avatar.
                    // Any plan left over from a previous free stretch is stale and is dropped silently,
                    // without running its completion side effects against a reality it no longer describes.
                    r.plan=null;r.suspendedAction=null;
                    continue;
                }
                Actor live=actor(w,"self");
                String signature=live.place()+"|"+live.activity()+"|"+live.until();
                if(r.plan!=null&&!signature.equals(r.selfActivitySignature)) {
                    // An explicit user intent (or CompanionRules' own idle autopilot) moved the avatar
                    // out from under this plan since it was made - pre-empt it, per item 7's requirement.
                    r.plan=null;r.suspendedAction=null;
                }
            }
            reconcileDayPlan(w,r,at);
            perceive(w,r,at);
            // The owner's sense of responsibility for the counter builds every tick someone is
            // waiting, whatever else the owner is currently doing - not only when they are free to
            // decide. See CafeService.accruePressure.
            if(CafeService.mayTend(w,r.id))CafeService.accruePressure(w,r,at);
            settleEnergy(w,r,at);
            if(activeConversation(w,r.id)!=null)continue;
            if(r.plan!=null&&!at.isBefore(r.plan.endsAt())){Plan completed=r.plan;complete(w,r,at);if(r.plan==completed){r.plan=null;if(!Set.of("sleep","open_cafe").contains(completed.action()))resumeSuspended(w,r,at);}}
            if(r.plan==null){if(!maybePlaceHabit(w,r,at))awaitDecision(w,r,at);}
            maybeEncounter(w,r,at);
            maybeSolitudeDrift(w,r,at);
            maybeHabit(w,r,at);
        }
        // The model is each resident's decision-maker. Rule-only fallback completes already approved
        // physical work but does not manufacture a reflection, social choice or new intention.
        expirePendingEncounters(w,at);
        expireDeclinedEncounters(w);
        CafeService.finishClosingIfEmpty(w,at);
        syncLegacyObjects(w);
    }

    private static void awaitDecision(CompanionWorld w,ResidentState r,Instant at){
        Actor current=actor(w,r.id);
        if(!"idle".equals(current.activity())||!at.isBefore(current.until()))
            replaceActor(w,r.id,current.place(),"idle",w.modelConversationsEnabled?"停下来想下一步":"暂时没有新的安排",at.plusSeconds(300));
    }

    /** Energy follows simulated elapsed time, not the number of service calls or completed actions.
     * Values are game tuning: roughly sixteen ordinary waking hours from full to very low, and about
     * eight hours of sleep from very low back toward full. */
    static void settleEnergy(CompanionWorld w,ResidentState r,Instant at){
        if(r.energyUpdatedAt==null){r.energyUpdatedAt=at;return;}
        long seconds=Duration.between(r.energyUpdatedAt,at).getSeconds();
        if(seconds<=0)return;
        String action=r.plan==null?actor(w,r.id).activity():r.plan.action();
        double hourly=switch(action==null?"idle":action){
            case "sleep"->10.0;
            case "rest"->8.0;
            case "study","read"->-7.5;
            case "work","make","create","help","tend","away"->-8.0;
            case "travel","walk"->-7.0;
            default->-6.0;
        };
        r.energy=clamp(r.energy+hourly*seconds/3600.0);r.energyUpdatedAt=at;
    }
    /** The actions that count as having done work of the kind a direction is about - deliberately the
     * same set {@link #INTERRUPTIBLE_WORK_ACTIONS} already uses for "is this person in the middle of
     * work", plus tending the counter, rather than a second, differently-drawn line. */
    private static final Set<String> DIRECTED_WORK_ACTIONS=Set.of("study","read","work","make","create","help","tend");
    /** Stamps "the last time I actually did something toward this" on a resident's own directions.
     * <p>{@code lastActedAt} was declared, exposed all the way into every resident's model context
     * through LifeIntentView, and asserted by a test - and written by absolutely nothing, anywhere,
     * in production code. It reached every model call as a permanent null. This is the write.
     * <p>The two intents are stamped by two different rules because they are two different things.
     * careerIntent has no goalId, so the only honest reading is the coarse one: this person did work
     * of the kind their direction is about. The rules deliberately do not judge whether the work
     * SERVED the direction - that is a reading of meaning, and it belongs to the resident, not here.
     * lifeIntent, when it names a goal at all, gets the precise reading instead: this finished thing
     * was that thing. A lifeIntent naming nothing in particular is never stamped, because there is
     * nothing in particular it could be stamped for. */
    private static void markIntentActedOn(ResidentState r,Plan p,Instant at){
        if(p==null||p.action()==null)return;
        if(r.careerIntent!=null&&DIRECTED_WORK_ACTIONS.contains(p.action()))r.careerIntent.lastActedAt=at;
        if(r.lifeIntent!=null&&r.lifeIntent.goalId!=null&&r.lifeIntent.goalId.equals(p.targetId()))r.lifeIntent.lastActedAt=at;
    }

    private static void complete(CompanionWorld w,ResidentState r,Instant at) {
        Plan p=r.plan;
        markIntentActedOn(r,p,at);
        if(p.action().equals("travel")){int duration=r.desiredDurationSeconds>0?r.desiredDurationSeconds:42;schedule(w,r,r.desiredAction,p.place(),p.targetId(),p.reason(),at,duration);return;}
        if(p.action().equals("away")){
            String home=TownPlaces.homeOf(r.id);
            replaceActor(w,r.id,home,"idle","刚从外面回来",at.plusSeconds(60));
            TownPlaces.claim(w,r.id,home,null,at);
            memory(w,r.id,r.id,"observed",at,p.targetId(),"我出门处理了自己的事："+p.reason()+"，现在回来了。",List.of(),6);
            event(w,at,"return",home,List.of(r.id),actor(w,r.id).name()+"从外面回来了。",null);
            return;
        }
        if(p.action().equals("relocate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&actor(w,r.id).place().equals("cafe")&&project.place.equals("garden")) {
                project.place="cafe";r.knownProjects.put(project.id,new ProjectKnowledge(project.id,"cafe",project.status,project.progress,at,r.id));
                for(int i=0;i<w.objects.size();i++){WorldObject o=w.objects.get(i);if(Objects.equals(o.projectId(),project.id))w.objects.set(i,new WorldObject(o.id(),o.kind(),"cafe",o.label(),o.state(),o.projectId()));}
                event(w,at,"adapt","cafe",List.of(r.id),actor(w,r.id).name()+"把「"+project.title+"」的材料带到了檐下，免得淋湿。",project.id);
                memory(w,r.id,r.id,"observed",at,project.id,"我把「"+project.title+"」的材料搬到了咖啡馆檐下。",List.of(),6);
            }
        } else if(p.action().equals("invite")) {
            ResidentState partner=state(w,p.targetId());Project topic=project(w,r.goal);
            if(partner!=null&&canTalkTo(w,r.id,partner.id)){
                if(topic!=null&&canInvite(w,r,partner,topic,at))startConversation(w,r,partner,topic,at);
                else startLifeConversation(w,r,partner,at);
            }
        } else if(Set.of("create","help").contains(p.action())) {
            Project project=project(w,p.targetId());
            if(project!=null&&knows(w,r.id,project.id)&&actor(w,r.id).place().equals(project.place)&&!Set.of("ready","celebrating").contains(project.status)) {
                boolean first=!project.contributors.contains(r.id);if(first)project.contributors.add(r.id);
                // A conscientious resident follows through a little more thoroughly once committed;
                // the least conscientious does a little less per attempt. Never lets personality wipe
                // out a contribution entirely.
                int gain=Math.max(4,(first?25:12)+Personality.of(r).diligenceBonus());
                // A communal project cannot finish through one resident's repeated work alone.
                project.progress=Math.min(project.contributors.size()<project.needed?SOLO_PROGRESS_CAP:100,project.progress+gain);
                project.status=project.progress==100?"ready":"active";
                project.description=actor(w,r.id).name()+"刚完成了一小部分；"+(project.progress==100?"已经可以一起看看了。":"还想听听别人的想法。");
                w.objects.removeIf(o->Objects.equals(o.projectId(),project.id));
                w.objects.add(new WorldObject("project-"+project.id,project.objectKind,project.place,project.title,project.progress==100?"finished":"progress-"+project.progress,project.id));
                String text=actor(w,r.id).name()+"在"+placeName(project.place)+"为「"+project.title+"」添了一笔"+(first?"，留下了自己的做法。":"。");
                String evidence=memory(w,r.id,r.id,"observed",at,project.id,text,List.of(),7);
                event(w,at,"contribution",project.place,List.of(r.id),text,project.id);
                for(ResidentState other:w.residentStates)if(!other.id.equals(r.id)&&!other.id.equals("self")&&actor(w,other.id).place().equals(project.place)&&!actor(w,other.id).activity().equals("walk"))
                    witnessContribution(w,other,r,project,text,evidence,at);
                if(project.progress==100){project.completedAt=at;event(w,at,"ready",project.place,new ArrayList<>(project.contributors),"「"+project.title+"」准备好了，和最初一个人的想法已经不太一样。",project.id);
                    // Seeing something all the way through is real, repeatable evidence of follow-through -
                    // exactly the axis conscientiousness already measures (see Personality's own javadoc).
                    driftPersonality(w,r,"conscientiousness",PERSONALITY_DRIFT_STEP,"project_complete",at);}
            }
        } else if(p.action().equals("celebrate")) {
            Project project=project(w,p.targetId());
            if(project!=null&&project.status.equals("ready")) {
                project.status="celebrating";
                event(w,at,"celebration",project.place,new ArrayList<>(project.contributors),actor(w,r.id).name()+"招呼大家来看「"+project.title+"」。这一次，桌边多了几个熟悉的位置。",project.id);
                for(String member:project.contributors)if(actor(w,member).place().equals(project.place)&&!actor(w,member).activity().equals("walk")) {
                    boolean detail=Personality.of(state(w,member)).sensitivity()>=65;
                    String text=detail?"我亲眼看见，参与的「"+project.title+"」真的做出来了，连细节都跟当初说的差不多。":"我亲眼看见，参与的「"+project.title+"」真的做出来了。";
                    memory(w,member,r.id,"observed",at,project.id,text,List.of(),9);
                    // A shared, happy moment is real steadying experience, not a reason - the opposite
                    // pull from "interrupted" below, on the same dimension a repeated interruption erodes.
                    driftPersonality(w,state(w,member),"volatility",-PERSONALITY_DRIFT_STEP,"celebration",at);
                }
            }
        } else if(p.action().equals("open_cafe")){CafeService.openForDay(w,r.id,p.reason(),at);}
        else if(p.action().equals("tend")){CafeService.finishTending(w,r,p.targetId(),at);}
    }
    /** Put aside a concrete action rather than throwing it away.  The remaining time is rebuilt from
     * the interruption instant, so an old end timestamp cannot make a returned-to action complete
     * immediately. */
    private static void suspend(ResidentState r,Instant at){
        if(r.plan==null||r.suspendedAction!=null)return;
        long remaining=Math.max(1,Duration.between(at,r.plan.endsAt()).getSeconds());
        SuspendedAction paused=new SuspendedAction();
        paused.plan=new Plan(r.plan.id(),r.plan.action(),r.plan.place(),r.plan.targetId(),r.plan.reason(),at,at.plusSeconds(remaining));
        paused.desiredAction=r.desiredAction;paused.desiredDurationSeconds=r.desiredDurationSeconds;paused.pausedAt=at;
        r.suspendedAction=paused;
    }
    /** Committed work (see {@link #PORTABLE_ACTIONS} plus the two social-project actions) genuinely
     * being set aside for a conversation is item 2's "被打断" - real, repeated evidence, not mood.
     * Used only where a conversation is about to start (see the two call sites below); the ordinary
     * "rest"/"sleep" re-plan and the tend/request_drink detours in applyDecision are not this - they
     * are the resident's own choice, not an interruption imposed on them by someone else appearing. */
    private static final Set<String> INTERRUPTIBLE_WORK_ACTIONS = Set.of("study","read","work","make","create","help");
    private static void suspendForConversation(CompanionWorld w,ResidentState r,Instant at){
        if(r.plan!=null&&r.suspendedAction==null&&INTERRUPTIBLE_WORK_ACTIONS.contains(r.plan.action()))
            driftPersonality(w,r,"conscientiousness",-PERSONALITY_DRIFT_STEP,"interrupted",at);
        suspend(r,at);
    }
    /** Restoring is a normal scheduling transition, including a journey that was interrupted on the
     * street.  This is deliberately called only after the interrupting action actually completes. */
    static void resumeSuspended(CompanionWorld w,ResidentState r,Instant at){
        SuspendedAction paused=r.suspendedAction;if(paused==null||paused.plan==null)return;
        Plan p=paused.plan;
        if("cafe".equals(p.place())&&!"open".equals(w.cafeStatus))return;
        r.suspendedAction=null;
        int remaining=(int)Math.max(1,Duration.between(p.startedAt(),p.endsAt()).getSeconds());
        if("travel".equals(p.action())){
            r.desiredAction=paused.desiredAction;r.desiredDurationSeconds=paused.desiredDurationSeconds;
            r.plan=new Plan("p-"+(++w.eventSequence),"travel",p.place(),p.targetId(),p.reason(),at,at.plusSeconds(remaining));
            r.revision++;r.thought="继续"+p.reason();replaceActor(w,r.id,"street","walk","继续去"+placeName(p.place()),r.plan.endsAt());
        } else moveOrSchedule(w,r,p.action(),p.place(),p.targetId(),p.reason(),at,remaining);
    }
    /** The same contribution is witnessed by everyone present, but what each observer actually
     * writes into their own memory depends on how much attention to detail they personally pay - not
     * on the event itself, which is identical for all of them. Someone with a sharp eye keeps the
     * concrete wording; someone in the middle keeps only the gist, at lower importance; someone not
     * paying close attention writes nothing down at all - the event happened, but for them it left no
     * trace. This is the mechanism the differentiated-memory tests exercise directly. */
    private static void witnessContribution(CompanionWorld w,ResidentState observer,ResidentState actorState,Project project,String actorText,String actorEvidenceId,Instant at) {
        int sensitivity=Personality.of(observer).sensitivity();
        if(sensitivity<35){
            // Genuinely not noticing, repeatedly, is itself real evidence - the opposite pull from
            // actually paying attention below, on the same dimension it moves.
            driftPersonality(w,observer,"sensitivity",-PERSONALITY_DRIFT_STEP,"missed_detail",at);
            return;
        }
        boolean detail=sensitivity>=65;
        if(detail)driftPersonality(w,observer,"sensitivity",PERSONALITY_DRIFT_STEP,"noticed_detail",at);
        String text=detail?"我看见"+actorText:"隐约感觉到"+actor(w,actorState.id).name()+"又在忙「"+project.title+"」，具体做了什么我没太看清。";
        memory(w,observer.id,actorState.id,"observed",at,project.id,text,List.of(actorEvidenceId),detail?7:4);
        // Someone who actually watched the work happen has seen how far along the thing in the room
        // is. Without this the observer knows the project exists (the memory above is what "knows"
        // reads) but knownProjects stays empty, so every project anybody ever witnessed reads back to
        // them as "刚开始" - which is the exact opposite of the truth for one that has stalled at the
        // solo cap, and the reading least likely to make anyone put their hands on it.
        if(detail)observer.knownProjects.put(project.id,new ProjectKnowledge(project.id,project.place,project.status,project.progress,at,actorState.id));
    }
    private static void moveOrSchedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        if(!actor(w,r.id).place().equals(place)) {
            r.desiredAction=action;r.desiredDurationSeconds=duration;
            int travel=travelSeconds(actor(w,r.id).place(),place);
            r.plan=new Plan("p-"+(++w.eventSequence),"travel",place,target,reason,at,at.plusSeconds(travel));
            r.revision++;r.thought=reason;
            TownPlaces.release(w,r.id); // stepping away frees up the spot right away, not 12 seconds from now
            replaceActor(w,r.id,"street","walk","准备去"+placeName(place)+"："+reason,r.plan.endsAt());
        } else schedule(w,r,action,place,target,reason,at,duration);
    }
    static void schedule(CompanionWorld w,ResidentState r,String action,String place,String target,String reason,Instant at,int duration) {
        r.plan=new Plan("p-"+(++w.eventSequence),action,place,target,reason,at,at.plusSeconds(duration));r.revision++;r.thought=reason;
        r.desiredAction=null;r.desiredDurationSeconds=0;
        String label=switch(action){case "create","help"->"动手准备"+(project(w,target)==null?"手上的小事":"「"+project(w,target).title+"」");case "study"->"在窗边复习，想守住一点安静";case "invite"->reason;case "join"->"过去和"+(target==null?"邻居":actor(w,target).name())+"坐一起";case "sleep"->"睡着了，给明天留一点精神";case "rest"->"捧着杯子歇一会儿";case "celebrate"->"想请大家看看一起做出来的东西";case "wait"->reason;case "tend"->r.id.equals(CafeService.operatorId(w))?"回到吧台，照应一下柜台前的人":"替"+actor(w,CafeService.operatorId(w)).name()+"照看吧台";case "away"->"出门去处理自己的事："+reason;default->reason;};
        replaceActor(w,r.id,place,action,label,r.plan.endsAt());
        // "能站的地方都能去" (04-decisions.md): a named position is only claimed for the handful of
        // things that are genuinely owned and capacity-limited - a bed, the owner's coffee machine,
        // the student's window seat. Standing, chatting, observing, creating at the shared table,
        // inviting someone, celebrating - none of that needs a slot; positionId simply stays null and
        // the resident is loosely "at" the place, exactly where the frontend's own walkable-area
        // pathing already puts a standing actor. This is also why the garden's four named spots no
        // longer force four people into a pile - most of what happens there never claims one.
        String kind=preferredKind(action,place);
        if(kind==null){TownPlaces.release(w,r.id);return;}
        TownPlaces.Outcome outcome=TownPlaces.claim(w,r.id,place,kind,at);
        if(outcome==TownPlaces.Outcome.WAITING) {
            // Only a bed, counter, study seat or home desk reaches here, so this is the genuinely-scarce case. Stand by a moment instead of
            // being placed on top of someone.
            String waitReason="这里现在坐满了，先在旁边等一等";
            r.plan=new Plan("p-"+(++w.eventSequence),"wait",place,target,waitReason,at,at.plusSeconds(12));r.thought=waitReason;
            replaceActor(w,r.id,place,"wait",waitReason,r.plan.endsAt());
        }
    }
    /** Which actions still need a named, owned position claimed - see the "能站的地方都能去" note in
     * schedule() above. Null means this action never claims one at all, not merely "any spot will
     * do". */
    private static String preferredKind(String action,String place) {
        if(TownPlaces.isHome(place)&&Set.of("study","read","work","make").contains(action))return "desk";
        if("cafe".equals(place)&&Set.of("study","read","work","make").contains(action))return "seat";
        if("join".equals(action))return switch(place){case "cafe"->"seat";case "street","garden"->"bench";default->null;};
        return switch(action) {
            case "sleep"->"bed";
            case "tend"->"equipment";
            default->null;
        };
    }
    private static boolean canInvite(CompanionWorld w,ResidentState a,ResidentState b,Project p,Instant at) {
        if(!knows(w,a.id,p.id)||knownStatus(a,p.id).equals("celebrating")||p.members.contains(b.id))return false;
        Instant previous=p.invitationHistory.get(pairKey(a.id,b.id));
        if(previous==null)previous=w.conversations.stream().filter(c->c.topicId.equals(p.id)&&c.participantIds.contains(a.id)&&c.participantIds.contains(b.id)).map(c->c.startedAt).max(Comparator.naturalOrder()).orElse(null);
        // The inviter's own extroversion can only lengthen this cooldown (introverts wait longer to
        // re-approach the same person), never shorten it below the 900-second baseline.
        return previous==null||Duration.between(previous,at).getSeconds()>=Personality.of(a).inviteCooldownSeconds();
    }
    private static String pairKey(String a,String b){return a.compareTo(b)<0?a+":"+b:b+":"+a;}
    static void startConversation(CompanionWorld w,ResidentState a,ResidentState b,Project p,Instant at) {
        p.invitationHistory.put(pairKey(a.id,b.id),at);
        // A chat can be a detour through an already meaningful afternoon, rather than a command to
        // discard that afternoon altogether.
        suspendForConversation(w,a,at);suspendForConversation(w,b,at);
        Conversation c=new Conversation();c.id="c-"+(++w.eventSequence);c.place=actor(w,a.id).place();c.topicId=p.id;c.status="active";c.participantIds.add(a.id);c.participantIds.add(b.id);c.startedAt=at;c.updatedAt=at;
        // A production/model conversation never drops into the rule-authored agreement script just
        // because the provider is cooling down. ConversationLifecycle will end it neutrally if the
        // model is unavailable; only explicit rule-only historical runs use the template below.
        if(w.modelConversationsEnabled){
            c.mode="model";c.nextSpeakerId=a.id;w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
            replaceActor(w,a.id,c.place,"talk","想和"+actor(w,b.id).name()+"聊聊，正在组织语言",at.plusSeconds(45));
            replaceActor(w,b.id,c.place,"talk","停下手里的事，等对方开口",at.plusSeconds(45));
            event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"叫住了"+actor(w,b.id).name()+"。",p.id);
            while(w.conversations.size()>24)w.conversations.removeFirst();return;
        }
        String invitation=switch(p.objectKind){case "poster"->"你看这个，哪块颜色最像你？";case "flowers"->"有株苗多出来了。你窗边放得下吗？";case "tea"->"茶我泡了两种。你要不要尝一口？";default->"你那本读到一半的书，也带来吧？";};
        // A's own private fondness for b (never visible to b, and never sent to anyone else's model
        // context - see ResidentDirector.perspective()) can let itself show once, the first time it
        // is high enough; after that it does not keep repeating the same tell every single time.
        boolean fond=a.relationships.getOrDefault(b.id,40)>55;
        boolean alreadyShown=Boolean.TRUE.equals(a.affectionExpressed.get(b.id));
        if(fond&&!alreadyShown)a.affectionExpressed.put(b.id,true);
        String opener=fond&&!alreadyShown?"你上次说的，我还记着。":"";
        c.turns.add(new Turn(a.id,opener+invitation,at));
        w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
        replaceActor(w,a.id,c.place,"talk",c.turns.getFirst().text(),at.plusSeconds(36));replaceActor(w,b.id,c.place,"talk","停下手里的事，听听对方",at.plusSeconds(36));
        var shared=a.knownProjects.get(p.id);if(shared!=null)b.knownProjects.put(p.id,new ProjectKnowledge(shared.id(),shared.place(),shared.status(),shared.progress(),at,a.id));
        memory(w,b.id,a.id,"heard",at,p.id,actor(w,a.id).name()+"当面告诉我，正在准备「"+p.title+"」。",ownEvidence(w,a.id,p.id),7);
        event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"叫住了"+actor(w,b.id).name()+"，聊起「"+p.title+"」。",p.id);
        while(w.conversations.size()>24)w.conversations.removeFirst();
    }
    /** A conversation may be about a shift, a possible new life, or an unresolved arrangement;
     * it does not need a public project to be a legitimate encounter. */
    private static void startLifeConversation(CompanionWorld w,ResidentState a,ResidentState b,Instant at){
        suspendForConversation(w,a,at);suspendForConversation(w,b,at);Conversation c=new Conversation();c.id="c-"+(++w.eventSequence);c.place=actor(w,a.id).place();c.topicId="life";c.status="active";c.participantIds.add(a.id);c.participantIds.add(b.id);c.startedAt=at;c.updatedAt=at;c.mode=w.modelConversationsEnabled?"model":"fallback";c.nextSpeakerId=a.id;w.conversations.add(c);a.lastSocialAt=at;b.lastSocialAt=at;
        replaceActor(w,a.id,c.place,"talk","想聊聊手头的生活安排",at.plusSeconds(45));replaceActor(w,b.id,c.place,"talk","停下来听听对方的打算",at.plusSeconds(45));event(w,at,"conversation",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"和"+actor(w,b.id).name()+"聊起最近的生活安排。",null);
    }
    private static void continueConversation(CompanionWorld w,Conversation c,Instant at) {
        if(ConversationLifecycle.tick(w,c,at))return;
        if(Duration.between(c.updatedAt,at).getSeconds()<8)return;
        ResidentState a=state(w,c.participantIds.get(0)),b=state(w,c.participantIds.get(1));Project p=project(w,c.topicId);
        if(!actor(w,a.id).place().equals(c.place)||!actor(w,b.id).place().equals(c.place)){ConversationLifecycle.finish(w,c,at,"有人先离开了");return;}
        int n=c.stage;String speaker,text;
        if(n==1) {
            speaker=b.id;
            boolean reluctant=b.energy<40||b.id.equals("student")&&p.kind.equals("gathering")||b.relationships.getOrDefault(a.id,40)<32;
            c.accepted=!reluctant;
            text=reluctant?(b.id.equals("student")?"我还要复习。真要聚的话，给我留个安静点的位置。":"今天不行，我脑子已经转不动了。要不我只帮一小会儿？")
                :b.id.equals("artist")?"这块太整齐了。让我留点真的人住过的痕迹？"
                :b.id.equals("gardener")?"苗能放，不过得有人记得浇水。你管，还是我管？":"我能帮你理一理。先说最急的是哪件？";
            b.mood=reluctant?"有些犹豫":"被需要";relation(a,b,reluctant?-3:4);
        } else if(n==2) {
            speaker=a.id;
            if(!c.accepted && b.energy<32) {
                text="行，你先歇。";
                a.mood="更体谅了";
            } else if(!c.accepted) {
                text="那就把安静角留出来。别的先照旧。";
                p.description="听过"+actor(w,b.id).name()+"的顾虑，改成小规模准备，并给安静和休息留位置。";
                a.mood="重新想过了";c.accepted=true;
                event(w,at,"change_of_mind",c.place,List.of(a.id,b.id),actor(w,a.id).name()+"因为"+actor(w,b.id).name()+"的顾虑，改了「"+p.title+"」的做法。",p.id);
            } else text=switch(b.id){case "artist"->"行，那块留给你。别画得太规矩。";case "gardener"->"浇水我来记。你帮我看看放哪儿合适。";case "student"->"安静的位置我留着。";default->"最急的是把材料归一下。别的先放着。";};
        } else if(n==3) {
            speaker=b.id;
            if(!c.accepted) {
                text="好，那我先歇。";
                c.turns.add(new Turn(speaker,text,at));c.stage++;c.updatedAt=at;relation(a,b,2);
                a.revision++;b.revision++;
                memory(w,a.id,b.id,"heard",at,p.id,actor(w,b.id).name()+"说今天想先休息，没有答应参与。",ownEvidence(w,a.id,p.id),6);
                event(w,at,"declined",c.place,List.of(a.id,b.id),actor(w,b.id).name()+"婉拒了这一次邀请，"+actor(w,a.id).name()+"决定不催促。",p.id);
                // A genuine, repeated rejection is real experience, not mood - see driftPersonality's
                // own doc comment for the bound that keeps this from ever becoming a visible swing.
                driftPersonality(w,a,"extroversion",-PERSONALITY_DRIFT_STEP,"declined",at);
                replaceActor(w,speaker,c.place,"talk",text,at.plusSeconds(12));return;
            }
            text=switch(b.id){case "student"->"行。等我把这页看完。";case "artist"->"行，留一块给我。";case "gardener"->"行，我晚点把苗拿来。";default->"行，我收完台面就来。";};
            if(!p.members.contains(b.id))p.members.add(b.id);b.goal=p.id;b.thought="听过对方的安排后，我愿意试着一起做一点。";
            relation(a,b,6);b.social=clamp(b.social+18);a.social=clamp(a.social+18);
            String evidence=memory(w,b.id,a.id,"heard",at,p.id,"我们当面商量过「"+p.title+"」，对方接受了我的想法，我答应做一小部分。",ownEvidence(w,b.id,p.id),8);
            memory(w,a.id,b.id,"heard",at,p.id,actor(w,b.id).name()+"当面答应参与「"+p.title+"」。",List.of(evidence),8);
            event(w,at,"agreement",c.place,List.of(a.id,b.id),actor(w,b.id).name()+"答应参与「"+p.title+"」，不是旁观者了。",p.id);
            driftPersonality(w,b,"extroversion",PERSONALITY_DRIFT_STEP,"agreement",at);
        } else {
            ConversationLifecycle.finish(w,c,at,"各自继续手上的事");return;
        }
        c.turns.add(new Turn(speaker,text,at));c.stage++;c.updatedAt=at;a.revision++;b.revision++;replaceActor(w,speaker,c.place,"talk",text,at.plusSeconds(24));
    }
    // reflect() and newWish() used to live here. Both were already unreachable - nothing called
    // either one - and they are removed rather than moved so the next batch builds reflection from
    // scratch instead of inheriting a corpse. What the metrics counted as "reflections" was never
    // this method: it is applyDecision() below storing the model's own stated reason for an action
    // as a reflection-typed memory. The town has had no reflection mechanism at all.

    public static boolean proposeDecision(CompanionWorld w,String id,long residentRevision,long intentRevision,String place,String title,String objectKind,String reason,List<String> evidence,Instant now) {
        ResidentState r=state(w,id);
        if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision||!TownPlaces.contains(w,place)||TownPlaces.isHome(place))return false;
        if(title==null||title.isBlank()||title.length()>36||reason==null||reason.length()>160||!Set.of("poster","flowers","books","tea").contains(objectKind==null?"":objectKind))return false;
        if(evidence==null||evidence.isEmpty()||evidence.stream().anyMatch(e->w.memories.stream().noneMatch(m->m.ownerId().equals(id)&&m.id().equals(e))))return false;
        if(w.projects.stream().filter(p->p.ownerId.equals(id)&&!p.status.equals("celebrating")).count()>=2)return false;
        propose(w,r,place,title,objectKind,reason,evidence,now);w.modelStatus="模型刚让"+actor(w,id).name()+"想到了一个新愿望";w.revision++;return true;
    }
    private static void propose(CompanionWorld w,ResidentState r,String place,String title,String kind,String reason,List<String> evidence,Instant now) {
        String id="wish-"+(++w.eventSequence);
        project(w,id,title,"shared",place,r.id,kind,reason,2);r.knownProjects.put(id,new ProjectKnowledge(id,place,"idea",0,now,r.id));r.goal=id;r.thought=reason;r.mood="又有了一个小主意";r.revision++;
        memory(w,r.id,r.id,"observed",now,id,"我在纸上写下一个还没实现的愿望：「"+title+"」。",List.of(),7);
        if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,id,reason,evidence,8);
        event(w,now,"new_wish",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"写下一个新愿望：「"+title+"」。",id);
        while(w.projects.size()>16){Project old=w.projects.stream().filter(p->p.status.equals("celebrating")).findFirst().orElse(null);if(old==null)break;w.projects.remove(old);}
        while(w.objects.size()>20)w.objects.removeFirst();
    }
    /** An offer records only one side of a social arrangement.  It does not grant equipment use;
     * acceptance below is intentionally a separate, auditable transition. */
    public static boolean proposeWorkArrangement(CompanionWorld w,String proposerId,String kind,String otherId,String note,Instant now){
        return proposeWorkArrangement(w,proposerId,kind,otherId,note,List.of(),now);
    }
    public static boolean proposeWorkArrangement(CompanionWorld w,String proposerId,String kind,String otherId,String note,List<String> evidence,Instant now){
        ResidentState proposer=state(w,proposerId),other=state(w,otherId);String operator=CafeService.operatorId(w);
        if(proposer==null||other==null||proposerId.equals(otherId)||!Set.of("assist","delegate","takeover").contains(kind)||note==null||note.isBlank()||note.length()>160)return false;
        boolean valid="assist".equals(kind)?Objects.equals(otherId,operator):Objects.equals(proposerId,operator);
        if(!valid)return false;
        WorkArrangement a=new WorkArrangement();a.id="work-"+(++w.eventSequence);a.kind=kind;a.place="cafe";a.proposerId=proposerId;a.workerId="assist".equals(kind)?proposerId:otherId;a.status="proposed";a.note=note;a.proposedAt=now;a.proposerEvidenceIds.addAll(evidence);w.workArrangements.add(a);
        memory(w,proposerId,proposerId,"observed",now,"work","assist".equals(kind)?"我提出可以替对方照看一阵吧台。":"我提出把吧台的责任交给对方。",List.of(),6);
        event(w,now,"work_offer","cafe",List.of(proposerId,otherId),actor(w,proposerId).name()+"提出了关于咖啡馆的安排。",null);return true;
    }
    /** Both people must say yes before the helper can use the counter.  A takeover additionally
     * transfers the concrete counter owner; delegation and help remain use permissions only. */
    public static boolean acceptWorkArrangement(CompanionWorld w,String accepterId,String arrangementId,Instant now){
        return acceptWorkArrangement(w,accepterId,arrangementId,List.of(),now);
    }
    public static boolean acceptWorkArrangement(CompanionWorld w,String accepterId,String arrangementId,List<String> evidence,Instant now){
        WorkArrangement a=w.workArrangements.stream().filter(x->Objects.equals(x.id,arrangementId)&&"proposed".equals(x.status)).findFirst().orElse(null);
        if(a==null)return false;String operator=CafeService.operatorId(w);String required="assist".equals(a.kind)?operator:a.workerId;
        if(!Objects.equals(required,accepterId))return false;
        a.status="active";a.acceptedAt=now;a.workerEvidenceIds.addAll(evidence);
        if("takeover".equals(a.kind)){String former=CafeService.operatorId(w);TownPlaces.transferCafeCounter(w,a.workerId);w.cafeOperating=true;state(w,a.workerId).occupation="经营咖啡馆";state(w,a.workerId).careerIntent=lifeIntent(w,state(w,a.workerId),null,"试着把咖啡馆经营下去","active",now);replaceRole(w,a.workerId,"咖啡馆经营者");if(!former.equals(a.workerId))replaceRole(w,former,"前店主，正在找新的生活方向");}
        else {state(w,a.workerId).occupation="assist".equals(a.kind)?"在高峰时替人照看吧台":"受托照看咖啡馆";replaceRole(w,a.workerId,"assist".equals(a.kind)?"咖啡馆帮工":"受托经营者");}
        memory(w,accepterId,accepterId,"observed",now,"work","我明确答应了这份关于咖啡馆的安排。",List.of(),7);
        event(w,now,"work_agreement","cafe",List.of(a.proposerId,accepterId),"关于咖啡馆的安排被两个人说清楚了。",null);return true;
    }
    /** Either participant may decline an offer or end an active arrangement; ended arrangements
     * never regain equipment authority if the counter later reopens. */
    public static boolean endWorkArrangement(CompanionWorld w,String residentId,String arrangementId,String status,Instant now){
        WorkArrangement a=w.workArrangements.stream().filter(x->Objects.equals(x.id,arrangementId)&&Set.of("proposed","active").contains(x.status)).findFirst().orElse(null);
        if(a==null||!Set.of("rejected","ended").contains(status)||(!residentId.equals(a.proposerId)&&!residentId.equals(a.workerId)&&!residentId.equals(CafeService.operatorId(w))))return false;
        a.status=status;a.endedAt=now;event(w,now,"work_"+status,"cafe",List.of(residentId),actor(w,residentId).name()+"没有继续这份咖啡馆安排。",null);return true;
    }
    /** Choosing another life can close the counter.  It never invents a replacement resident. */
    public static boolean changeOccupation(CompanionWorld w,String residentId,String occupation,Instant now){
        return changeOccupation(w,residentId,occupation,null,now);
    }
    public static boolean changeOccupation(CompanionWorld w,String residentId,String occupation,String closingSpeech,Instant now){
        ResidentState r=state(w,residentId);if(r==null||occupation==null||occupation.isBlank()||occupation.length()>80)return false;
        if(Objects.equals(CafeService.operatorId(w),residentId)){
            if("open".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place())&&closingSpeech!=null&&!closingSpeech.isBlank())CafeService.closeForDay(w,residentId,closingSpeech,now);
            CafeService.pauseOperation(w,residentId,now);
            for(Conversation conversation:new ArrayList<>(w.conversations))if("active".equals(conversation.status)&&"cafe".equals(conversation.place))ConversationLifecycle.finish(w,conversation,now,"经营者暂停营业，这段谈话先停在这里");
            moveFocusedAvatarHome(w,now);
        }
        r.occupation=occupation;r.goal=null;r.plan=null;r.suspendedAction=null;TownPlaces.release(w,residentId);r.careerIntent=lifeIntent(w,r,null,"试着过上"+occupation+"的日子","active",now);
        if(Objects.equals(CafeService.operatorId(w),residentId))replaceRole(w,residentId,"正在转向"+occupation);
        memory(w,residentId,residentId,"reflection",now,"work","我想把日子往“"+occupation+"”的方向试一试，先不把这当成已经成功。",List.of(),7);
        event(w,now,"occupation_change",actor(w,residentId).place(),List.of(residentId),actor(w,residentId).name()+"正在重新想自己想做什么。",null);return true;
    }
    /** Called only after an authorized open_cafe action physically completes. The prior direction is
     * kept in the new description instead of being silently erased by returning to the counter. */
    static void markCafeReturned(CompanionWorld w,String residentId,String reason,Instant at){
        if(!Objects.equals(CafeService.operatorId(w),residentId))return;
        ResidentState r=state(w,residentId);if(r==null)return;
        String previous=r.occupation==null||r.occupation.isBlank()?"未记录":r.occupation;
        if(previous.startsWith("恢复咖啡馆经营；此前方向："))previous=previous.substring("恢复咖啡馆经营；此前方向：".length());
        String chosenReason=reason==null||reason.isBlank()?"本人选择重新开门":reason;
        r.occupation="恢复咖啡馆经营；此前方向："+previous;
        r.careerIntent=lifeIntent(w,r,null,"恢复咖啡馆经营："+chosenReason+"；此前方向："+previous,"active",at);r.lifeIntent=lifeIntent(w,r,null,chosenReason,"active",at);
        replaceRole(w,residentId,"咖啡馆经营者");r.revision++;
    }
    public record PortableAction(String action,String reason,int remainingSeconds) {}

    /** Only unusually strong body signals enter the resident's conscious context. Internal values
     * remain simulation state and are never exposed to the language model. */
    public static List<String> salientPerceptions(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null)return List.of();List<String> result=new ArrayList<>();
        if(r.energy<=8)result.add("困得几乎做不了需要专注的事");
        else if(r.energy<=20)result.add("已经很累，注意力很难维持");
        if("closing".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place()))result.add("咖啡馆正在打烊，店里不再接新单");
        else if("closed".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place()))result.add("咖啡馆已经关门");
        if(CafeService.mayTend(w,residentId)&&"cafe".equals(actor(w,residentId).place())&&CafeService.oldestWaitingRequestId(w)!=null)result.add("柜台前有人在等");
        ServiceRequest ownWait=w.serviceRequests.stream().filter(request->residentId.equals(request.requesterId)&&"waiting".equals(request.status)&&"cafe".equals(actor(w,residentId).place())).findFirst().orElse(null);
        if(ownWait!=null&&Duration.between(ownWait.requestedAt,at).getSeconds()>=120)result.add("这杯已经等了一阵，还没有人来做");
        // The one thing that could ever give someone a reason to order anything. Across a measured day
        // request_drink was offered 32 times and chosen zero times, and the cafe served nobody - not
        // because the machinery was broken (it isn't) but because nothing in anyone's perceptions ever
        // pointed at it. A "thirst" value is forbidden, so this is a situation instead: you have been
        // sitting here a while with nothing in front of you. See CafeService.drinkWantCue.
        String drinkWant=CafeService.drinkWantCue(w,r,actor(w,residentId).place(),at);
        if(drinkWant!=null)result.add(drinkWant);
        return List.copyOf(result);
    }
    public static String cafeScheduleCue(CompanionWorld w,String residentId,Instant at){return CafeService.scheduleCue(w,residentId,at);}
    public static List<String> routineCues(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null||!r.sleepScheduleSeeded||"sleep".equals(actor(w,residentId).activity()))return List.of();
        ZonedDateTime local=at.atZone(ZoneId.of(w.timezone));int minute=local.getHour()*60+local.getMinute();
        boolean inWindow=r.usualSleepMinute<r.usualWakeMinute?minute>=r.usualSleepMinute&&minute<r.usualWakeMinute:minute>=r.usualSleepMinute||minute<r.usualWakeMinute;
        return inWindow?List.of("到了我平常睡觉的时间"):List.of();
    }
    public static String cafeNotice(CompanionWorld w,String residentId){
        if(!"closing".equals(w.cafeStatus)||!"cafe".equals(actor(w,residentId).place()))return null;
        return CafeService.latestClosingNotice(w,residentId);
    }
    private static boolean canTalkTo(CompanionWorld w,String speakerId,String otherId){
        ResidentState other=state(w,otherId);if(other==null||speakerId.equals(otherId)||activeConversation(w,otherId)!=null)return false;
        // The avatar is only ever a valid conversation partner during its own free/autonomous time
        // (item 7) - never while the user is explicitly directing it, so a rule- or model-triggered
        // chat can never yank the avatar out of a focus session or an explicit intent.
        if("self".equals(otherId)&&!selfIsFree(w))return false;
        Actor speaker=actor(w,speakerId),candidate=actor(w,otherId);
        return speaker.place().equals(candidate.place())&&!Set.of("walk","travel","sleep","rest","away","tend").contains(candidate.activity());
    }
    /** The public places, and only these: being alone in your own home is not an encounter waiting to
     * happen, and nobody is greeted through their own front door. */
    private static final Set<String> PUBLIC_PLACES = Set.of("street","cafe","garden");
    /** Long enough after anyone's last conversation before the rules will put them in front of someone
     * again. The per-pair cooldown below stops the same two people greeting in a loop; this stops one
     * sociable resident being handed round the whole town in a single afternoon. */
    private static final long SOCIAL_RECOVERY_SECONDS = 15*60;

    /** Whether the rules may put this resident face to face with someone right now. Deliberately more
     * permissive than {@link #canTalkTo}, which governs a resident deciding to approach someone: you
     * do not choose to interrupt a person mid-walk, but you do say hello to someone you pass, and to
     * someone reading in the corner of the cafe you have just walked into. The three exclusions are
     * the ones a person would also observe - asleep, out of town entirely, or behind the counter
     * working - plus the avatar's own protection: it is only ever available during its free time. */
    private static boolean greetable(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);
        if(r==null||activeConversation(w,residentId)!=null)return false;
        if("self".equals(residentId)&&!selfIsFree(w))return false;
        if(r.lastSocialAt!=null&&Duration.between(r.lastSocialAt,at).getSeconds()<SOCIAL_RECOVERY_SECONDS)return false;
        return !Set.of("sleep","away","tend").contains(actor(w,residentId).activity());
    }

    /** Rule-detected "just ran into someone" (item 3, docs/01's 偶遇). Two residents sharing a public
     * place become an external fact for both of them - the rules bring them face to face and stop
     * there. The model still writes every word (through the same {@link #startLifeConversation} a
     * manual "invite" without a shared project already uses); whether this actually becomes a
     * conversation, a nod, or an excuse to leave is entirely its choice. Gated on
     * {@link CompanionWorld#modelConversationsEnabled}: a rule-only world has no model to write an
     * opening turn with, so it does not manufacture one.
     * <p>This runs once per resident per tick rather than only on arrival, which is the correction to
     * the first version. That one fired solely when a travel plan completed, and a measured day
     * produced exactly one arrival event in twenty-four hours and zero encounters: residents mostly
     * re-decide where they already are, so "arrived somewhere" is far too rare a moment to hang the
     * town's whole social life on. Passing someone on the street is covered by the same code for the
     * same reason - a walker's place IS "street" for the length of the walk (see
     * {@link #moveOrSchedule}), so crossing paths needs no separate rule, only a walk long enough to
     * be seen (see {@link #SECONDS_PER_STREET_UNIT}) and a {@link #greetable} check that does not
     * refuse to notice someone because they are moving. An interrupted journey is already a case
     * {@link #resumeSuspended} handles: both walkers pick their trip back up when the talking ends. */
    private static void maybeEncounter(CompanionWorld w,ResidentState resident,Instant at){
        if(!w.modelConversationsEnabled)return;
        String place=actor(w,resident.id).place();
        if(!PUBLIC_PLACES.contains(place)||!greetable(w,resident.id,at))return;
        for(ResidentState other:w.residentStates){
            if(other.id.equals(resident.id)||!actor(w,other.id).place().equals(place))continue;
            if(!greetable(w,other.id,at))continue;
            String key=pairKey(resident.id,other.id);
            Instant last=w.encounterCooldowns.get(key);
            if(last!=null&&Duration.between(last,at).getSeconds()<encounterCooldownSeconds(resident.id,other.id))continue;
            // Already decided to leave this person alone, and nothing about the scene has changed
            // since. Not a timer - a re-noticing. See encounterFingerprint.
            String seen=encounterFingerprint(w,resident.id,other.id,place);
            if(seen.equals(w.declinedEncounters.get(key)))continue;
            w.declinedEncounters.remove(key);
            w.encounterCooldowns.put(key,at);
            // The rules stop here. Standing in front of someone is a fact; what to do about it is the
            // resident's own call, answered by a model through applyReaction below.
            CompanionWorld.PendingEncounter pending=new CompanionWorld.PendingEncounter();
            pending.id="pe-"+(++w.eventSequence);pending.residentId=resident.id;pending.otherId=other.id;
            pending.place=place;pending.at=at;pending.residentRevision=resident.revision;
            w.pendingEncounters.add(pending);
            while(w.pendingEncounters.size()>12)w.pendingEncounters.removeFirst();
            recordDecisionTrigger(w,resident.id,"encounter",at);
            return; // one encounter at a time; the others are still standing there next tick
        }
    }

    /** Item 2's fifth example event, "长时间独处": read only from a fact the rules already track for an
     * entirely different reason (lastSocialAt - see SOCIAL_RECOVERY_SECONDS above, and item 1's red
     * line that this value is never sent to any model). A long, real stretch without a single
     * conversation nudges volatility up a little, the opposite pull from a shared happy moment in
     * complete()'s "celebrate" branch above. Gated by driftPersonality's own cooldown, so this cannot
     * refire every tick just because the drought continues. */
    private static final long SOLITUDE_DRIFT_THRESHOLD_SECONDS = 6*3600L;
    private static void maybeSolitudeDrift(CompanionWorld w,ResidentState r,Instant at){
        if(r.id.equals("self")||r.lastSocialAt==null)return;
        if(Duration.between(r.lastSocialAt,at).getSeconds()<SOLITUDE_DRIFT_THRESHOLD_SECONDS)return;
        driftPersonality(w,r,"volatility",PERSONALITY_DRIFT_STEP,"solitude",at);
    }

    // ---- habitual reflexes (item 1): the rules acting on a resident's behalf, never the model --------
    /** At most about this often, per resident per habit - the frequency backstop item 1 explicitly asks
     * for ("每人每小时最多一次那种量级"), tightened further here so a full simulated day still lands in
     * the single digits per resident even if the structural condition holds continuously all day. */
    private static final long HABIT_MIN_GAP_SECONDS = 3*3600L;
    /** The same frequency backstop, sized for the other kind of habit item 1 also covers: one that
     * actually walks a resident somewhere (see the placeHabitXxx family below) rather than only
     * coloring a label. A trip is a bigger thing than a label change, so it gets a longer cooldown -
     * at most four or five a day, not one every three hours. */
    private static final long PLACE_HABIT_MIN_GAP_SECONDS = 5*3600L;
    /** Deterministic, mean-once-every-five-eligible-minutes roll for whether a habit that has already
     * cleared its own cooldown actually fires on this exact minute - same discipline as driftDue()/
     * Personality.abandonThreshold(): (worldId, residentId, habit id, minute bucket) hashed, never
     * Math.random, so a replay reproduces the exact same moments. */
    private static boolean habitEligible(CompanionWorld w,ResidentState r,String habitId,Instant at){
        return habitEligible(w,r,habitId,HABIT_MIN_GAP_SECONDS,at);
    }
    private static boolean habitEligible(CompanionWorld w,ResidentState r,String habitId,long baseGapSeconds,Instant at){
        // A belief must be able to weaken even the very first time a habit would otherwise fire, not
        // only the spacing between repeats - so a habit with no history yet is anchored to the
        // world's own join instant, exactly as if it had "already fired" the moment this resident's
        // life began. Without a belief this changes nothing observable (every habit already needs a
        // real elapsed gap before it can fire at all); with one, the damped gap below can outlast an
        // entire test window's worth of simulated time, deterministically, rather than merely making
        // firing less likely.
        Instant last=r.lastHabitAt.getOrDefault(habitId,w.joinedAt==null?at:w.joinedAt);
        double damping=habitBeliefDamping(w,r.id,habitId);
        long gap=Math.round(baseGapSeconds*damping);
        if(Duration.between(last,at).getSeconds()<gap)return false;
        long bucket=at.getEpochSecond()/60;
        long hash=Objects.hash(w.id,r.id,habitId,bucket);
        return Math.floorMod(hash,5)==0;
    }
    /** Item 3's one closed loop: a resident's own standing belief can turn its own default habit down.
     * The rules never read what the belief SAYS - only whether one exists, structurally, under the
     * exact reserved key "habit:&lt;residentId&gt;:&lt;habitId&gt;" (the same supersedesKey convention
     * ResidentReflectionTest's own beliefs already use, e.g. "artist:seat:owner") and is not
     * superseded. When one is found, its own {@code importance} (1-10, a structured field, not text)
     * scales how much rarer the habit becomes - a belief the resident holds strongly dampens it harder
     * than an offhand one. This can only ever weaken a habit, never strengthen it, whatever the belief
     * actually concludes - partly because telling the two directions apart would mean reading the text,
     * which is exactly the understanding the rules must never do, and partly because that is the
     * honest reading anyway: a reflex you have noticed in yourself and formed a standing view about
     * has stopped being entirely automatic, whether or not you approve of it. Which is the whole point
     * of the layer above - see {@link #habitTraits} for the half that lets a resident notice at all. */
    /** One of this resident's own default reflexes, in their own words, paired with the exact
     * supersedesKey a belief has to carry to stand over it. This is the half of the loop that was
     * missing: the rules could already read a belief filed under "habit:&lt;居民&gt;:&lt;习惯&gt;" and turn
     * the matching habit down ({@link #habitBeliefDamping}), but nothing ever told a resident that
     * such a key existed, so no resident ever wrote one and the whole path was unreachable.
     * <p>The description is deliberately the reflex as an observer would describe it - what this
     * person tends to do - and says nothing about what filing a belief under the key will DO. A
     * resident who is told "say this and you will do it less" is following an instruction; one who is
     * shown what they keep doing and reaches their own conclusion about it is the thing this whole
     * layer is for. Which of these ever gets written, and in which direction, stays the resident's. */
    public record HabitTrait(String key,String description) {}
    private static final Map<String,List<String[]>> HABIT_TRAITS = Map.of(
        "owner",List.of(new String[]{"tidy","心里不痛快的时候不说出来，去擦桌子、把杯子重新摆一遍"},
                        new String[]{"mind_cafe","一闲下来就想回店里看看，哪怕没人叫"},
                        new String[]{"own_thing","一闲下来就回头去弄自己那件没做完的事，没跟谁说"}),
        "student",List.of(new String[]{"quiet","被打断之后就不再多说，把书翻回原来那页接着看"},
                          new String[]{"study_cafe","没别的安排就往咖啡馆靠窗那个位置坐，点杯常喝的看书"},
                          new String[]{"own_thing","一闲下来就回头去弄自己那件没做完的事，没跟谁说"}),
        "artist",List.of(new String[]{"hide","刚做完一件东西，反而先转过去放好，不急着拿给谁看"},
                         new String[]{"seek_inspiration","想不出画什么的时候不硬画，去咖啡馆看人"},
                         new String[]{"own_thing","一闲下来就回头去弄自己那件没做完的事，没跟谁说"}),
        "gardener",List.of(new String[]{"handwork","旁边有人的时候不搭话，先去把手边松掉的东西钉紧"},
                           new String[]{"tend_garden","没事就往花园去，手上顺带点东西"},
                           new String[]{"deliver_seedling","想找人的时候不空手去，带一株苗"},
                           new String[]{"own_thing","一闲下来就回头去弄自己那件没做完的事，没跟谁说"},
                           new String[]{"lend_a_hand","看见别人没做完的事搁在那儿，不问就上手添一笔"}),
        "fixer",List.of(new String[]{"check","路过就伸手推一推、试试稳不稳，话不多"},
                        new String[]{"check_cafe","闲下来往店里走，看看有没有要搭把手的"},
                        new String[]{"lend_a_hand","看见别人没做完的事搁在那儿，不问就上手添一笔"}),
        "weaver",List.of(new String[]{"smooth","气氛一僵就先动手挪东西，替人找个台阶，不点破"},
                         new String[]{"be_around_people","没什么事就往人多的地方坐"},
                         new String[]{"lend_a_hand","看见别人没做完的事搁在那儿，不问就上手添一笔"}));
    /** What to offer this resident when they are reflecting. Empty for anyone with no default reflexes
     * of their own (the avatar, above all: its habits are the user's, not ours to name). */
    public static List<HabitTrait> habitTraits(String residentId){
        return HABIT_TRAITS.getOrDefault(residentId,List.of()).stream()
            .map(t->new HabitTrait("habit:"+residentId+":"+t[0],t[1])).toList();
    }
    /** A belief may only ever stand over one of the reflecting resident's OWN habits. Anything else
     * under the reserved prefix - somebody else's habit, or a habit nobody has - is not a belief about
     * oneself and is refused rather than quietly filed. */
    private static boolean validHabitKey(String residentId,String supersedesKey){
        return !supersedesKey.startsWith("habit:")
            ||habitTraits(residentId).stream().anyMatch(t->t.key().equals(supersedesKey));
    }
    private static double habitBeliefDamping(CompanionWorld w,String residentId,String habitId){
        String key="habit:"+residentId+":"+habitId;
        return w.memories.stream()
            .filter(m->residentId.equals(m.ownerId())&&"belief".equals(m.sourceType())&&key.equals(m.supersedesKey())&&!m.superseded())
            .findFirst()
            .map(m->1.0+Math.max(1,m.importance()))
            .orElse(1.0);
    }
    /** Lands one habitual reflex: a cosmetic label only, on top of whatever the resident is already
     * doing. Deliberately never touches place/activity/plan - see item 1's own red line "习惯动作绝不
     * 推进任何世界状态，也绝不开始对话" - so nothing that reads r.plan (completion, energy,
     * availableActions) can tell the difference between a resident with a habit and one without. The
     * deed itself is what survives: recordDeed's own note is the only durable trace, exactly like any
     * other reflex action. */
    private static void fireHabit(CompanionWorld w,ResidentState r,String habitId,String action,String label,String note,Instant at){
        r.lastHabitAt.put(habitId,at);
        Actor a=actor(w,r.id);
        replaceActor(w,r.id,a.place(),a.activity(),label,a.until());
        recordDeed(w,r.id,action,a.place(),note,at);
    }
    /** One habitual reflex per resident (item 1), sunk from ResidentSeed.NARRATIVES' actingSelf prose
     * down into a rule the simulation runs on its own, without asking the model - see each habitXxx
     * method for which sentence of that resident's own actingSelf it stands in for. A habit only ever
     * colors a plan that is already running (never invents one from idle), which is what "习惯是默认
     * 值不是强制" means in practice: the model's next real decision can always simply not repeat it. */
    private static void maybeHabit(CompanionWorld w,ResidentState r,Instant at){
        if(r.plan==null)return;
        switch(r.id){
            case "owner"->habitTidy(w,r,at);
            case "student"->habitQuiet(w,r,at);
            case "artist"->habitHide(w,r,at);
            case "gardener"->habitHandwork(w,r,at);
            case "fixer"->habitCheck(w,r,at);
            case "weaver"->habitSmooth(w,r,at);
            default->{}
        }
    }
    /** 阿禾: "用忙碌代替表达：心里不舒服时去擦桌子、理杯子，而不是说出来。" dutyPressure (never sent to
     * any model - see the class's own red line) is precisely the rules' own tracked measure of exactly
     * the kind of unspoken discomfort that sentence describes: people are waiting, he has not gone to
     * the counter yet, and it is building. */
    private static final double HABIT_TIDY_DUTY_THRESHOLD = 20;
    private static void habitTidy(CompanionWorld w,ResidentState r,Instant at){
        Actor a=actor(w,r.id);
        if(!"cafe".equals(a.place())||"tend".equals(r.plan.action())||r.dutyPressure<HABIT_TIDY_DUTY_THRESHOLD)return;
        if(!habitEligible(w,r,"tidy",at))return;
        fireHabit(w,r,"tidy","tidy","手上没停，去擦了擦桌子、把杯子按高矮摆整齐。","又把已经擦过的桌子擦了一遍，顺手把杯子按高矮重新摆好。",at);
    }
    /** 小川: "低调内敛：话短，……被打断就闭嘴。" Right after a conversation ends and he is back at his
     * own portable work with nobody prompting him to, he goes quiet rather than dwelling on it aloud. */
    private static void habitQuiet(CompanionWorld w,ResidentState r,Instant at){
        if(r.plan==null||!PORTABLE_ACTIONS.contains(r.plan.action()))return;
        if(r.lastSocialAt==null||Duration.between(r.lastSocialAt,at).getSeconds()>150)return;
        if(!habitEligible(w,r,"quiet",at))return;
        fireHabit(w,r,"quiet","study","把书翻回原来那页，没再多说什么。","被打断之后没再多说，把书翻回刚才那页，接着看。",at);
    }
    /** 知夏: "真做完时反而突然怕拿出来。" Right after one of her own projects actually reaches "ready",
     * the rules' own recorded {@code completedAt} is the fact that this just happened. */
    private static final long HABIT_HIDE_WINDOW_SECONDS = 300;
    private static void habitHide(CompanionWorld w,ResidentState r,Instant at){
        boolean justFinished=w.projects.stream().anyMatch(p->p.contributors.contains("artist")&&p.completedAt!=null
            &&!p.completedAt.isAfter(at)&&Duration.between(p.completedAt,at).getSeconds()<=HABIT_HIDE_WINDOW_SECONDS);
        if(!justFinished)return;
        if(!habitEligible(w,r,"hide",at))return;
        fireHabit(w,r,"hide","tidy","把刚画完的那张转过去放好，没急着给人看。","把刚完成的那部分转过去放好，没有主动拿给谁看。",at);
    }
    /** 青叔: "话少，动手多：用东西代替话……" Someone else sharing the garden with him, right now, is the
     * rules' own "身边有人" fact - what he does about it is fix something rather than talk about it. */
    private static void habitHandwork(CompanionWorld w,ResidentState r,Instant at){
        if(!"garden".equals(actor(w,r.id).place()))return;
        boolean someoneElseHere=w.residentStates.stream().anyMatch(o->!o.id.equals("gardener")&&!o.id.equals("self")&&"garden".equals(actor(w,o.id).place()));
        if(!someoneElseHere)return;
        if(!habitEligible(w,r,"handwork",at))return;
        fireHabit(w,r,"handwork","tend_object","没说话，蹲下把一块松动的木牌钉紧了。","没答话，先把花园角落一块松动的木牌钉紧了。",at);
    }
    /** 周野: "直接问、直接说。" The wordless version of the same instinct, before there is anything
     * worth saying yet: someone else sharing whatever public place he is in, right now, gets a quick,
     * blunt physical check rather than small talk. */
    private static void habitCheck(CompanionWorld w,ResidentState r,Instant at){
        String place=actor(w,r.id).place();
        if(!PUBLIC_PLACES.contains(place))return;
        boolean someoneElseHere=w.residentStates.stream().anyMatch(o->!o.id.equals("fixer")&&!o.id.equals("self")&&place.equals(actor(w,o.id).place()));
        if(!someoneElseHere)return;
        if(!habitEligible(w,r,"check",at))return;
        fireHabit(w,r,"check","inspect","伸手推了推旁边的桌子，看看稳不稳，没说话。","路过时伸手推了推旁边的桌子，确认稳不稳，没说话。",at);
    }
    /** 阿满: "抢在冲突之前说话，替别人找台阶。" The wordless version: a decline the rules already
     * recorded as a WorldEvent, in the same place she is standing in right now, is the "冲突" fact -
     * she does not need to have heard the words to reach for the cups between two people. */
    private static final long HABIT_SMOOTH_WINDOW_SECONDS = 300;
    private static void habitSmooth(CompanionWorld w,ResidentState r,Instant at){
        String place=actor(w,r.id).place();
        boolean tensionNearby=w.events.stream().anyMatch(e->"declined".equals(e.type())&&place.equals(e.place())
            &&!e.actorIds().contains("weaver")&&!e.at().isAfter(at)&&Duration.between(e.at(),at).getSeconds()<=HABIT_SMOOTH_WINDOW_SECONDS);
        if(!tensionNearby)return;
        if(!habitEligible(w,r,"smooth",at))return;
        fireHabit(w,r,"smooth","tidy","没说话，把两人中间的杯子往里挪了挪。","没说话，把两个人中间的杯子往里挪了挪，像是想让气氛松一点。",at);
    }

    // ---- place habits (item 1's second half): where someone goes without being asked -------------
    /** The other shape a habit can take: not a label on top of an existing plan, but the plan itself -
     * a resident with nothing already decided (r.plan==null - see step()'s own call site above, which
     * tries this before falling back to the ordinary idle wait) defaults toward a place their own
     * occupation or actingSelf already points at, exactly the way a person walks into a cafe and it
     * simply occurs to them that they could sit down and drink something, long before they consciously
     * decide anything. This walks the same {@link #moveOrSchedule} path any model decision already
     * uses - a travel plan if not there yet, the destination action directly if already there - so a
     * habit is a normal plan the model can simply choose not to repeat next time, never a separate
     * mechanism the rest of the simulation has to know about. Returns whether it fired, so the caller
     * knows whether to fall back to the ordinary idle wait instead. */
    private static boolean maybePlaceHabit(CompanionWorld w,ResidentState r,Instant at){
        if(activeConversation(w,r.id)!=null)return false;
        if(Set.of("sleep","travel","walk","away","tend","wait").contains(actor(w,r.id).activity()))return false;
        return switch(r.id){
            // Signature habit first, own unfinished thing second, and that order matters: the
            // student's own default is the cafe window, and putting anything ahead of it simply
            // stopped him ever going there. It also reads better than it sounds - the signature
            // habit is what takes you somewhere, and once you are there (its own condition is "not
            // already at the cafe") it steps aside and you poke at your own thing instead.
            case "student"->placeHabitStudyAtCafe(w,r,at)||placeHabitStartOwnThing(w,r,at);
            case "artist"->placeHabitSeekInspiration(w,r,at)||placeHabitStartOwnThing(w,r,at);
            // Garden work still comes first whenever it is actually due; the cafe errand below only
            // gets a turn in the minutes that habit's own cooldown or hash roll leaves open - see its
            // own javadoc for why the cafe is the second half of this resident's default, not a
            // replacement for the first.
            // 青叔's own "用东西代替话——递一株苗" is help offered as an object rather than a sentence,
            // which is what putting a hand on somebody's unfinished thing is. It comes after his own
            // garden, which is still his first default, and before the errand that was already the
            // second half of the same instinct.
            // The errand to the cafe stays ahead of lending a hand, and deliberately: his own
            // default already keeps him in the one public place nobody else visits, and the whole
            // point of that errand is that living far and working alone is answered by going toward
            // people. Putting lend-a-hand first quietly undid it - the thing he would lend a hand to
            // is usually the one in his own garden, so he never left.
            case "gardener"->placeHabitTendGarden(w,r,at)||placeHabitBringSeedlingToCafe(w,r,at)
                ||placeHabitLendAHand(w,r,at)||placeHabitStartOwnThing(w,r,at);
            // The gathering comes before minding an empty counter: wanting to be needed is what both
            // of these are, and only one of them ever produces something for anybody to need.
            case "owner"->placeHabitStartOwnThing(w,r,at)||placeHabitMindTheCafe(w,r,at);
            // Lending a hand first, then the trip that was only ever a pretext for lending one.
            case "fixer"->placeHabitLendAHand(w,r,at)||placeHabitCheckCafe(w,r,at);
            // 阿满: "替别人找台阶" without saying anything - see her own habitSmooth, which is the
            // wordless version applied to a room. This is the same instinct applied to a thing.
            case "weaver"->placeHabitLendAHand(w,r,at)||placeHabitBeAroundPeople(w,r,at);
            default->false;
        };
    }
    /** Lands one place habit: moves (or, if already there, simply schedules the destination action
     * for) the resident, then records the departure as a deed exactly like a micro-habit does - only
     * what an observer standing at the starting place would have seen, never why. */
    private static void firePlaceHabit(CompanionWorld w,ResidentState r,String habitId,String action,String place,String reason,String note,Instant at,int duration){
        firePlaceHabit(w,r,habitId,action,place,null,reason,note,at,duration);
    }
    private static void firePlaceHabit(CompanionWorld w,ResidentState r,String habitId,String action,String place,String target,String reason,String note,Instant at,int duration){
        String from=actor(w,r.id).place();
        r.lastHabitAt.put(habitId,at);
        moveOrSchedule(w,r,action,place,target,reason,at,duration);
        recordDeed(w,r.id,action,from,note,at);
    }
    /** 小川: occupation is studying for an exam, and the actual complaint behind this half of item 1
     * ("request_drink 被提供 32 次、选中 0 次") is that nothing in his own context ever gives him a
     * reason to want a drink - a hidden thirst value is exactly what is banned. This is the answer:
     * not a need, a habit. When he has nothing already decided and it is not his own sleep window, he
     * defaults to the cafe's window seat to study, and orders the ordinary cup that goes with it
     * through the same {@link CafeService#request} path request_drink already uses. */
    private static boolean placeHabitStudyAtCafe(CompanionWorld w,ResidentState r,Instant at){
        if("cafe".equals(actor(w,r.id).place())||!routineCues(w,"student",at).isEmpty()||!CafeService.acceptingOrders(w))return false;
        if(!habitEligible(w,r,"study_cafe",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"study_cafe","study","cafe","照老样子来咖啡馆靠窗的位置看书",
            "没多想，又往咖啡馆靠窗那个位置去了。",at,1800);
        CafeService.request(w,r,at);
        return true;
    }
    /** 知夏: "擅长把'没做完'讲成'还在长'" pairs with the same instinct in the other direction - when
     * stuck, she does not force the page, she goes and watches people instead. Watching, not
     * studying, is the point: unlike student's habit above this one never orders anything. */
    private static boolean placeHabitSeekInspiration(CompanionWorld w,ResidentState r,Instant at){
        if("cafe".equals(actor(w,r.id).place())||!CafeService.acceptingOrders(w))return false;
        if(!habitEligible(w,r,"seek_inspiration",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"seek_inspiration","observe","cafe","没想好画什么，先去咖啡馆看看人",
            "没说要去哪，人已经往咖啡馆那边去了。",at,900);
        return true;
    }
    /** 青叔: his own occupation's default place, needing no special condition beyond having nothing
     * else already decided - "手上带点东西" is the {@code work} action he arrives to, not idle observing. */
    private static boolean placeHabitTendGarden(CompanionWorld w,ResidentState r,Instant at){
        if("garden".equals(actor(w,r.id).place()))return false;
        if(!habitEligible(w,r,"tend_garden",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"tend_garden","work","garden","顺路去花园看看",
            "没说什么，顺手拿了点东西，往花园那边去了。",at,1800);
        return true;
    }
    /** 青叔's own second half of the same instinct - "用东西代替话——递一株苗" (ResidentSeed's
     * actingSelf line for him) is an errand, and an errand goes wherever the people are, not only
     * wherever the plants are. A measured full simulated day found him sharing a place with anyone
     * else exactly once: {@link #placeHabitTendGarden} is his only default, and it defaults him
     * toward the one public place ({@code garden}, street-position 14) that none of the other five
     * residents' own place habits ({@link #placeHabitStudyAtCafe}, {@link #placeHabitSeekInspiration},
     * {@link #placeHabitMindTheCafe}, {@link #placeHabitCheckCafe}, {@link #placeHabitBeAroundPeople})
     * ever visit - they all converge on the cafe, the actually busy point on the street (see {@link
     * #STREET_POSITION}'s own doc comment on why). Shortening his walk there was explicitly rejected
     * (see this batch's report) because a walk shorter than a tick's own resolution stops existing on
     * the street at all - so the fix is not a faster trip, it is a second, independent reason to make
     * the trip: living far and working alone is answered by going toward people occasionally, not by
     * making the far end of the street closer than it is. Never touches the garden itself and never
     * decides who he talks to once there - {@link #maybeEncounter} still owns that, exactly as it does
     * for everyone else who already defaults toward the cafe. */
    private static boolean placeHabitBringSeedlingToCafe(CompanionWorld w,ResidentState r,Instant at){
        if("cafe".equals(actor(w,r.id).place())||!CafeService.acceptingOrders(w))return false;
        if(!habitEligible(w,r,"deliver_seedling",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"deliver_seedling","observe","cafe","顺路带一株新苗去咖啡馆",
            "没说什么，顺手拿了盆新苗，往咖啡馆那边去了。",at,900);
        return true;
    }
    /** 阿禾: "想被需要" - idle at home while the counter he runs is open, he defaults back toward it
     * rather than staying put, even before anyone has actually asked for anything. */
    private static boolean placeHabitMindTheCafe(CompanionWorld w,ResidentState r,Instant at){
        if(!"open".equals(w.cafeStatus)||!TownPlaces.homeOf("owner").equals(actor(w,r.id).place()))return false;
        if(!habitEligible(w,r,"mind_cafe",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"mind_cafe","observe","cafe","闲不住，去店里看看",
            "没说什么，起身往店里走了。",at,900);
        return true;
    }
    /** 周野: the same "看看有什么不对" instinct as his own micro-habit above, applied to where he
     * defaults toward when nothing else is decided - the cafe's shared worktable is the one place in
     * town most likely to have something worth checking. */
    private static boolean placeHabitCheckCafe(CompanionWorld w,ResidentState r,Instant at){
        if("cafe".equals(actor(w,r.id).place())||!CafeService.acceptingOrders(w))return false;
        if(!habitEligible(w,r,"check_cafe",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"check_cafe","observe","cafe","顺路去店里看看有没有需要搭把手的",
            "没说什么，往咖啡馆那边走去了。",at,900);
        return true;
    }
    /** 阿满: being near people is what lets her get ahead of a conflict before it starts, so an idle
     * moment defaults her toward wherever people already are. */
    private static boolean placeHabitBeAroundPeople(CompanionWorld w,ResidentState r,Instant at){
        if("cafe".equals(actor(w,r.id).place())||!CafeService.acceptingOrders(w))return false;
        if(!habitEligible(w,r,"be_around_people",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"be_around_people","observe","cafe","没什么事，去咖啡馆那边坐坐",
            "没说什么，往咖啡馆那边去了。",at,900);
        return true;
    }

    /** A shared thing this resident knows about, that by its own nature needs more than one person,
     * that is not finished, and that they have not put their hands on yet. Ones somebody has already
     * started come first: joining something under way is a smaller step than starting something
     * nobody has touched, and it is the one that unblocks {@link #SOLO_PROGRESS_CAP}. */
    private static Project sharedThingToLendAHandTo(CompanionWorld w,ResidentState r,boolean skipOwn){
        return w.projects.stream()
            .filter(p->knows(w,r.id,p.id)&&!Set.of("ready","celebrating").contains(p.status))
            .filter(ResidentSimulation::takesMoreThanOnePerson)
            .filter(p->!p.contributors.contains(r.id)&&(!skipOwn||!r.id.equals(p.ownerId)))
            .filter(p->!"cafe".equals(p.place)||CafeService.acceptingOrders(w))
            .max(Comparator.<Project>comparingInt(p->someoneIsWorkingOnItRightNow(w,p,r.id)?1:0)
                .thenComparingInt(p->p.contributors.size()))
            .orElse(null);
    }
    /** Whether somebody else is, at this exact moment, standing where this thing is and working on
     * it. Ranked above everything else in the choice above, because it is the difference between
     * two people who each did some of one thing and two people doing one thing - and because it is
     * the more human of the two anyway: you lend a hand to someone you can see working, not to an
     * abstract entry on a list. Reads only what anybody standing in that room would see. */
    private static boolean someoneIsWorkingOnItRightNow(CompanionWorld w,Project p,String exceptId){
        return w.residentStates.stream().anyMatch(o->!o.id.equals(exceptId)&&!o.id.equals("self")
            &&o.plan!=null&&Set.of("create","help").contains(o.plan.action())&&p.id.equals(o.plan.targetId())
            &&actor(w,o.id).place().equals(p.place));
    }
    /** 周野: {@link #placeHabitCheckCafe} above already says, in his own actingSelf's words, that he
     * goes to the shop "看看有没有需要搭把手的" - and then observes. That is the whole of it: he
     * arrives to lend a hand and never lends one. This is the step it stops one short of.
     * <p>Why a rule may put a resident's hands on a shared thing at all, when nothing else here does:
     * a communal project stops dead at {@link #SOLO_PROGRESS_CAP} until a second person arrives, and
     * a measured day offered {@code create} 216 times and got it chosen zero, so the second person
     * never came and nothing in this town has ever been finished by more than one person. A man whose
     * own written self is "直接问、直接说" walking past a half-finished shared thing and putting a
     * hand on it before deciding to is precisely the reflex layer - the account of why he did it comes
     * afterwards, from him, through the ordinary deed/explanation path, and may well be wrong.
     * <p>Never his own project: this habit is about other people's unfinished things, which is also
     * the only version of it that can lift a project past the solo cap. */
    private static boolean placeHabitLendAHand(CompanionWorld w,ResidentState r,Instant at){
        Project shared=sharedThingToLendAHandTo(w,r,true);
        if(shared==null)return false;
        // Where he already is, never a trip - the same rule placeHabitStartOwnThing follows, and for
        // a reason worth stating once: a habit that MOVES somebody can permanently kill another whose
        // own condition is "not already there". It happened twice while this was being written (the
        // student stopped ever going to the cafe window; the gardener stopped ever running his
        // errand), and both times the damage was silent. So: the signature habits are the ones that
        // take you somewhere, and these two only ever act on what is already in front of you.
        if(!actor(w,r.id).place().equals(shared.place))return false;
        if(!habitEligible(w,r,"lend_a_hand",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"lend_a_hand","create",shared.place,shared.id,"看见「"+shared.title+"」还搁在那儿，顺手搭把手",
            "没问谁，走过去在「"+shared.title+"」上添了一笔。",at,900);
        return true;
    }
    /** Poking at your own unfinished thing when you have nothing else on. Unlike every other habit in
     * this file this one is not drawn from any single resident's actingSelf, and it applies to
     * everybody, because it is not a personality trait - a person with an unfinished thing of their
     * own, idle, in the place that thing lives, putting a bit more into it is about as close to a
     * universal reflex as this town has.
     * <p>It is here because an idea nobody has started is an idea nobody can join. Somebody has to
     * lay down the first stroke before "剩下的得有人一起动手" is even true of a thing, and the person
     * with the least excuse is whoever wanted it. Only ever their own, and never past the solo cap:
     * once a thing has gone as far as one pair of hands can take it, going back to it alone is not a
     * reflex, it is avoidance, and the rules do not put words in anybody's mouth about that. */
    private static boolean placeHabitStartOwnThing(CompanionWorld w,ResidentState r,Instant at){
        Project own=w.projects.stream()
            .filter(p->r.id.equals(p.ownerId)&&knows(w,r.id,p.id)&&!Set.of("ready","celebrating").contains(p.status))
            .filter(p->!"cafe".equals(p.place)||CafeService.acceptingOrders(w))
            .min(Comparator.comparingInt(p->p.progress))
            .orElse(null);
        if(own==null||own.progress>=SOLO_PROGRESS_CAP)return false;
        // Only ever where they already are. A reflex does not walk you across town - and letting this
        // one do so quietly killed a signature habit: it relocated the student to the cafe before his
        // own cafe-window default ever got a turn, and that default's own condition is "not already
        // at the cafe", so it could never fire again for the rest of the day.
        if(!actor(w,r.id).place().equals(own.place))return false;
        if(!habitEligible(w,r,"own_thing",PLACE_HABIT_MIN_GAP_SECONDS,at))return false;
        firePlaceHabit(w,r,"own_thing","create",own.place,own.id,"手上没别的事，先给「"+own.title+"」弄一点",
            "没跟谁说，先动手给「"+own.title+"」弄了一点。",at,1800);
        return true;
    }

    /** At most this many unaccounted-for deeds are kept per resident. A queue that grows without
     * bound would eventually hand the model a whole day in one prompt; more importantly, a deed
     * nobody got round to accounting for simply stops being available to remember, which is what
     * happens to most of what a person does. */
    private static final int MAX_UNEXPLAINED_DEEDS = 8;

    /** Records something the rules did on a resident's behalf, so it can be accounted for later in
     * that resident's own words. Called from the reflex layer - a habit firing, a routine carrying
     * on - never from a path where a model already chose and already gave its reason.
     * <p>{@code note} must state only what an observer would have seen. The rules do not know why
     * anyone does anything and must never write a motive here; that is the whole point of the
     * split. */
    public static void recordDeed(CompanionWorld w,String residentId,String action,String place,String note,Instant at){
        ResidentState r=state(w,residentId);
        if(r==null||note==null||note.isBlank())return;
        CompanionWorld.Deed deed=new CompanionWorld.Deed();
        deed.id="deed-"+(++w.eventSequence);deed.action=action;deed.place=place;deed.note=note;deed.at=at;
        r.unexplainedDeeds.add(deed);
        while(r.unexplainedDeeds.size()>MAX_UNEXPLAINED_DEEDS)r.unexplainedDeeds.removeFirst();
    }
    public static List<CompanionWorld.Deed> unexplainedDeeds(CompanionWorld w,String residentId){
        ResidentState r=state(w,residentId);
        return r==null?List.of():List.copyOf(r.unexplainedDeeds);
    }
    /** How long a stretch of unaccounted-for behaviour has to be before it is worth accounting for.
     * People do not narrate themselves continuously; they notice afterwards, in a lull, that they
     * have been doing something. Asking after every single deed would cost more model calls than
     * asking before every action did, which would defeat the entire point. */
    private static final int EXPLANATION_MIN_DEEDS = 3;
    public static boolean needsExplanation(CompanionWorld w,String residentId,Instant now){
        ResidentState r=state(w,residentId);
        if(r==null||"self".equals(residentId)||now==null)return false;
        if(activeConversation(w,residentId)!=null)return false;
        return r.unexplainedDeeds.size()>=EXPLANATION_MIN_DEEDS;
    }
    /** Lands a resident's own account of what they have been doing. The deeds named are cleared
     * whether or not they were all mentioned - an account that skips something is still the account
     * this person ended up with, and the unmentioned parts are simply gone, exactly as they would be.
     * <p>The account is written as a {@code reflection}, not an {@code observed}: it is a construction
     * after the fact, and a resident who later retrieves it is retrieving what they decided it meant,
     * not what happened. That distinction is already carried by the memory layers, and the model is
     * told to treat reflection as fallible. */
    public static boolean applyExplanation(CompanionWorld w,String residentId,long residentRevision,List<String> deedIds,String text,List<String> evidenceIds,Instant now){
        ResidentState r=state(w,residentId);
        if(r==null||r.revision!=residentRevision||now==null)return false;
        if(text==null||text.isBlank()||text.length()>200)return false;
        if(deedIds==null||deedIds.isEmpty())return false;
        if(r.unexplainedDeeds.stream().noneMatch(d->deedIds.contains(d.id)))return false;
        List<String> evidence=evidenceIds==null?List.of():evidenceIds;
        if(evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        String place=r.unexplainedDeeds.stream().filter(d->deedIds.contains(d.id)).findFirst().map(d->d.place).orElse(null);
        r.unexplainedDeeds.removeIf(d->deedIds.contains(d.id));
        memory(w,residentId,residentId,"reflection",now,null,text,evidence,6);
        // The account replaces whatever the resident was privately telling themselves. This is the
        // step that keeps the explanation from being decoration: it is now the thing they know about
        // themselves, and it is what the next decision reads.
        r.thought=text;r.revision++;w.revision++;
        event(w,now,"account",place==null?actor(w,residentId).place():place,List.of(residentId),actor(w,residentId).name()+"回头想了想刚才：“"+text+"”",null);
        return true;
    }

    /** How long a face-to-face fact stays worth answering. Past this the moment has gone: you do not
     * walk up to someone ten minutes after noticing them. Also the window inside which a rule-only
     * world (no model at all) falls back to greeting on the resident's behalf. */
    private static final long PENDING_ENCOUNTER_TTL_SECONDS = 90;
    /** What one resident can see of another across a room, as one comparable string: where they both
     * are, what the other is doing, and what the looker themself is doing. This is the whole basis on
     * which a declined encounter gets asked again.
     * <p>It replaces a thirty-minute declined-encounter cooldown, and the reason is that the thirty
     * minutes was ours, not the town's. People do not re-decide whether to say hello on a timer; they
     * re-notice someone when something changes - he closes his book and stands up, he walks in off
     * the street, I finish what I was doing and look up. Generative Agents has the same shape: a
     * reaction is asked of an *observation*, and a scene that has not changed produces no new
     * observation to react to. An earlier twelve-minute version asked one pair the same question six
     * times in a row and got back the same sentence almost verbatim; widening it to thirty only made
     * the same wrong thing rarer.
     * <p>Deliberately only observable things. No {@code energy}, no {@code social}, no
     * {@code lastSocialAt} - not because this string ever reaches a model (it does not; it is
     * simulation bookkeeping like every other cooldown here) but because a change nobody in the room
     * could see is not a reason for anybody in the room to look up again. */
    private static String encounterFingerprint(CompanionWorld w,String residentId,String otherId,String place){
        return place+"|"+actor(w,residentId).activity()+"|"+actor(w,otherId).activity();
    }
    /** Drops the "I already decided to leave them alone" impression for any pair that is no longer
     * standing in the same place. Without this, someone could walk out, come back doing the exact
     * same thing, and be filtered out as unchanged - but walking back in is precisely the case
     * ("他从街上走进来了") this whole mechanism exists to catch. The impression lasts as long as the
     * two are in the room together, and no longer. */
    private static void expireDeclinedEncounters(CompanionWorld w){
        w.declinedEncounters.keySet().removeIf(key->{
            String[] pair=key.split(":",2);
            if(pair.length!=2||state(w,pair[0])==null||state(w,pair[1])==null)return true;
            return !actor(w,pair[0]).place().equals(actor(w,pair[1]).place());
        });
    }

    /** Drops face-to-face facts that reality has overtaken: one of them walked off, one of them is
     * already talking to somebody, or nobody got round to answering in time. A model outage must not
     * make the town silent, so a world running without a model greets on the resident's behalf rather
     * than letting every encounter expire unanswered - the fallback is deliberately the sociable one. */
    private static void expirePendingEncounters(CompanionWorld w,Instant at){
        for(CompanionWorld.PendingEncounter pending:new ArrayList<>(w.pendingEncounters)){
            ResidentState resident=state(w,pending.residentId),other=state(w,pending.otherId);
            boolean stale=resident==null||other==null
                ||!actor(w,pending.residentId).place().equals(pending.place)
                ||!actor(w,pending.otherId).place().equals(pending.place)
                ||activeConversation(w,pending.residentId)!=null||activeConversation(w,pending.otherId)!=null
                ||resident.revision!=pending.residentRevision;
            boolean expired=Duration.between(pending.at,at).getSeconds()>=PENDING_ENCOUNTER_TTL_SECONDS;
            if(!stale&&expired&&!w.modelConversationsEnabled){startLifeConversation(w,resident,other,at);w.pendingEncounters.remove(pending);continue;}
            if(stale||expired)w.pendingEncounters.remove(pending);
        }
    }
    /** The fallback when nothing can answer "do you say anything?" - a mind that does not implement
     * reactions at all, or a rule-only world. Greeting is chosen over silence on purpose: a missing
     * capability should degrade to the town this project is trying to be, not to the empty one it
     * measured before encounters existed. */
    public static void greetWithoutDeciding(CompanionWorld w,String pendingId,Instant now){
        CompanionWorld.PendingEncounter pending=pendingEncounter(w,pendingId);
        if(pending==null)return;
        w.pendingEncounters.remove(pending);
        ResidentState resident=state(w,pending.residentId),other=state(w,pending.otherId);
        if(resident==null||other==null)return;
        if(!actor(w,resident.id).place().equals(pending.place)||!actor(w,other.id).place().equals(pending.place))return;
        if(activeConversation(w,resident.id)!=null||activeConversation(w,other.id)!=null)return;
        startLifeConversation(w,resident,other,now);
    }
    public static CompanionWorld.PendingEncounter pendingEncounter(CompanionWorld w,String id){
        return w.pendingEncounters.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);
    }
    /** Lands the resident's own answer to "someone is standing in front of you". Three answers only,
     * and the model picks: walk up and say something, sit down near them without opening your mouth,
     * or let them be. Declining is a real, recorded outcome - noticing someone and choosing not to
     * approach them is a thing people do all day, and this town has never been able to represent it.
     * The rules never author a word of what gets said; "greet" only opens the conversation, exactly
     * as a resident's own "invite" already does. */
    public static boolean applyReaction(CompanionWorld w,String pendingId,long residentRevision,String reaction,String reason,List<String> evidence,Instant now){
        CompanionWorld.PendingEncounter pending=pendingEncounter(w,pendingId);
        if(pending==null||!Set.of("greet","join","none").contains(reaction))return false;
        ResidentState resident=state(w,pending.residentId),other=state(w,pending.otherId);
        if(resident==null||other==null||resident.revision!=residentRevision)return false;
        if(reason==null||reason.isBlank()||reason.length()>160)return false;
        if(evidence==null||evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(resident.id))))return false;
        boolean stillTogether=actor(w,resident.id).place().equals(pending.place)&&actor(w,other.id).place().equals(pending.place)
            &&activeConversation(w,resident.id)==null&&activeConversation(w,other.id)==null;
        w.pendingEncounters.remove(pending);
        if(!stillTogether)return false;
        String otherName=actor(w,other.id).name();
        switch(reaction){
            case "greet"->{
                startLifeConversation(w,resident,other,now);
                event(w,now,"greeting",pending.place,List.of(resident.id,other.id),actor(w,resident.id).name()+"走过去和"+otherName+"打了个招呼。",null);
            }
            case "join"->{
                // Sitting down near someone without speaking. Deliberately not a conversation: the
                // town has never been able to show two people quietly sharing a table.
                if(resident.plan!=null)suspend(resident,now);
                schedule(w,resident,"join",pending.place,other.id,reason,now,900);
                memory(w,resident.id,resident.id,"observed",now,null,"我在"+placeName(pending.place)+"看见"+otherName+"，没说话，就在旁边坐了下来。",evidence,5);
            }
            default->{
                // Not approaching is still something that happened to this resident, and it is the
                // resident's own reason for it that gets written down, not a rule's guess.
                // Not a cooldown: what they looked like when the answer was "not right now". The
                // rules ask again when that stops being true, and not before.
                w.encounterCooldowns.remove(pairKey(resident.id,other.id));
                w.declinedEncounters.put(pairKey(resident.id,other.id),
                    encounterFingerprint(w,resident.id,other.id,pending.place));
                memory(w,resident.id,resident.id,"observed",now,null,"在"+placeName(pending.place)+"遇见"+otherName+"，"+reason,evidence,4);
            }
        }
        resident.revision++;w.revision++;
        w.modelStatus="模型刚决定了"+actor(w,resident.id).name()+"要不要开口";
        return true;
    }
    /** End-of-day reconciliation for a resident's coarse day plan (item 4): once the local calendar
     * date has moved past the day this plan was formed for, any segment still "pending" (or somehow
     * left "active") is a plan reality wrecked rather than one the resident finished - it becomes
     * exactly one reflection memory naming what did not happen, never a silent success and never a
     * scolding. A fresh plan is formed the next morning through the normal ResidentDirector "dayplan"
     * work item; this method never creates one itself. */
    private static void reconcileDayPlan(CompanionWorld w,ResidentState r,Instant at){
        if(r.dayPlan==null)return;
        String today=at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
        if(today.equals(r.dayPlan.day))return;
        var missed=r.dayPlan.segments.stream().filter(s->!"done".equals(s.status)&&!"skipped".equals(s.status)).map(s->s.label).toList();
        if(!missed.isEmpty())memory(w,r.id,r.id,"reflection",at,null,"今天原本想"+String.join("、",missed)+"，一天过去了，没顾上。",List.of(),6);
        r.dayPlan=null;
    }
    /** A defensive no-op for a {@link ResidentMind} that does not implement day planning at all (the
     * interface's own default throws {@code UnsupportedOperationException} - see
     * {@link com.betterself.growth.town.companion.application.ResidentMind#planDay}). Recorded exactly
     * like a real day plan with zero segments so {@link #reconcileDayPlan} has nothing to call missed
     * and {@code needsDayPlan} in ResidentDirector stops re-asking for the rest of today, without ever
     * touching the world's model-failure/backoff bookkeeping - an unsupported capability is not the
     * same kind of failure as a real network or parsing error. */
    public static void markDayPlanUnavailableForToday(CompanionWorld w,String residentId,Instant now){
        ResidentState r=state(w,residentId);if(r==null)return;
        CompanionWorld.DayPlan plan=new CompanionWorld.DayPlan();
        plan.id="dayplan-unavailable-"+(++w.eventSequence);plan.day=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();plan.formedAt=now;
        r.dayPlan=plan;
    }
    /** Applies a model-proposed day plan (item 4). Coarse on purpose - three or four short segments,
     * never a time-slotted schedule; ResidentSimulation never checks elapsed real time against them,
     * only whether one is still pending when the day rolls over (see {@link #reconcileDayPlan}). */
    public static boolean applyDayPlan(CompanionWorld w,String residentId,long residentRevision,List<String> segments,List<String> evidence,Instant now){
        ResidentState r=state(w,residentId);
        if(r==null||r.revision!=residentRevision||segments==null||segments.size()<3||segments.size()>4)return false;
        for(String s:segments)if(s==null||s.isBlank()||s.length()>40)return false;
        if(evidence!=null&&evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        CompanionWorld.DayPlan plan=new CompanionWorld.DayPlan();
        plan.id="dayplan-"+(++w.eventSequence);plan.day=now.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();plan.formedAt=now;
        for(String s:segments){CompanionWorld.DaySegment seg=new CompanionWorld.DaySegment();seg.label=s;plan.segments.add(seg);}
        r.dayPlan=plan;r.revision++;w.revision++;
        event(w,now,"day_plan",actor(w,residentId).place(),List.of(residentId),actor(w,residentId).name()+"给今天理了个大致的想法。",null);
        return true;
    }
    public static PortableAction portableAction(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null||!"closing".equals(w.cafeStatus)||!"cafe".equals(actor(w,residentId).place()))return null;
        Plan plan=portablePlan(r);if(plan==null)return null;
        int remaining=(int)Math.max(1,Duration.between(at,plan.endsAt()).getSeconds());
        if(r.suspendedAction!=null&&plan==r.suspendedAction.plan)remaining=(int)Math.max(1,Duration.between(plan.startedAt(),plan.endsAt()).getSeconds());
        return new PortableAction(plan.action(),plan.reason(),remaining);
    }
    public static List<String> availableActions(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null)return List.of();
        LinkedHashSet<String> actions=new LinkedHashSet<>(List.of("observe","rest","study","work","read","make","sleep","away","change_work"));
        if(r.plan!=null&&!("cafe".equals(actor(w,residentId).place())&&!"open".equals(w.cafeStatus)))actions.add("continue");
        if(w.projects.stream().anyMatch(p->knows(w,residentId,p.id)&&!Set.of("ready","celebrating").contains(p.status)))actions.add("create");
        if(w.projects.stream().anyMatch(p->knows(w,residentId,p.id)&&p.members.contains(residentId)&&!Set.of("ready","celebrating").contains(p.status)))actions.add("help");
        // Showing people the finished thing. It had a completion branch, a label, and a personality
        // drift driver, and it was in no menu, in no DECISION_ACTIONS, and scheduled by nothing
        // anywhere - so a project that actually got finished could never be shown to anybody, and
        // "celebration" was one of the two drift causes a measured day fired zero times. Offered to
        // whoever helped make it, standing where it is: you do not call people over to something in
        // another building.
        if(w.projects.stream().anyMatch(p->"ready".equals(p.status)&&p.contributors.contains(residentId)
            &&p.place.equals(actor(w,residentId).place())))actions.add("celebrate");
        if(w.residentStates.stream().anyMatch(other->canTalkTo(w,residentId,other.id))){actions.add("invite");actions.add("join");}
        if(CafeService.mayTend(w,residentId)&&"cafe".equals(actor(w,residentId).place())&&CafeService.oldestWaitingRequestId(w)!=null)actions.add("tend");
        if(CafeService.acceptingOrders(w)&&"cafe".equals(actor(w,residentId).place())&&!residentId.equals(CafeService.operatorId(w))
            &&w.serviceRequests.stream().noneMatch(request->residentId.equals(request.requesterId)&&Set.of("waiting","preparing","delivered").contains(request.status)))actions.add("request_drink");
        if(CafeService.mayManage(w,residentId)&&"closed".equals(w.cafeStatus))actions.add("open_cafe");
        if(CafeService.mayManage(w,residentId)&&"open".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place())&&!"sleep".equals(actor(w,residentId).activity()))actions.add("close_cafe");
        if(portableAction(w,residentId,at)!=null)actions.add("continue_home");
        PausedAction paused=pausedAction(w,residentId,at);if(paused!=null&&(!"cafe".equals(paused.place())||"open".equals(w.cafeStatus)))actions.add("resume");
        if(w.projects.stream().filter(project->residentId.equals(project.ownerId)&&!"celebrating".equals(project.status)).count()<2)actions.add("propose");
        return List.copyOf(actions);
    }
    public static boolean configureCafeHours(CompanionWorld w,int openMinute,int closeMinute){
        if(openMinute<0||openMinute>=1440||closeMinute<0||closeMinute>=1440||openMinute==closeMinute)return false;
        w.cafeOpenMinute=openMinute;w.cafeCloseMinute=closeMinute;w.revision++;return true;
    }
    public static boolean configureSleepHours(CompanionWorld w,String residentId,int sleepMinute,int wakeMinute){
        ResidentState r=state(w,residentId);if(r==null||sleepMinute<0||sleepMinute>=1440||wakeMinute<0||wakeMinute>=1440||sleepMinute==wakeMinute)return false;
        r.usualSleepMinute=sleepMinute;r.usualWakeMinute=wakeMinute;r.sleepScheduleSeeded=true;r.revision++;w.revision++;return true;
    }
    private static final Set<String> PORTABLE_ACTIONS=Set.of("study","read","work","make");
    public record PausedAction(String action,String place,String reason,int remainingSeconds) {}
    public static PausedAction pausedAction(CompanionWorld w,String residentId,Instant at){
        ResidentState r=state(w,residentId);if(r==null||r.suspendedAction==null||r.suspendedAction.plan==null)return null;
        Plan plan=r.suspendedAction.plan;int remaining=(int)Math.max(1,Duration.between(plan.startedAt(),plan.endsAt()).getSeconds());String effectivePlace=plan.place();
        if(PORTABLE_ACTIONS.contains(plan.action())&&"cafe".equals(plan.place())&&!"open".equals(w.cafeStatus)&&TownPlaces.isHome(actor(w,residentId).place()))effectivePlace=TownPlaces.homeOf(residentId);
        return new PausedAction(plan.action(),effectivePlace,plan.reason(),remaining);
    }
    private static Plan portablePlan(ResidentState r){
        if(r.plan!=null&&PORTABLE_ACTIONS.contains(r.plan.action()))return r.plan;
        return r.suspendedAction!=null&&r.suspendedAction.plan!=null&&PORTABLE_ACTIONS.contains(r.suspendedAction.plan.action())?r.suspendedAction.plan:null;
    }
    private static boolean continueAtHome(CompanionWorld w,ResidentState r,Instant at){
        PortableAction view=portableAction(w,r.id,at);Plan plan=portablePlan(r);if(view==null||plan==null)return false;
        String target=plan.targetId();r.plan=null;r.suspendedAction=null;
        moveOrSchedule(w,r,view.action(),TownPlaces.homeOf(r.id),target,view.reason(),at,view.remainingSeconds());return true;
    }
    private static boolean closeCafe(CompanionWorld w,ResidentState manager,String speech,Instant at){
        if(!CafeService.closeForDay(w,manager.id,speech,at))return false;
        for(Conversation c:new ArrayList<>(w.conversations))if("active".equals(c.status)&&"cafe".equals(c.place))ConversationLifecycle.finish(w,c,at,"店里开始打烊，这段谈话先停在这里");
        if(manager.plan!=null&&("tend".equals(manager.plan.action())||"travel".equals(manager.plan.action())&&"tend".equals(manager.desiredAction))){manager.plan=null;manager.suspendedAction=null;}
        moveFocusedAvatarHome(w,at);return true;
    }
    private static void moveFocusedAvatarHome(CompanionWorld w,Instant at){
        if(w.avatar==null||!"cafe".equals(w.avatar.place()))return;
        Actor a=w.avatar;String home=TownPlaces.homeOf("self");
        w.avatar=new Actor(a.id(),a.name(),a.role(),home,a.activity(),a.label(),a.x(),a.y(),a.until());
        TownPlaces.release(w,"self");TownPlaces.claim(w,"self",home,Set.of("focus","study").contains(a.activity())?"desk":null,at);
    }
    private static int sleepDurationSeconds(CompanionWorld w,ResidentState r,Instant at){
        ZonedDateTime local=at.atZone(ZoneId.of(w.timezone));int wakeMinute=r.sleepScheduleSeeded?r.usualWakeMinute:7*60;
        ZonedDateTime wake=local.withHour(wakeMinute/60).withMinute(wakeMinute%60).withSecond(0).withNano(0);
        if(!wake.isAfter(local))wake=wake.plusDays(1);
        if(!routineCues(w,r.id,at).isEmpty())return (int)Math.max(90*60,Duration.between(local,wake).getSeconds());
        double hours=Math.max(1.5,Math.min(4.0,(65-r.energy)/10.0));return (int)Math.round(hours*3600);
    }
    private static final Set<String> DECISION_ACTIONS=Set.of("continue","resume","observe","create","help","celebrate","invite","join","rest","sleep","study","work","read","make","request_drink","tend","open_cafe","close_cafe","continue_home","away","offer_assist","offer_delegate","offer_takeover","accept_work","change_work");
    /** How many refusals in a row before this resident stops being asked for a while, and how long
     * that while can grow to. This is a retry backoff, not a judgement about how often a person
     * reconsiders their day - the situation the question was asked in has to change before the same
     * answer can land, and asking again in the meantime buys nothing and costs a model call. Both
     * numbers are bounds on waste: three attempts is enough to rule out a one-off collision, and
     * fifteen minutes is short enough that a resident whose world has moved on is not left stranded.
     * Any decision that lands clears it. */
    private static final int DECISION_REJECTIONS_BEFORE_BACKOFF = 3;
    private static final long MAX_DECISION_BACKOFF_SECONDS = 15*60;
    public static void recordDecisionOutcome(ResidentState r,boolean applied,Instant now){
        if(applied){r.consecutiveDecisionRejections=0;r.decisionRetryAfter=null;return;}
        r.consecutiveDecisionRejections++;
        if(r.consecutiveDecisionRejections<DECISION_REJECTIONS_BEFORE_BACKOFF)return;
        long seconds=Math.min(MAX_DECISION_BACKOFF_SECONDS,
            60L<<Math.min(8,r.consecutiveDecisionRejections-DECISION_REJECTIONS_BEFORE_BACKOFF));
        r.decisionRetryAfter=now.plusSeconds(seconds);
    }

    public static boolean applyDecision(CompanionWorld w,String residentId,long residentRevision,long intentRevision,String place,String action,String target,String reason,String speech,List<String> evidence,Instant now) {
        ResidentState r=state(w,residentId);if(r==null||r.revision!=residentRevision||w.intentRevision!=intentRevision||!DECISION_ACTIONS.contains(action))return false;
        if(reason==null||reason.isBlank()||reason.length()>160||speech!=null&&speech.length()>180)return false;
        if(evidence==null||evidence.stream().anyMatch(id->w.memories.stream().noneMatch(m->m.id().equals(id)&&m.ownerId().equals(residentId))))return false;
        // "away" (item 8) genuinely leaves the map: its own place is a sentinel, not one of the four
        // real locations, so it is handled before the generic place-containment check below ever runs.
        if("away".equals(action)){
            if(activeConversation(w,residentId)!=null)return false;
            if(r.plan!=null)suspend(r,now);
            TownPlaces.release(w,residentId);
            int duration=Math.max(600,Math.min(2700,600+Math.floorMod(reason.hashCode()+(int)now.getEpochSecond(),2100)));
            r.plan=new Plan("p-"+(++w.eventSequence),"away","away",target,reason,now,now.plusSeconds(duration));
            r.revision++;r.thought=reason;
            replaceActor(w,residentId,"away","away",reason,r.plan.endsAt());
            if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,r.goal,reason,evidence,7);
            w.modelStatus="模型刚让"+actor(w,residentId).name()+"暂时出门了";w.revision++;
            event(w,now,"away","street",List.of(residentId),actor(w,residentId).name()+"出门去处理自己的事，暂时不在小街上。",target);
            return true;
        }
        // The model still speaks of "home" generically; the resident's own home is what that resolves to.
        String resolvedPlace="home".equals(place)?TownPlaces.homeOf(residentId):place;
        if(!TownPlaces.contains(w,resolvedPlace)||TownPlaces.isHome(resolvedPlace)&&!resolvedPlace.equals(TownPlaces.homeOf(residentId)))return false;
        if("continue".equals(action)){
            if("cafe".equals(actor(w,residentId).place())&&!"open".equals(w.cafeStatus))return false;
            if(r.plan!=null){r.thought=reason;return appliedThought(w,r,residentId,reason,r.plan.targetId(),now);}
            if(r.suspendedAction==null)return false;Plan paused=r.suspendedAction.plan;resumeSuspended(w,r,now);
            if(r.plan==null)return false;return appliedThought(w,r,residentId,reason,paused==null?null:paused.targetId(),now);
        }
        if("continue_home".equals(action)){
            if(!resolvedPlace.equals(TownPlaces.homeOf(residentId))||!continueAtHome(w,r,now))return false;
            return appliedThought(w,r,residentId,reason,null,now);
        }
        if("resume".equals(action)){
            PausedAction paused=pausedAction(w,residentId,now);if(paused==null||!resolvedPlace.equals(paused.place())||("cafe".equals(paused.place())&&!"open".equals(w.cafeStatus)))return false;
            Plan stored=r.suspendedAction.plan;
            if(!stored.place().equals(paused.place()))r.suspendedAction.plan=new Plan(stored.id(),stored.action(),paused.place(),stored.targetId(),stored.reason(),stored.startedAt(),stored.endsAt());
            r.plan=null;resumeSuspended(w,r,now);if(r.plan==null)return false;
            return appliedThought(w,r,residentId,reason,r.plan.targetId(),now);
        }
        if("close_cafe".equals(action)){
            if(!"cafe".equals(resolvedPlace)||!closeCafe(w,r,speech,now))return false;
            return appliedThought(w,r,residentId,reason,null,now);
        }
        if("open_cafe".equals(action)){
            if(!"cafe".equals(resolvedPlace)||!CafeService.mayManage(w,residentId)||!"closed".equals(w.cafeStatus))return false;
            if(r.plan!=null)suspend(r,now);
            moveOrSchedule(w,r,"open_cafe","cafe",null,reason,now,30);
            return appliedThought(w,r,residentId,reason,null,now);
        }
        if("request_drink".equals(action)){
            if(!"cafe".equals(resolvedPlace)||!"cafe".equals(actor(w,residentId).place())||!availableActions(w,residentId,now).contains("request_drink"))return false;
            Plan current=r.plan;int before=w.serviceRequests.size();CafeService.request(w,r,now);if(w.serviceRequests.size()==before)return false;
            if(current==null)moveOrSchedule(w,r,"rest","cafe",w.serviceRequests.getLast().id,"等刚才点的饮料",now,1200);
            else if(!"rest".equals(current.action())){suspend(r,now);moveOrSchedule(w,r,"rest","cafe",w.serviceRequests.getLast().id,"等刚才点的饮料",now,1200);}
            return appliedThought(w,r,residentId,reason,w.serviceRequests.getLast().id,now);
        }
        // A closed cafe is not a place you can go and use. But a cafe that is CLOSING still has
        // people standing in it, and one of them has to be able to do something: the operator, inside
        // his own shop while it emptied, chose to sit down 476 times and was refused 408 of them,
        // because "rest, here" named the cafe as its place. Winding-down actions only - sitting for a
        // moment or looking around while the room empties is what people do; starting a half-hour
        // study session in a shop that has just called last orders is not.
        if("cafe".equals(resolvedPlace)&&!"open".equals(w.cafeStatus)
            &&!("closing".equals(w.cafeStatus)&&"cafe".equals(actor(w,residentId).place())&&Set.of("rest","observe").contains(action)))return false;
        if("sleep".equals(action)&&!resolvedPlace.equals(TownPlaces.homeOf(residentId)))return false;
        if(Set.of("create","help").contains(action)){Project p=project(w,target);if(p==null||!knows(w,r.id,p.id)||!p.place.equals(resolvedPlace)||Set.of("ready","celebrating").contains(p.status))return false;}
        if(action.equals("celebrate")){Project p=project(w,target);if(p==null||!"ready".equals(p.status)||!p.contributors.contains(r.id)||!p.place.equals(resolvedPlace))return false;}
        if(action.equals("invite")&&(target==null||!canTalkTo(w,residentId,target)))return false;
        // "join" (item 3): sit down with someone already there. It is a physical positioning choice,
        // not itself a conversation - it deliberately reuses canTalkTo's same-place/available check
        // rather than requiring the stricter "no one is already mid-conversation with them" nuance
        // invite needs, since sitting near someone who is quietly reading is perfectly ordinary.
        if(action.equals("join")&&(target==null||!canTalkTo(w,residentId,target)||!resolvedPlace.equals(actor(w,target).place())))return false;
        if(action.equals("tend")){
            if(!"cafe".equals(resolvedPlace)||!CafeService.mayTend(w,residentId))return false;
            if(target==null)target=CafeService.oldestWaitingRequestId(w);
            String requestId=target;
            if(requestId==null||w.serviceRequests.stream().noneMatch(request->requestId.equals(request.id)&&"waiting".equals(request.status)&&"cafe".equals(request.place)))return false;
            if(r.plan!=null)suspend(r,now);CafeService.beginPreparing(w,target,now);
        }
        if(Set.of("offer_assist","offer_delegate","offer_takeover","accept_work").contains(action))return false;
        if(action.equals("change_work")){if(!changeOccupation(w,residentId,reason,speech,now))return false;return appliedThought(w,r,residentId,reason,null,now);}
        // Model may enrich this resident's current speaking turn; never invent the other party's reply.
        Conversation c=activeConversation(w,residentId);
        if(c!=null) {
            if(speech==null||speech.isBlank()||!actor(w,r.id).place().equals(c.place))return false;
            c.turns.add(new Turn(r.id,speech,now));c.updatedAt=now;
            for(String listener:c.participantIds)if(!listener.equals(r.id)&&actor(w,listener).place().equals(c.place))
                memory(w,listener,r.id,"heard",now,c.topicId,actor(w,r.id).name()+"当面说：“"+speech+"”",evidence,6);
            replaceActor(w,r.id,c.place,"talk",speech,now.plusSeconds(24));
        } else {
            if(Set.of("rest","sleep").contains(action)&&r.plan!=null){
                suspend(r,now);
                if(!"open".equals(w.cafeStatus)&&"cafe".equals(actor(w,r.id).place())&&TownPlaces.isHome(resolvedPlace)
                    &&r.suspendedAction!=null&&r.suspendedAction.plan!=null&&PORTABLE_ACTIONS.contains(r.suspendedAction.plan.action())){
                    Plan paused=r.suspendedAction.plan;
                    r.suspendedAction.plan=new Plan(paused.id(),paused.action(),TownPlaces.homeOf(r.id),paused.targetId(),paused.reason(),paused.startedAt(),paused.endsAt());
                }
            }
            else if(!"tend".equals(action))r.suspendedAction=null;
            if(Set.of("work","read","make").contains(action))setLifeIntent(w,r,null,reason,"active",now);
            // How long the resident is actually busy with what they just chose. "observe" used to
            // fall through to the 60-second default, which is what made it a heartbeat rather than an
            // activity: a resident who chose to look around was asked to decide again one simulated
            // minute later, saw the same street, chose to look around again, and wrote a near-identical
            // reflection each time. One resident spent 169 of his 194 model calls in that loop and took
            // 41% of the whole town's decisions. Looking around is a stretch of someone's day, so it
            // gets the length of one.
            int duration=switch(action){
                case "tend"->CafeService.PREP_SECONDS;
                case "sleep"->sleepDurationSeconds(w,r,now);
                case "rest"->1200;
                case "join"->900;
                case "observe"->900;
                case "study","read","work","make"->1800;
                // Long enough to actually be a gathering rather than a gesture at one.
                case "celebrate"->1800;
                default->60;
            };
            moveOrSchedule(w,r,action,resolvedPlace,target,reason,now,duration);
        }
        if(!evidence.isEmpty())memory(w,r.id,r.id,"reflection",now,r.goal,reason,evidence,7);
        w.modelStatus="模型刚为"+actor(w,r.id).name()+"补充了一个念头";w.revision++;r.revision++;
        event(w,now,"thought",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"想了想："+reason,target);
        return true;
    }
    private static boolean appliedThought(CompanionWorld w,ResidentState r,String residentId,String reason,String target,Instant now){w.modelStatus="模型刚为"+actor(w,residentId).name()+"补充了一个念头";w.revision++;r.revision++;event(w,now,"thought",actor(w,r.id).place(),List.of(r.id),actor(w,r.id).name()+"想了想："+reason,target);return true;}
    private static void perceive(CompanionWorld w,ResidentState r,Instant now) {
        Actor a=actor(w,r.id);if(a.activity().equals("walk")||a.activity().equals("sleep"))return;
        boolean detailSensitive=Personality.of(r).sensitivity()>=65;
        for(Project p:w.projects)if(p.place.equals(a.place())&&(p.progress>0||knows(w,r.id,p.id))) {
            ProjectKnowledge prior=r.knownProjects.get(p.id);
            boolean changed=prior!=null&&!prior.status().equals(p.status);
            r.knownProjects.put(p.id,new ProjectKnowledge(p.id,p.place,p.status,p.progress,now,r.id));
            // Only residents who pay close attention to detail bother writing down an ambient change
            // in something they were not part of; everyone else's private tracking above still
            // updates, just silently and unrecorded - it never becomes a memory they can retrieve.
            if(changed&&detailSensitive&&!p.contributors.contains(r.id))
                memory(w,r.id,p.ownerId,"observed",now,p.id,"路过时注意到「"+p.title+"」的样子变了，好像又往前推进了一点。",List.of(),4);
        }
    }
    private static String knownStatus(ResidentState r,String id){ProjectKnowledge p=r.knownProjects.get(id);return p==null?"idea":p.status();}
    /** How far a project can get on one person's repeated work before it simply stops. A measured day
     * produced zero completions and this is why: four of the five projects in town need more than one
     * pair of hands, they all sat here, and nothing anywhere said so. The number was invisible in the
     * only place it mattered - see ResidentDirector's projectStage, which now spends it on a sentence
     * rather than mapping 75 to a cheerful "进行中". */
    public static final int SOLO_PROGRESS_CAP = 75;
    /** Whether this project is, by its own nature, something more than one person has to be part of.
     * A property of the project as it was described when anyone first heard of it ("收集四个人眼里的
     * 小街"), not live state - so telling a resident this reveals nothing they were not already told. */
    public static boolean takesMoreThanOnePerson(Project p){return p!=null&&p.needed>1;}
    public static String knownPlace(ResidentState r,Project p){ProjectKnowledge known=r.knownProjects.get(p.id);return known==null?p.place:known.place();}
    public static ResidentState state(CompanionWorld w,String id){return w.residentStates.stream().filter(r->r.id.equals(id)).findFirst().orElse(null);}
    public static boolean mayTend(CompanionWorld w,String id){return CafeService.mayTend(w,id);}
    public static String cafeOperatorId(CompanionWorld w){return CafeService.operatorId(w);}
    /** Explicit authoring entry point for a future manually designed neighbour.  No simulation path
     * calls this and there is no automatic migration/recruitment: callers supply their id, name,
     * role and livelihood description, then the resident gets the same neutral state/home/plan
     * machinery as everyone else. */
    public static Actor actor(CompanionWorld w,String id){return "self".equals(id)?w.avatar:w.residents.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow();}
    public static Project project(CompanionWorld w,String id){return w.projects.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);}
    /** Whether this resident knows of a thing at all. Two ways, and both are ordinary: they carry a
     * memory that is about it (anything but a reflection - you cannot come to know a fact by
     * speculating), or they carry an entry about it in their own knownProjects, which is exactly what
     * "what I know about this project" means and is written by every legitimate path there is -
     * seeding, being told in conversation, being invited, and watching somebody work on it.
     * <p>The second half used to be missing, and it was only ever true by accident that it did not
     * matter: knownProjects happened to be populated in step with a memory. It stopped being true the
     * moment residents could hear of a project without also being handed a progress figure. */
    public static boolean knows(CompanionWorld w,String id,String topic){
        if(topic==null)return false;
        ResidentState r=state(w,id);
        if(r!=null&&r.knownProjects.containsKey(topic))return true;
        return w.memories.stream().anyMatch(m->m.ownerId().equals(id)&&Objects.equals(m.topicId(),topic)&&!m.sourceType().equals("reflection"));
    }
    public static Conversation activeConversation(CompanionWorld w,String id){return w.conversations.stream().filter(c->c.status.equals("active")&&c.participantIds.contains(id)).findFirst().orElse(null);}
    /** Each side's own private affection number moves independently, scaled by that side's own
     * emotional volatility - not by the same shared delta. A applies its own scaled change to its own
     * view of B, and B applies its own (generally different) scaled change to its own view of A;
     * neither ever reads or writes the other's number. Over many events with different partners and
     * different histories this is enough for A's feeling about B and B's feeling about A to genuinely
     * diverge - without hand-scripting who likes whom. */
    static void relation(ResidentState a,ResidentState b,int delta){
        bump(a,b.id,scaledDelta(a,delta));
        bump(b,a.id,scaledDelta(b,delta));
    }
    private static void bump(ResidentState owner,String otherId,int delta){owner.relationships.compute(otherId,(k,v)->Math.max(0,Math.min(100,(v==null?40:v)+delta)));}
    private static int scaledDelta(ResidentState owner,int delta){return (int)Math.round(delta*Personality.of(owner).intensity());}
    static void replaceActor(CompanionWorld w,String id,String place,String activity,String label,Instant until){
        // The avatar is not in w.residents (see ResidentSeed's note on that list staying exactly the
        // four NPCs the frontend already renders) - "self" resolves through w.avatar instead, exactly
        // like the read side (actor()) already does. Without this, ResidentSimulation could compute a
        // plan for the avatar (item 7) but the change would silently vanish: this write is a no-op
        // against a list "self" was never in.
        if("self".equals(id)) {
            if(w.avatar==null)return;
            w.avatar=new Actor(id,w.avatar.name(),w.avatar.role(),place,activity,label,w.avatar.x(),w.avatar.y(),until);
            ResidentState r=state(w,id);if(r!=null)r.selfActivitySignature=place+"|"+activity+"|"+until;
            return;
        }
        for(int i=0;i<w.residents.size();i++){Actor a=w.residents.get(i);if(a.id().equals(id))w.residents.set(i,new Actor(id,a.name(),a.role(),place,activity,label,a.x(),a.y(),until));}
    }
    static void replaceRole(CompanionWorld w,String id,String role){
        if("self".equals(id)) {
            if(w.avatar==null)return;
            w.avatar=new Actor(id,w.avatar.name(),role,w.avatar.place(),w.avatar.activity(),w.avatar.label(),w.avatar.x(),w.avatar.y(),w.avatar.until());
            return;
        }
        for(int i=0;i<w.residents.size();i++){Actor a=w.residents.get(i);if(a.id().equals(id))w.residents.set(i,new Actor(id,a.name(),role,a.place(),a.activity(),a.label(),a.x(),a.y(),a.until()));}
    }
    static void project(CompanionWorld w,String id,String title,String kind,String place,String owner,String object,String description,int needed){Project p=new Project();p.id=id;p.title=title;p.kind=kind;p.place=place;p.ownerId=owner;p.objectKind=object;p.description=description;p.status="idea";p.needed=needed;p.members.add(owner);w.projects.add(p);}
    static String memory(CompanionWorld w,String owner,String source,String type,Instant at,String topic,String text,List<String> evidence,int importance){
        return memory(w,owner,source,type,at,topic,text,evidence,importance,null);
    }
    /** Same as the nine-argument form, plus an optional supersession key. A non-null key first flips
     * every earlier memory this owner has under the same key to superseded (see Memory's own doc
     * comment) before the new one is written - this is the only place supersession ever happens, so
     * a reflection/belief's key is always resolved against the owner's own history, never anyone
     * else's. */
    static String memory(CompanionWorld w,String owner,String source,String type,Instant at,String topic,String text,List<String> evidence,int importance,String supersedesKey){
        if(type.equals("reflection")&&supersedesKey==null){Memory existing=w.memories.stream().filter(m->m.ownerId().equals(owner)&&m.sourceType().equals(type)&&m.text().equals(text)).findFirst().orElse(null);if(existing!=null)return existing.id();}
        if(supersedesKey!=null)supersedePrevious(w,owner,supersedesKey);
        String id="m2-"+(++w.eventSequence);w.memories.add(new Memory(id,owner,source,type,at,text,topic,evidence,importance,supersedesKey,false));while(w.memories.size()>200) {
            Set<String> referenced=new HashSet<>();
            for(Memory m:w.memories)if(m.evidenceIds()!=null)referenced.addAll(m.evidenceIds());
            // CafeService.reflectOnDuty holds memory ids on the owner's own state
            // (dutyComplaintEvidenceIds / dutyInterruptionEvidenceIds) between when they are recorded
            // and when enough of them accumulate to actually be cashed into a reflection's evidence -
            // now potentially several reflection windows later, since evidence below threshold is
            // meant to keep accumulating rather than being wiped. Those ids are a real, live reference
            // even though no memory's own evidenceIds names them yet; protect them the same way, or a
            // held id can be evicted here and a later reflection ends up citing a memory that no
            // longer exists.
            for(ResidentState r:w.residentStates){referenced.addAll(r.dutyComplaintEvidenceIds);referenced.addAll(r.dutyInterruptionEvidenceIds);}
            // Eviction respects the memory layers (see CompanionRecall.tier/Memory's doc comment):
            // raw observation is cheapest and goes first, a one-off reflection next, and a standing
            // belief is protected until nothing lower-tier is left to remove. Within a tier, the
            // oldest goes first - this is capacity trimming, not a judgement about which memory is
            // more "true".
            Memory removable=w.memories.stream().filter(m->!m.id().equals(id)&&!referenced.contains(m.id()))
                .min(Comparator.<Memory>comparingInt(m->CompanionRecall.tier(m.sourceType())).thenComparing(Memory::at)).orElse(null);
            if(removable==null)break;
            w.memories.remove(removable);
        }return id;}
    /** Flags every one of this owner's earlier, not-yet-superseded memories sharing supersedesKey.
     * The old memory is rewritten in place (same id, same everything else) rather than removed - see
     * Memory's own doc comment on why the evidence chain has to stay intact. */
    private static void supersedePrevious(CompanionWorld w,String owner,String supersedesKey){
        for(int i=0;i<w.memories.size();i++){
            Memory m=w.memories.get(i);
            if(m.ownerId().equals(owner)&&supersedesKey.equals(m.supersedesKey())&&!m.superseded())
                w.memories.set(i,new Memory(m.id(),m.ownerId(),m.sourceId(),m.sourceType(),m.at(),m.text(),m.topicId(),m.evidenceIds(),m.importance(),m.supersedesKey(),true));
        }
    }
    private static void deduplicateReflections(CompanionWorld w) {
        Map<String,String> canonical=new HashMap<>(),replacements=new HashMap<>();
        List<Memory> kept=new ArrayList<>();
        for(Memory m:w.memories){
            // A memory carrying a supersession key is never collapsed by this text-identity dedupe:
            // two beliefs can legitimately share the exact same wording at two different times (the
            // resident re-affirming the same conclusion), and each still needs its own id so
            // supersedePrevious above can tell which one is current.
            String previous=m.sourceType().equals("reflection")&&m.supersedesKey()==null?canonical.putIfAbsent(m.ownerId()+"\n"+m.text(),m.id()):null;
            if(previous==null)kept.add(m);else replacements.put(m.id(),previous);
        }
        if(replacements.isEmpty())return;
        w.memories=kept.stream().map(m->new Memory(m.id(),m.ownerId(),m.sourceId(),m.sourceType(),m.at(),m.text(),m.topicId(),
            m.evidenceIds()==null?List.of():m.evidenceIds().stream().map(id->replacements.getOrDefault(id,id)).distinct().toList(),m.importance(),m.supersedesKey(),m.superseded())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
    /** Minimum simulated time between two reflections for the same resident - reflection happens a
     * few times a day, not on a fixed clock and never every tick. Combined with the importance
     * threshold below, an ordinary resident lands single digits of reflections per day: a run of
     * eventful hours can trigger a couple of these, and the daily boundary trigger adds at most one
     * more for a quiet day that never crossed the threshold on its own. */
    private static final long REFLECTION_MIN_GAP_SECONDS = 3*3600L;
    /** How much fresh, unreflected-on experience (summed importance) it takes before there is
     * "enough" for this resident to have something worth thinking over - the same bar the earlier,
     * since-removed rule-authored reflect() used, kept here because it already encoded a defensible
     * amount of accumulated life rather than an arbitrary tick count. */
    private static final int REFLECTION_IMPORTANCE_THRESHOLD = 24;
    private static final int REFLECTION_SOURCE_LIMIT = 20;
    /** Whether this resident has accumulated enough new, not-yet-reflected-on experience (or reached
     * their own day's end) to be worth a real reflection. This never decides WHAT they conclude -
     * only that today handed them enough material, or that the day is closing, so it is time to look
     * back. A model call (outside this module) does the actual thinking; see {@link #reflectionSource}
     * for what it gets to look at and {@link #applyReflection} for how its conclusion lands. */
    public static boolean needsReflection(CompanionWorld w,String residentId,Instant now){
        ResidentState r=state(w,residentId);
        if(r==null||"self".equals(residentId)||r.lastReflectionAt==null||now==null)return false;
        long gap=Duration.between(r.lastReflectionAt,now).getSeconds();
        if(gap<REFLECTION_MIN_GAP_SECONDS)return false;
        int freshImportance=w.memories.stream()
            .filter(m->m.ownerId().equals(residentId)&&m.at()!=null&&m.at().isAfter(r.lastReflectionAt)&&!m.at().isAfter(now))
            // Only raw experience counts as "something happened since I last thought this through" -
            // an earlier reflection or belief is the product of thinking, not new material for it.
            .filter(m->CompanionRecall.tier(m.sourceType())==0)
            .mapToInt(Memory::importance).sum();
        boolean enoughHappened=freshImportance>=REFLECTION_IMPORTANCE_THRESHOLD;
        // The day-boundary trigger fires even on a quiet day that never crossed the importance bar -
        // it is the catch-all that guarantees at least one reflection per day, not conditioned on
        // there being any fresh experience at all.
        boolean dayBoundary=!routineCues(w,residentId,now).isEmpty()&&!sameLocalDate(w,r.lastReflectionAt,now);
        return enoughHappened||dayBoundary;
    }
    private static boolean sameLocalDate(CompanionWorld w,Instant a,Instant b){
        ZoneId zone=ZoneId.of(w.timezone);
        return a.atZone(zone).toLocalDate().equals(b.atZone(zone).toLocalDate());
    }
    /** The material a reflection is allowed to draw on: this resident's own memories, nothing from
     * anyone else's, ranked by {@link CompanionRecall}'s own scoring with no particular question in
     * mind (an open browse, not an answer to a query) and capped so a single reflection cannot read
     * the resident's entire life. Superseded memories are excluded the same way retrieval normally
     * excludes them - a reflection reasons from what this resident currently believes and has
     * observed, not from conclusions they have already moved past. */
    public static List<Memory> reflectionSource(CompanionWorld w,String residentId,Instant now){
        if(state(w,residentId)==null||now==null)return List.of();
        return CompanionRecall.retrieve(w.memories,residentId,"",now,REFLECTION_SOURCE_LIMIT);
    }
    /** Lands one conclusion a reflection (a model call outside this module) produced. Every evidence
     * id must be a real memory this same resident owns, or nothing is written at all - this is the
     * one guard against a fabricated or borrowed memory becoming "evidence" for a belief. A non-null
     * supersedesKey marks the conclusion as a standing belief (see Memory's own doc comment) and
     * retires whatever this resident previously believed under the same key; null means a one-off
     * reflection that does not stand in for anything earlier. residentRevision is the same optimistic
     * lock every other resident-mutating rule in this class already uses. */
    public static boolean applyReflection(CompanionWorld w,String residentId,long residentRevision,String text,List<String> evidenceIds,String supersedesKey,Instant now){
        ResidentState r=state(w,residentId);
        if(r==null||r.revision!=residentRevision||now==null)return false;
        if(text==null||text.isBlank()||text.length()>200)return false;
        if(evidenceIds==null||evidenceIds.isEmpty())return false;
        for(String evidenceId:evidenceIds)
            if(w.memories.stream().noneMatch(m->m.id().equals(evidenceId)&&m.ownerId().equals(residentId)))return false;
        if(supersedesKey!=null&&(supersedesKey.isBlank()||supersedesKey.length()>80))return false;
        if(supersedesKey!=null&&!validHabitKey(residentId,supersedesKey))return false;
        // A conclusion that names what it supersedes is, by construction, standing in for the
        // resident's ongoing view of a recurring topic - that is exactly what a belief is (see
        // Memory's doc comment). One that supersedes nothing is a one-off reflection instead. The
        // rules never decide which conclusion to reach; they only decide, from the shape of what the
        // model already told them, which of the two durability tiers it lands in.
        String type=supersedesKey!=null?"belief":"reflection";
        int importance=supersedesKey!=null?9:8;
        // findFirst() throws on a null element, so this must find the memory first and read its
        // topicId afterwards - a raw observation with no topic is completely ordinary, and mapping
        // before findFirst turned that into a NullPointerException.
        String topic=w.memories.stream().filter(m->m.id().equals(evidenceIds.get(0))).findFirst().map(Memory::topicId).orElse(null);
        memory(w,residentId,residentId,type,now,topic,text,List.copyOf(evidenceIds),importance,supersedesKey);
        r.thought=text;r.lastReflectionAt=now;r.revision++;w.revision++;
        return true;
    }
    static List<String> ownEvidence(CompanionWorld w,String id,String topic){return w.memories.stream().filter(m->m.ownerId().equals(id)&&Objects.equals(m.topicId(),topic)).sorted(Comparator.comparing(Memory::at).reversed()).limit(2).map(Memory::id).toList();}
    static void event(CompanionWorld w,Instant at,String type,String place,List<String> ids,String text,String project){w.events.add(new WorldEvent("e-"+(++w.eventSequence),at,type,place,ids,text,project));while(w.events.size()>80)w.events.removeFirst();if(w.avatar!=null&&w.avatar.place().equals(place)&&Set.of("ready","agreement","change_of_mind","celebration").contains(type)){w.diary.add(new Entry("d2-"+w.eventSequence,at,"路过时看见："+text));while(w.diary.size()>80)w.diary.removeFirst();}}
    private static double clamp(double v){return Math.max(0,Math.min(100,v));}
    private static String placeName(String place){if(TownPlaces.isHome(place))return"住处";return switch(place){case "cafe"->"咖啡馆";case "garden"->"花园";default->"小街";};}

    /** The user's avatar is the fifth resident: it shares this same ResidentState/position model so
     * the four NPCs can perceive it and contend with it for a seat, but nothing here drives its
     * activity - that stays with CompanionRules and the user's intents. Idempotent: safe to call on
     * every advance, including for saves from before the avatar had a state at all. */
    public static ResidentState ensureAvatarState(CompanionWorld w) {
        ResidentState existing=state(w,"self");
        if(existing!=null)return existing;
        ResidentState r=new ResidentState();r.id="self";r.energy=70;r.social=60;r.curiosity=60;r.mood="如常";r.revision=1;
        w.residentStates.add(r);return r;
    }
    /** Repairs an older save: gives it the location/position catalog and an avatar state if it is
     * missing either, and moves anyone still parked at the old single shared "home" into their own
     * home place. A brand-new world never needs this - {@link #initialize} already does it. */
    private static void reconcileLegacyPlaces(CompanionWorld w,Instant now) {
        TownPlaces.seed(w);ensureAvatarState(w);
        for(ResidentState r:w.residentStates) {
            if(r.id.equals("self"))continue;
            Actor a=actor(w,r.id);
            if(a.place().equals("home")){
                String home=TownPlaces.homeOf(r.id);replaceActor(w,r.id,home,a.activity(),a.label(),a.until());
                // A pre-place save can say "sleeping at home" while its old flat plan still points
                // at the shared cafe.  The visible, concrete old action wins during repair; without
                // this it immediately walks the resident back out of their newly repaired home.
                if("sleep".equals(a.activity()))r.plan=new Plan("legacy-sleep-"+r.id,"sleep",home,null,a.label(),now,a.until());
            }
            if(r.positionId==null||TownPlaces.position(w,r.positionId)==null){String place=actor(w,r.id).place();TownPlaces.claim(w,r.id,place,r.plan==null?null:preferredKind(r.plan.action(),place),now);}
        }
        if(w.avatar!=null) {
            if(w.avatar.place().equals("home"))w.avatar=new Actor(w.avatar.id(),w.avatar.name(),w.avatar.role(),TownPlaces.homeOf("self"),w.avatar.activity(),w.avatar.label(),w.avatar.x(),w.avatar.y(),w.avatar.until());
            ResidentState self=state(w,"self");
            if(self.positionId==null||TownPlaces.position(w,self.positionId)==null)TownPlaces.claim(w,"self",w.avatar.place(),Set.of("focus","study").contains(w.avatar.activity())&&TownPlaces.isHome(w.avatar.place())?"desk":null,now);
        }
    }
    /** The one legacy WorldObject that used to advertise a "state" nobody ever wrote back: keep it
     * reflecting whatever the real cafe-worktable position now holds. */
    private static void syncLegacyObjects(CompanionWorld w) {
        Position table=TownPlaces.position(w,"cafe-worktable");
        if(table==null)return;
        String state=table.occupantIds.isEmpty()?"available":"occupied";
        for(int i=0;i<w.objects.size();i++) {
            WorldObject o=w.objects.get(i);
            if(o.id().equals("worktable")&&!o.state().equals(state))w.objects.set(i,new WorldObject(o.id(),o.kind(),o.place(),o.label(),state,o.projectId()));
        }
    }
}
