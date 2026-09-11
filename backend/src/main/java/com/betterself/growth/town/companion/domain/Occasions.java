package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.PendingOccasion;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个动作要么永远在菜单上，要么挂在它的时机上单独问 —— 没有第三种。
 *
 * <p>This class exists because the same mistake was made four times. {@code celebrate} was offered
 * 1658 times and taken 0. The fix at the time was read as "celebrate needs a better prompt", so the
 * next action went into the same menu, and the next, and the next. A two-day run measured the
 * result: {@code change_work} 839 offers / 0 taken, {@code invite} 555/0, {@code lock_door} 470/0,
 * {@code close_cafe} 142/0 - against {@code greet} at 80.6%, which is the one question this town
 * asks on its own, about something that just happened.
 *
 * <p>The 470 is the one worth reading carefully, because it is not a failure of wording.
 * {@code lock_door} was offered on every decision a resident took while standing in the cafe - with
 * the shop open and four people at the tables. The real occasion for locking up (the last person
 * still there, on their way out) happened <b>7 times</b> in those two days. So 463 of those refusals
 * were the model reading the situation correctly and us recording it as a dead path. A menu entry
 * does not measure whether anybody wants to do a thing; it measures how often we asked at a moment
 * when nobody could have wanted to.
 *
 * <p>So the rule is structural, not per-action. An action whose sense depends on a moment is never
 * put in {@link ResidentSimulation#availableActions} - it is registered here with the trigger that
 * recognises its moment, and asked as its own question when that moment arrives, exactly the way a
 * face-to-face encounter is already asked (Generative Agents §4.3.1, <i>Should John react to the
 * observation, and if so, what would be an appropriate reaction?</i>). {@link
 * OccasionMenuExclusionTest} fails the build if an action ever appears in both places, which is what
 * makes this a rule rather than four more patches.
 *
 * <p>And every occasion carries a free refusal. Not a refusal that has to be argued for, not one
 * that gets recorded as a failure to answer - {@code none} is a first-class answer, needs no reason,
 * and is the right one most of the time. The same gap on the ordinary decision (26 verbs and no way
 * to say "nothing in particular", so "nothing in particular" had to disguise itself as rest 18% /
 * continue 18% / make 14% of the time) is closed by {@code none} in
 * {@code ResidentSimulation.DECISION_ACTIONS}.
 */
public final class Occasions {
    private Occasions(){}

    /** What the rules noticed, in bystander words. {@code fact} is what an onlooker would have seen
     * and is the only thing the resident is told; {@code situation} is simulation bookkeeping - the
     * comparable shape of this moment, used the same way {@code encounterFingerprint} is, so a
     * resident who has already said "no, leave it" is not asked again until something about the
     * scene actually changes. It never reaches a model. */
    public record Moment(String fact,String situation,String place,String target) {}

    @FunctionalInterface public interface Trigger {
        /** The moment, or null - which is the usual answer. */
        Moment at(CompanionWorld w,ResidentState r,Instant at);
    }
    @FunctionalInterface public interface Validity {
        /** Whether the moment this question was asked about is still the moment. A model answer can
         * arrive several simulated minutes after the question was raised; by then the shop may have
         * filled up again or somebody else may have locked the door. */
        boolean stillOpen(CompanionWorld w,PendingOccasion pending,Instant at);
    }

    /** {@code askedBy} names which question carries this occasion: {@code occasion} for one raised
     * here and put to the resident on its own, {@code react} for one that rides on the face-to-face
     * question that is already asked at exactly this moment - adding a second question about the
     * same instant would only make the two compete, which is the menu problem again in miniature. */
    public record Definition(String key,String askedBy,String question,String yes,String no,
                             long ttlSeconds,Trigger trigger,Validity validity) {}

    private static final long MINUTES_5=300,MINUTES_30=1800;

    public static final List<Definition> ALL=List.of(
        new Definition("lock_door","occasion",
            "要不要把门锁上再走？",
            "锁上：这段时间只有你自己进得来，别人会被关在外面",
            "不锁：门开着就走，这同样是正常的，不需要理由",
            MINUTES_5,Occasions::lastOneInTheCafe,Occasions::stillLastOneInTheCafe),
        new Definition("close_cafe","occasion",
            "已经过了平常打烊的时间，今天就收店吗？",
            "收店：当面说一句，让还在店里的人知道",
            "不收：想再开一会儿就再开一会儿，晚一点关门是你的事",
            MINUTES_30,Occasions::pastClosingTime,Occasions::stillPastClosingTimeAndOpen),
        new Definition("change_work","occasion",
            "手上这一段结束了，接下来你想过点别的日子吗？",
            "换一种活法：说说你想试的是什么，它可能让原来的事没人做",
            "不换：照旧过下去也完全正常，多数时候这就是答案",
            MINUTES_30,Occasions::atAJunction,Occasions::stillAtAJunction),
        // Carried by react, not raised here: the occasion for asking somebody to come and do a thing
        // with you is that they are standing in front of you, and that is the moment react already
        // owns. Registered all the same, because the point of this registry is that the menu can be
        // checked against it - see OccasionMenuExclusionTest.
        new Definition("invite","react",
            "有人就在你面前，你手上还有一件需要人搭手的事",
            "叫他一起：走过去说你在做什么",
            "不叫：自己做，或者什么都不说",
            0,null,null));

    public static Definition of(String key){return ALL.stream().filter(d->d.key().equals(key)).findFirst().orElse(null);}
    /** Every action this registry has taken responsibility for, whichever question carries it. The
     * menu must contain none of these. */
    public static boolean isOccasioned(String action){return of(action)!=null;}

    // ---- raising -----------------------------------------------------------------------------

    /** One pass over every registered occasion, run once per tick from {@link
     * ResidentSimulation#advance}. Deliberately the only place an occasion is ever raised: a
     * {@code raise()} call sprinkled into whichever method happened to be nearby is how the menu got
     * to twenty-six entries in the first place. */
    public static void scan(CompanionWorld w,Instant at){
        for(Definition d:ALL){
            if(!"occasion".equals(d.askedBy()))continue;
            for(ResidentState r:w.residentStates){
                if("self".equals(r.id)&&!ResidentSimulation.selfIsFree(w))continue;
                if(ResidentSimulation.activeConversation(w,r.id)!=null)continue;
                if(w.pendingOccasions.stream().anyMatch(p->p.residentId.equals(r.id)&&p.key.equals(d.key())))continue;
                Moment moment=d.trigger().at(w,r,at);
                if(moment==null)continue;
                // Already put to them once for this scene - answered, refused, or simply left to
                // expire. All three mean the same thing: this moment has had its question.
                if(moment.situation().equals(w.askedOccasions.get(r.id+"|"+d.key())))continue;
                asked(w,r.id,d.key(),moment.situation());
                PendingOccasion pending=new PendingOccasion();
                pending.id="po-"+(++w.eventSequence);pending.residentId=r.id;pending.key=d.key();
                pending.place=moment.place();pending.target=moment.target();pending.fact=moment.fact();
                pending.situation=moment.situation();pending.at=at;pending.residentRevision=r.revision;
                w.pendingOccasions.add(pending);
                while(w.pendingOccasions.size()>12)w.pendingOccasions.removeFirst();
                ResidentSimulation.recordDecisionTrigger(w,r.id,"occasion:"+d.key(),at);
            }
        }
    }

    /** Drops questions reality has overtaken. Unlike an unanswered encounter, which falls back to
     * greeting so a model outage cannot make the town silent, an unanswered occasion falls back to
     * nothing at all: the whole point is that not doing it is a real and common answer, so silence
     * has to mean exactly that rather than the rules quietly doing it on the resident's behalf. */
    public static void expire(CompanionWorld w,Instant at){
        for(PendingOccasion pending:new ArrayList<>(w.pendingOccasions)){
            ResidentState r=ResidentSimulation.state(w,pending.residentId);
            Definition d=of(pending.key);
            boolean stale=r==null||d==null||r.revision!=pending.residentRevision
                ||Duration.between(pending.at,at).getSeconds()>=d.ttlSeconds()
                ||!d.validity().stillOpen(w,pending,at);
            if(stale)w.pendingOccasions.remove(pending);
        }
    }

    public static PendingOccasion pending(CompanionWorld w,String id){
        return w.pendingOccasions.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);
    }
    private static void asked(CompanionWorld w,String residentId,String key,String situation){
        w.askedOccasions.put(residentId+"|"+key,situation);
        while(w.askedOccasions.size()>32)w.askedOccasions.remove(w.askedOccasions.keySet().iterator().next());
    }

    // ---- the triggers ------------------------------------------------------------------------

    /** Locking up is a thing you do on your way out of a room you are the last one in. Measured
     * against the two-day run this is on the order of a handful of moments a day, not 470. */
    private static Moment lastOneInTheCafe(CompanionWorld w,ResidentState r,Instant at){
        if(!aloneAndAwakeInTheCafe(w,r))return null;
        if(DoorService.isLocked(w))return null;
        // On the way out: either the thing they were doing has just ended, or it ends shortly. A
        // person settled in for another two hours is not standing at the door.
        boolean leaving=r.plan==null||Duration.between(at,r.plan.endsAt()).getSeconds()<=600;
        if(!leaving)return null;
        return new Moment("店里现在只剩你一个，门还开着。",day(w,at)+"|cafe-alone|"+w.cafeStatus,"cafe",null);
    }
    private static boolean stillLastOneInTheCafe(CompanionWorld w,PendingOccasion pending,Instant at){
        ResidentState r=ResidentSimulation.state(w,pending.residentId);
        if(r==null||DoorService.isLocked(w))return false;
        // Answering from the street is fine and is what actually happens - you pull the door shut
        // behind you. Answering from the garden, ten minutes later, is not.
        String place=ResidentSimulation.actor(w,r.id).place();
        if(!"cafe".equals(place)&&!"street".equals(place))return false;
        return w.residentStates.stream().noneMatch(o->!o.id.equals(r.id)&&inTheCafeAwake(w,o));
    }
    private static boolean aloneAndAwakeInTheCafe(CompanionWorld w,ResidentState r){
        if(!inTheCafeAwake(w,r))return false;
        return w.residentStates.stream().noneMatch(o->!o.id.equals(r.id)&&inTheCafeAwake(w,o));
    }
    private static boolean inTheCafeAwake(CompanionWorld w,ResidentState r){
        var a=ResidentSimulation.actor(w,r.id);
        return a!=null&&"cafe".equals(a.place())&&!"sleep".equals(a.activity());
    }

    /** The clock has gone past the hour this shop usually stops. Asked once a day, of whoever is
     * actually running it and actually standing in it. */
    private static Moment pastClosingTime(CompanionWorld w,ResidentState r,Instant at){
        if(!CafeService.mayManage(w,r.id)||!"open".equals(w.cafeStatus))return null;
        if(!inTheCafeAwake(w,r))return null;
        if(CafeService.scheduledOpen(w,at))return null;
        return new Moment("已经过了咖啡馆平常打烊的时间，店还开着。",day(w,at)+"|past-close","cafe",null);
    }
    private static boolean stillPastClosingTimeAndOpen(CompanionWorld w,PendingOccasion pending,Instant at){
        ResidentState r=ResidentSimulation.state(w,pending.residentId);
        return r!=null&&"open".equals(w.cafeStatus)&&CafeService.mayManage(w,r.id)&&inTheCafeAwake(w,r);
    }

    /** A junction in one's own life, which is the only time anybody reconsiders what they do with
     * their days: the stretch of work just ended and there is nothing of their own waiting. Once a
     * day at most - a question about how to live asked every twenty minutes is not a question. */
    private static Moment atAJunction(CompanionWorld w,ResidentState r,Instant at){
        if(r.plan!=null)return null;
        var a=ResidentSimulation.actor(w,r.id);
        if(a==null||"sleep".equals(a.activity()))return null;
        if(w.projects.stream().anyMatch(p->r.id.equals(p.ownerId)&&!"celebrating".equals(p.status)))return null;
        return new Moment("手上这一段刚结束，眼下没有自己的事等着做。",day(w,at)+"|junction",a.place(),null);
    }
    private static boolean stillAtAJunction(CompanionWorld w,PendingOccasion pending,Instant at){
        ResidentState r=ResidentSimulation.state(w,pending.residentId);
        return r!=null&&w.projects.stream().noneMatch(p->r.id.equals(p.ownerId)&&!"celebrating".equals(p.status));
    }

    private static String day(CompanionWorld w,Instant at){
        return at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
    }

    // ---- the answer --------------------------------------------------------------------------

    /** {@code none} is free: it needs no reason, records nothing, and is not a refusal the backoff
     * counts against the resident. Anything else is the registered action, applied through exactly
     * the same path an ordinary decision goes through - this class decides when to ask, never what
     * an action does. */
    public static boolean apply(CompanionWorld w,String pendingId,long residentRevision,String choice,
                                String reason,String speech,List<String> evidence,Instant now){
        PendingOccasion pending=pending(w,pendingId);
        if(pending==null)return false;
        Definition d=of(pending.key);
        if(d==null||choice==null)return false;
        ResidentState r=ResidentSimulation.state(w,pending.residentId);
        if(r==null||r.revision!=residentRevision)return false;
        if(!choice.equals("none")&&!choice.equals(d.key()))return false;
        if(!d.validity().stillOpen(w,pending,now)){w.pendingOccasions.remove(pending);return false;}
        w.pendingOccasions.remove(pending);
        if(choice.equals("none"))return true;
        boolean applied=ResidentSimulation.applyDecision(w,r.id,residentRevision,w.intentRevision,
            pending.place,d.key(),pending.target,
            reason==null||reason.isBlank()?d.question():reason,speech,evidence==null?List.of():evidence,now);
        return applied;
    }
}
