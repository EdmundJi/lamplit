package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/**
 * Physical coffee/water service and opening-state rules. Residents choose whether to request a
 * drink, tend the counter, keep waiting, leave, open, close, or return through their own model turn.
 * This class validates permissions and place state, advances an already chosen request through
 * waiting/preparing/delivered/consumed, and records objective failures such as leaving before a cup
 * arrives or closing with work still open.
 *
 * <p>It deliberately does not turn duty pressure, personality, familiarity, elapsed waiting time or
 * learned expectations into speech or actions. Those legacy helpers remain only for old controlled
 * compatibility tests and are not called by the production simulation loop. A visible wait may enter
 * one resident's model context as a qualitative fact; the resident still decides what it means and
 * what to do.
 */
final class CafeService {
    private CafeService() {}
    static final String OWNER = "owner";
    static final String PLACE = "cafe";
    static final int DEFAULT_OPEN_MINUTE = 9 * 60;
    static final int DEFAULT_CLOSE_MINUTE = 21 * 60;
    /** The operator is mutable world state.  OWNER remains the legacy/default id for old saves and
     * tests, while an active, explicitly accepted arrangement can add a helper or replace it. */
    /** Even when the counter is closed, the last operator remains the person who can agree to a
     * handover.  cafeOperating gates actual service in mayTend and the simulation loop. */
    static String operatorId(CompanionWorld w){return w.cafeOperatorId==null?OWNER:w.cafeOperatorId;}
    static void reconcileSchedule(CompanionWorld w,Instant at){
        if(w.cafeOpenMinute<0||w.cafeOpenMinute>=1440||w.cafeCloseMinute<0||w.cafeCloseMinute>=1440||w.cafeOpenMinute==w.cafeCloseMinute){
            w.cafeOpenMinute=DEFAULT_OPEN_MINUTE;w.cafeCloseMinute=DEFAULT_CLOSE_MINUTE;
        }
        if(!Set.of("open","closing","closed").contains(w.cafeStatus==null?"":w.cafeStatus)){
            w.cafeStatus=w.cafeOperating&&scheduledOpen(w,at)?"open":"closed";w.cafeStatusChangedAt=at;
        }
        // An explicit pause uses closing until everyone has actually left. Only an old/inconsistent
        // save that says both "not operating" and "open" is repaired straight to closed.
        if(!w.cafeOperating&&"open".equals(w.cafeStatus)){w.cafeStatus="closed";w.cafeStatusChangedAt=at;}
    }
    static boolean scheduledOpen(CompanionWorld w,Instant at){
        int minute=at.atZone(ZoneId.of(w.timezone)).getHour()*60+at.atZone(ZoneId.of(w.timezone)).getMinute();
        return w.cafeOpenMinute<w.cafeCloseMinute
            ?minute>=w.cafeOpenMinute&&minute<w.cafeCloseMinute
            :minute>=w.cafeOpenMinute||minute<w.cafeCloseMinute;
    }
    static boolean acceptingOrders(CompanionWorld w){return w.cafeOperating&&"open".equals(w.cafeStatus);}
    static boolean mayManage(CompanionWorld w,String residentId){
        if(Objects.equals(operatorId(w),residentId))return true;
        return w.workArrangements.stream().anyMatch(a->"active".equals(a.status)&&residentId.equals(a.workerId)
            &&PLACE.equals(a.place)&&"delegate".equals(a.kind));
    }
    static boolean mayTend(CompanionWorld w,String residentId){
        if(!acceptingOrders(w))return false;
        if(Objects.equals(operatorId(w),residentId))return true;
        return w.workArrangements.stream().anyMatch(a->"active".equals(a.status)&&residentId.equals(a.workerId)
            && PLACE.equals(a.place)&&Set.of("assist","delegate").contains(a.kind));
    }
    static String oldestWaitingRequestId(CompanionWorld w){return w.serviceRequests.stream().filter(r->"waiting".equals(r.status)&&PLACE.equals(r.place)).min(Comparator.comparing(r->r.requestedAt)).map(r->r.id).orElse(null);}
    static final int PREP_SECONDS = 18;
    private static final int COLD_AFTER_SECONDS = 90;
    private static final int FOLLOWUP_WINDOW_SECONDS = 600;
    private static final int PROACTIVE_COOLDOWN_SECONDS = 400;
    private static final int DUTY_REFLECTION_COOLDOWN_SECONDS = 180;
    private static final int COMPLAINT_EVIDENCE_THRESHOLD = 2;
    private static final int INTERRUPTION_EVIDENCE_THRESHOLD = 3;
    private static final int CONSCIENTIOUSNESS_FLOOR = 15, CONSCIENTIOUSNESS_CEILING = 95;
    private static final int MIN_INTERRUPTION_INTERVAL_SECONDS = 90;

    // ---- making a request --------------------------------------------------------------------

    /** A resident has explicitly chosen request_drink; create one request unless one is already open. */
    static void request(CompanionWorld w, ResidentState requester, Instant at) {
        if(!acceptingOrders(w))return;
        String operatorId=operatorId(w);
        if (requester == null || requester.id.equals(operatorId)) return;
        if (w.serviceRequests.stream().anyMatch(r -> r.requesterId.equals(requester.id) && open(r.status))) return;
        ServiceRequest req = new ServiceRequest();
        req.id = "sr-" + (++w.eventSequence); req.requesterId = requester.id; req.kind = "coffee";
        req.place = PLACE; req.status = "waiting"; req.requestedAt = at;
        w.serviceRequests.add(req);
        ResidentSimulation.memory(w, requester.id, requester.id, "observed", at, "service", "想喝一口热的，看看柜台那边有没有人。", List.of(), 3);
        while (w.serviceRequests.size() > 60) w.serviceRequests.removeFirst();
    }

    // ---- the owner's side: duty vs. everything else -------------------------------------------

    record Decision(String requestId, String reason, boolean interruptsOwnProject) {}

    /** Every tick this resident is free to choose, for the owner only: does the responsibility of a
     * waiting counter outweigh whatever else is pulling at them right now? Neither side is a fixed
     * rule - dutyPressure is a running total that decays when nobody waits, and the competing pull
     * grows both from an unfinished project of the owner's own and from how eroded their own
     * conscientiousness has become. Returns null (duty loses, or there is nothing to do) or the
     * request the owner will go tend to. */
    static Decision decide(CompanionWorld w, ResidentState owner, Personality personality, Project current, boolean unfinished, Instant at) {
        List<ServiceRequest> waiting = w.serviceRequests.stream().filter(r -> "waiting".equals(r.status) && PLACE.equals(r.place))
            .sorted(Comparator.comparing(r -> r.requestedAt)).toList();
        if (waiting.isEmpty()) return null;
        double pull = personalPull(personality, current, unfinished, owner.id);
        if (owner.dutyPressure <= pull) return null;
        boolean interrupts = current != null && unfinished && current.ownerId.equals(owner.id);
        String reason = interrupts ? "柜台上有人在等，手上的事先放一放" : "柜台上有人在等，回去看看";
        return new Decision(waiting.getFirst().id, reason, interrupts);
    }

    /** Same competition as {@link #decide}, but for pre-empting a plan already running rather than a
     * resident who just became free to choose. Two extra guards keep this from thrashing: an added
     * margin - scaled so a low-conscientiousness owner needs pressure to clear the pull by a much
     * wider gap before an ongoing plan gets cut short, not just a hair over it, so "被打断的门槛应该
     * 明显更高" is a matter of degree rather than a different rule - and a cooldown since the last
     * interruption, so an owner who was just pulled off something is not immediately pulled off the
     * next thing too (the "刚被打断又立刻被拉回原计划" case the task calls out). */
    static Decision decideInterrupt(CompanionWorld w, ResidentState owner, Personality personality, Project current, boolean unfinished, Instant at) {
        if (owner.lastDutyInterruptionAt != null && Duration.between(owner.lastDutyInterruptionAt, at).getSeconds() < MIN_INTERRUPTION_INTERVAL_SECONDS) return null;
        Decision base = decide(w, owner, personality, current, unfinished, at);
        if (base == null) return null;
        double margin = interruptMargin(personality);
        if (owner.dutyPressure <= personalPull(personality, current, unfinished, owner.id) + margin) return null;
        return base;
    }

    /** How much further pressure must clear the competing pull before it is allowed to cut a plan
     * already in progress short, on top of simply winning at a moment the owner was free anyway.
     * Conscientious owners need barely any extra evidence; the least conscientious need a lot. */
    private static double interruptMargin(Personality personality) {
        return Math.max(4, (80 - personality.conscientiousness()) * 0.45);
    }

    /** The pull toward *not* tending the counter right now: a baseline (there is always something
     * else one could be doing), a strong term when the owner's own project is unfinished and would be
     * set aside, and a term that grows as the owner's own conscientiousness has eroded - so the very
     * same drift {@link #reflectOnDuty} produces feeds back into how easily duty loses next time. */
    private static double personalPull(Personality personality, Project current, boolean unfinished, String ownerId) {
        double base = 25;
        double projectPull = current != null && unfinished && current.ownerId.equals(ownerId) ? 20 : 0;
        double erosionPull = (100 - personality.conscientiousness()) * 0.4;
        return base + projectPull + erosionPull;
    }

    /** How much responsibility-pressure the owner is carrying right now: grows with how many people
     * are waiting, grows faster during business hours (an off-hours wait still counts, just less), and
     * decays gently on its own when the counter is clear. Called every tick regardless of what the
     * owner is currently doing, so pressure genuinely builds while it is being ignored. */
    static void accruePressure(CompanionWorld w, ResidentState owner, Instant at) {
        if(!acceptingOrders(w)){owner.dutyPressure=clamp(owner.dutyPressure-2.5);return;}
        long waiting = w.serviceRequests.stream().filter(r -> "waiting".equals(r.status) && PLACE.equals(r.place)).count();
        double weight = businessHours(w, at) ? 1.0 : 0.4;
        owner.dutyPressure = waiting > 0
            ? clamp(owner.dutyPressure + weight * (5 + 4 * waiting))
            : clamp(owner.dutyPressure - 2.5);
    }

    // Package-visible (not private) so ResidentSimulation can give the owner's own idle default a
    // soft business-hours bias toward the cafe - see the "店主的价值来自他偶尔不在" / "他长时间待在
    // 花园，这本身就不对" requirement. Still just a bias on one fallback branch, not a rule that
    // forbids leaving; duty itself still runs entirely on dutyPressure vs. personalPull above.
    static boolean businessHours(CompanionWorld w, Instant at) {
        return scheduledOpen(w,at);
    }

    static String scheduleCue(CompanionWorld w,String residentId,Instant at){
        if(!mayManage(w,residentId))return null;
        if("open".equals(w.cafeStatus)&&!scheduledOpen(w,at))return "已经过了咖啡馆平常打烊的时间";
        if("closed".equals(w.cafeStatus)&&scheduledOpen(w,at))return w.cafeOperating?"已经到了咖啡馆平常开门的时间":"到了咖啡馆平常开门时间；目前经营暂停，门仍关着";
        if("closing".equals(w.cafeStatus))return cafeEmpty(w)?"客人已经走了，可以锁门回家":"刚才已经说过要打烊，店里还有人没走";
        return null;
    }

    static String latestClosingNotice(CompanionWorld w,String residentId){
        return w.memories.stream().filter(m->m.ownerId().equals(residentId)&&"cafe-hours".equals(m.topicId())&&"heard".equals(m.sourceType()))
            .max(Comparator.comparing(Memory::at)).map(Memory::text).orElse(null);
    }

    static boolean closeForDay(CompanionWorld w,String residentId,String announcement,Instant at){
        ResidentState manager=ResidentSimulation.state(w,residentId);Actor actor=manager==null?null:ResidentSimulation.actor(w,residentId);
        if(manager==null||actor==null||!mayManage(w,residentId)||!"open".equals(w.cafeStatus)||!PLACE.equals(actor.place())||"sleep".equals(actor.activity()))return false;
        List<String> present=new ArrayList<>();
        for(ResidentState state:w.residentStates)if(!state.id.equals(residentId)&&PLACE.equals(ResidentSimulation.actor(w,state.id).place())&&!"sleep".equals(ResidentSimulation.actor(w,state.id).activity()))present.add(state.id);
        String spoken=announcement==null?"":announcement.trim();
        if(!present.isEmpty()&&spoken.isEmpty())return false;
        w.cafeStatus="closing";w.cafeStatusChangedAt=at;
        String own=null;
        if(!spoken.isEmpty())own=ResidentSimulation.memory(w,residentId,residentId,"observed",at,"cafe-hours","我刚才说：“"+spoken+"”",List.of(),5);
        for(String guest:present)ResidentSimulation.memory(w,guest,residentId,"heard",at,"cafe-hours",ResidentSimulation.actor(w,residentId).name()+"当面说：“"+spoken+"”",List.of(),6);
        closeRequests(w,residentId,at);
        ResidentSimulation.event(w,at,"cafe_closing",PLACE,List.of(residentId),spoken.isEmpty()?ResidentSimulation.actor(w,residentId).name()+"开始收店。":ResidentSimulation.actor(w,residentId).name()+"说：“"+spoken+"",null);
        return own!=null||present.isEmpty();
    }

    static boolean openForDay(CompanionWorld w,String residentId,String reason,Instant at){
        if(!mayManage(w,residentId)||!"closed".equals(w.cafeStatus)||!PLACE.equals(ResidentSimulation.actor(w,residentId).place()))return false;
        boolean returning=!w.cafeOperating;w.cafeOperating=true;w.cafeStatus="open";w.cafeStatusChangedAt=at;
        if(returning)ResidentSimulation.markCafeReturned(w,residentId,reason,at);
        ResidentSimulation.memory(w,residentId,residentId,"observed",at,"cafe-hours","我把门打开，开始今天的营业。",List.of(),4);
        ResidentSimulation.event(w,at,"cafe_opened",PLACE,List.of(residentId),ResidentSimulation.actor(w,residentId).name()+"打开了咖啡馆的门。",null);return true;
    }

    /** Stop accepting work without inventing an announcement. A caller may first use closeForDay
     * when the operator actually supplied on-site speech; this is the silent physical fallback for
     * an operator who changes direction elsewhere or says nothing aloud. */
    static boolean pauseOperation(CompanionWorld w,String residentId,Instant at){
        if(!Objects.equals(operatorId(w),residentId))return false;
        boolean wasOpen="open".equals(w.cafeStatus);w.cafeOperating=false;
        if(!"closed".equals(w.cafeStatus)){w.cafeStatus="closing";w.cafeStatusChangedAt=at;closeRequests(w,residentId,at);}
        if(wasOpen)ResidentSimulation.event(w,at,"cafe_closing",PLACE,List.of(residentId),"咖啡馆暂停营业，正在收店。",null);
        return true;
    }

    static void finishClosingIfEmpty(CompanionWorld w,Instant at){
        if(!"closing".equals(w.cafeStatus)||!cafeEmpty(w))return;
        w.cafeStatus="closed";w.cafeStatusChangedAt=at;
        ResidentSimulation.event(w,at,"cafe_closed",PLACE,List.of(),"咖啡馆的灯熄了，今天已经打烊。",null);
    }

    private static boolean cafeEmpty(CompanionWorld w){
        boolean npc=w.residents.stream().anyMatch(a->PLACE.equals(a.place()));
        return !npc&&(w.avatar==null||!PLACE.equals(w.avatar.place()));
    }

    private static void closeRequests(CompanionWorld w,String managerId,Instant at){
        for(ServiceRequest req:w.serviceRequests)if(Set.of("waiting","preparing","delivered").contains(req.status)){
            ResidentState requester=ResidentSimulation.state(w,req.requesterId);
            if(requester!=null)ResidentSimulation.memory(w,requester.id,managerId,"observed",at,"service","店里打烊了，这一杯今天没等到。",List.of(),4);
            req.status="abandoned";req.resolvedAt=at;
        }
    }

    /** Called from choose() the moment duty wins over an unfinished project of the owner's own: leaves
     * a small trace of the interruption (visible in the owner's own memory, and evidence for a future
     * downward nudge in {@link #reflectOnDuty}) without touching the resident's mood or plan itself -
     * that part stays choose()'s job. */
    static void recordInterruption(CompanionWorld w, ResidentState owner, Project current, Instant at) {
        owner.interruptionsSinceDutyReflection++;
        String id = ResidentSimulation.memory(w, owner.id, owner.id, "observed", at, current.id,
            "又被柜台叫住了，手上的「" + current.title + "」只好先放一放。", List.of(), 4);
        pushEvidence(owner.dutyInterruptionEvidenceIds, id);
    }

    /** The owner has just claimed the counter and started making the oldest waiting drink: mark it
     * "preparing" the moment they set off, including during the walk back if they were elsewhere - the
     * decision to go serve is what starts the clock on this link, not physical arrival. */
    static void beginPreparing(CompanionWorld w, String requestId, Instant at) {
        ServiceRequest req = find(w, requestId);
        if (req != null && "waiting".equals(req.status)) { req.status = "preparing"; req.preparingAt = at; }
    }

    /** The owner's "tend" plan has run its course: hand the request its drink, unless the customer has
     * already left - in which case the link breaks quietly (a memory, no WorldEvent), not a shout.
     * Also gives a learned regular one proactive pour, at most one per visit to the counter. */
    static void finishTending(CompanionWorld w, ResidentState owner, String requestId, Instant at) {
        ServiceRequest req = find(w, requestId);
        if (req != null && "preparing".equals(req.status)) {
            ResidentState requester = ResidentSimulation.state(w, req.requesterId);
            boolean present = requester != null && ResidentSimulation.actor(w, req.requesterId).place().equals(req.place);
            if (present) { req.status = "delivered"; req.deliveredAt = at; }
            else {
                req.status = "abandoned"; req.resolvedAt = at;
                ResidentSimulation.memory(w, owner.id, owner.id, "observed", at, "service", "做好了一杯，人却已经不在了。", List.of(), 4);
            }
        }
        // Further service is another decision. Completing one cup never silently creates the next.
    }

    /** Pours, unprompted, for at most one learned regular currently at the cafe with nothing already
     * pending for them and the per-customer cooldown elapsed - the concrete form of "predicting the
     * user's need" the requirements ask for. Whether it actually lands is judged later, in
     * {@link #tick}: an explicit request is always wanted; a guess is only picked up if the customer
     * still plausibly wants it, which is exactly what lets a learned rule misfire. */
    private static void proactivelyServe(CompanionWorld w, ResidentState owner, Instant at) {
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(operatorId(w)) || other.id.equals("self")) continue;
            if (!Boolean.TRUE.equals(owner.anticipatesRefill.get(other.id))) continue;
            if (!ResidentSimulation.actor(w, other.id).place().equals(PLACE)) continue;
            Instant cooldown = owner.lastProactiveAt.get(other.id);
            if (cooldown != null && Duration.between(cooldown, at).getSeconds() < PROACTIVE_COOLDOWN_SECONDS) continue;
            if (w.serviceRequests.stream().anyMatch(r -> r.requesterId.equals(other.id) && open(r.status))) continue;
            owner.lastProactiveAt.put(other.id, at);
            ServiceRequest req = new ServiceRequest();
            req.id = "sr-" + (++w.eventSequence); req.requesterId = other.id; req.kind = "water"; req.place = PLACE;
            req.status = "delivered"; req.requestedAt = at; req.preparingAt = at; req.deliveredAt = at; req.proactive = true;
            w.serviceRequests.add(req);
            ResidentSimulation.memory(w, owner.id, owner.id, "observed", at, "service",
                "没等" + ResidentSimulation.actor(w, other.id).name() + "开口，我先倒了一杯放过去。", List.of(), 5);
            return;
        }
    }

    // ---- ticking every request forward, or breaking the chain ---------------------------------

    /** Advances the whole request queue by one tick: waiters who ran out of patience or already left
     * are reaped, delivered drinks get picked up or go cold. Called once per world tick, independent
     * of whose plan is running - a request keeps its own clock even while its requester has moved on
     * to something else while they wait. */
    static void tick(CompanionWorld w, Instant at) {
        for (ServiceRequest req : w.serviceRequests) {
            if ("waiting".equals(req.status)) reapWaiting(w, req, at);
            else if ("delivered".equals(req.status)) resolveDelivered(w, req, at);
        }
    }

    private static void reapWaiting(CompanionWorld w, ServiceRequest req, Instant at) {
        ResidentState requester = ResidentSimulation.state(w, req.requesterId);
        boolean present = requester != null && ResidentSimulation.actor(w, req.requesterId).place().equals(req.place);
        if (!present) abandon(w, requester, req, at);
    }

    /** How long this particular requester will wait before giving up: familiar people (a relationship
     * above the neutral baseline) wait longer, and emotionally steady people wait a little longer than
     * volatile ones - never the other way around for either factor. */
    private static long patienceSeconds(ResidentState requester, int familiarity) {
        Personality p = Personality.of(requester);
        double familiarityBonus = Math.max(0, familiarity - 40) * 0.9;
        double steadiness = (50 - p.volatility()) * 0.3;
        return Math.max(18, Math.round(42 + familiarityBonus + steadiness));
    }

    private static void abandon(CompanionWorld w, ResidentState requester, ServiceRequest req, Instant at) {
        req.status = "abandoned"; req.resolvedAt = at;
        if (requester == null) return;
        ResidentSimulation.memory(w, requester.id, requester.id, "observed", at, "service", "离开咖啡馆时，那杯还没有等到。", List.of(), 3);
    }

    private static void resolveDelivered(CompanionWorld w, ServiceRequest req, Instant at) {
        ResidentState requester = ResidentSimulation.state(w, req.requesterId);
        boolean present = requester != null && ResidentSimulation.actor(w, req.requesterId).place().equals(req.place);
        // An explicit ask is always wanted once it arrives; a proactive guess only lands if the
        // customer still plausibly wants it - this is what lets a learned expectation misfire.
        boolean stillWants = !req.proactive || requester.energy < 60;
        if (present && stillWants) consume(w, requester, req, at);
        else if (Duration.between(req.deliveredAt, at).getSeconds() > COLD_AFTER_SECONDS) goCold(w, req, at);
    }

    private static void consume(CompanionWorld w, ResidentState requester, ServiceRequest req, Instant at) {
        req.status = "consumed"; req.resolvedAt = at;
        ResidentState owner = ResidentSimulation.state(w, operatorId(w));
        if(owner==null)return;
        owner.lastServedAt.put(requester.id, at);
        // A drink is a small immediate lift, not a substitute for sustained sleep.
        requester.energy = Math.min(100, requester.energy + 3);
        ResidentSimulation.relation(owner, requester, req.proactive ? 5 : 3);
        String text = req.proactive
            ? "还没开口，" + ResidentSimulation.actor(w, owner.id).name() + "就端了一杯过来，像是记住了我的习惯。"
            : ResidentSimulation.actor(w, owner.id).name() + "端来了一杯，等了一会儿，但还是很暖。";
        ResidentSimulation.memory(w, requester.id, owner.id, "observed", at, "service", text, List.of(), req.proactive ? 7 : 5);
    }

    private static void goCold(CompanionWorld w, ServiceRequest req, Instant at) {
        req.status = "cold"; req.resolvedAt = at;
        ResidentState owner = ResidentSimulation.state(w, operatorId(w));
        if(owner==null)return;
        ResidentSimulation.memory(w, owner.id, owner.id, "observed", at, "service",
            "端过去的那一杯，" + ResidentSimulation.actor(w, req.requesterId).name() + "这次没喝，凉了。", List.of(), 4);
    }

    // ---- reflecting: the only place personality itself moves -----------------------------------

    /** The owner-only, bounded, evidence-gated place personality itself moves from this chain: at
     * least {@link #COMPLAINT_EVIDENCE_THRESHOLD} voiced complaints since the last check nudges
     * conscientiousness up a little; zero complaints but at least {@link #INTERRUPTION_EVIDENCE_THRESHOLD}
     * interruptions of the owner's own project nudges it down. Both require repeated evidence (a
     * single incident never moves anything), both are clamped to a small step and to a floor/ceiling
     * well short of 0/100 so the character never disappears entirely, and both consume (reset) the
     * counters they read so evidence cannot be double-spent across reflections - but ONLY when they
     * are actually acted on. Evidence below threshold (e.g. one complaint, or two interruptions) is
     * left in place so it can keep accumulating across later reflection windows; the earlier version
     * of this method reset both counters to zero on every check regardless of whether either had
     * crossed its threshold, which meant evidence trickling in slower than one reflection window
     * (every {@link #DUTY_REFLECTION_COOLDOWN_SECONDS}) was silently discarded and could never reach
     * the threshold at all - this is the broken link that left conscientiousness stuck at its initial
     * value even after real complaints and interruptions had happened. */
    static void reflectOnDuty(CompanionWorld w, ResidentState r, Instant at) {
        if (!r.id.equals(operatorId(w))) return;
        Personality.of(r); // ensure this resident's own personality fields are seeded before nudging them
        if (r.lastDutyReflectionAt != null && Duration.between(r.lastDutyReflectionAt, at).getSeconds() < DUTY_REFLECTION_COOLDOWN_SECONDS) return;
        r.lastDutyReflectionAt = at;
        int complaints = r.complaintsSinceDutyReflection, interruptions = r.interruptionsSinceDutyReflection;
        if (complaints == 0 && interruptions == 0) return;
        if (complaints >= COMPLAINT_EVIDENCE_THRESHOLD) {
            double before = r.conscientiousness;
            r.conscientiousness = Math.min(CONSCIENTIOUSNESS_CEILING, r.conscientiousness + Math.min(4, complaints));
            if (r.conscientiousness != before) ResidentSimulation.memory(w, r.id, r.id, "reflection", at, "duty",
                "最近总有人等太久，我说了要更上心一点。", copy(r.dutyComplaintEvidenceIds), 8);
            r.dutyComplaintEvidenceIds.clear();
            r.complaintsSinceDutyReflection = 0;
        } else if (interruptions >= INTERRUPTION_EVIDENCE_THRESHOLD) {
            double before = r.conscientiousness;
            r.conscientiousness = Math.max(CONSCIENTIOUSNESS_FLOOR, r.conscientiousness - Math.min(3, interruptions / 2 + 1));
            if (r.conscientiousness != before) ResidentSimulation.memory(w, r.id, r.id, "reflection", at, "duty",
                "总是被叫回柜台，自己的事一拖再拖，我开始觉得，我为这家店牺牲太多了。", copy(r.dutyInterruptionEvidenceIds), 8);
            r.dutyInterruptionEvidenceIds.clear();
            r.interruptionsSinceDutyReflection = 0;
        }
        // else: real evidence exists but has not crossed either threshold yet - keep it and check
        // again next window, rather than discarding it here.
    }

    // ---- small shared helpers -------------------------------------------------------------------

    private static boolean open(String status) { return Set.of("waiting", "preparing", "delivered").contains(status); }
    private static ServiceRequest find(CompanionWorld w, String id) { return w.serviceRequests.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null); }
    private static void pushEvidence(List<String> ids, String id) { if (id == null) return; if (ids.size() >= 4) ids.removeFirst(); ids.add(id); }
    private static List<String> copy(List<String> ids) { return ids.isEmpty() ? List.of() : new ArrayList<>(ids); }
    private static double clamp(double v) { return Math.max(0, Math.min(100, v)); }
}
