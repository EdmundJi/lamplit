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
 * <p>It deliberately turns nothing - not duty pressure, personality, familiarity nor elapsed waiting
 * time - into speech or actions. An earlier batch did carry such helpers (a rule that counted two
 * repeat visits into an {@code anticipatesRefill} flag and then poured unprompted, and a
 * duty-vs-pull comparison that picked the owner's action for them); they had already been
 * disconnected from the loop and are now deleted outright, because a pattern the rules detect on a
 * resident's behalf is not the resident noticing it. A visible wait may enter one resident's model
 * context as a qualitative fact; that resident still decides what it means and what to do, and any
 * regularity ("this one always wants a refill") has to be reached through their own memory and
 * reflection. See docs/04-decisions.md: 保留社会经历，让居民自己解释.
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
    /** Whether the shop is meant to be open at this moment and simply is not yet - the operator has a
     * standing commitment, the hours say so, and they have not shut it for today. This is what makes
     * "go and open up" a thing a shopkeeper's own default can act on, instead of the shop needing a
     * model decision before anybody may walk to it. */
    static boolean dueToOpen(CompanionWorld w,Instant at){
        return w.cafeOperating&&"closed".equals(w.cafeStatus)&&scheduledOpen(w,at)
            &&!at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString().equals(w.cafeClosedForDayOn);
    }
    static boolean scheduledOpen(CompanionWorld w,Instant at){
        int minute=at.atZone(ZoneId.of(w.timezone)).getHour()*60+at.atZone(ZoneId.of(w.timezone)).getMinute();
        return w.cafeOpenMinute<w.cafeCloseMinute
            ?minute>=w.cafeOpenMinute&&minute<w.cafeCloseMinute
            :minute>=w.cafeOpenMinute||minute<w.cafeCloseMinute;
    }
    static boolean acceptingOrders(CompanionWorld w){return w.cafeOperating&&"open".equals(w.cafeStatus);}
    /** Of the two ways to be outside the usual hours, the early one: strictly between the previous
     * closing and today's opening. Written against the same wrap-around shape scheduledOpen uses, so
     * an overnight shop stays correct. */
    static boolean beforeUsualOpening(CompanionWorld w,Instant at){
        if(scheduledOpen(w,at))return false;
        int minute=at.atZone(ZoneId.of(w.timezone)).getHour()*60+at.atZone(ZoneId.of(w.timezone)).getMinute();
        return w.cafeOpenMinute<w.cafeCloseMinute?minute<w.cafeOpenMinute:minute>=w.cafeCloseMinute&&minute<w.cafeOpenMinute;
    }
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
    private static final int DUTY_REFLECTION_COOLDOWN_SECONDS = 180;
    private static final int COMPLAINT_EVIDENCE_THRESHOLD = 2;
    private static final int INTERRUPTION_EVIDENCE_THRESHOLD = 3;
    private static final int CONSCIENTIOUSNESS_FLOOR = 15, CONSCIENTIOUSNESS_CEILING = 95;

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

    /** A candidate qualitative cue for {@code ResidentSimulation.salientPerceptions}, exactly like
     * the ones it already builds from {@link #mayTend} and {@link #oldestWaitingRequestId} - this
     * class proposes a sentence, it never decides anything on the resident's behalf. It exists
     * because request_drink was offered dozens of times in a real run and never once chosen: nothing
     * in a resident's own perceptions ever gave them a reason to want a drink in the first place, and
     * a resident with no such reason has no basis to pick it over whatever they were already doing.
     * The fix is not a new hidden "thirst" gauge (that would be exactly the kind of internal number
     * docs/04-decisions.md rules out) - it is a targeted phrasing of the one qualitative body signal
     * already sanctioned and exposed elsewhere in salientPerceptions (r.energy translated to "很累"),
     * scoped to the one place and moment where acting on it is actually possible. Returns null far
     * more often than not: only when the resident is standing at an open counter with request_drink
     * genuinely available to them AND their own already-disclosed tiredness is real. See this
     * method's caller-side requirement in the PR notes for the one line ResidentSimulation.java needs
     * to add to actually surface this - CafeService cannot add it there itself. */
    /** How long someone has to have been settled in the cafe before having nothing in front of them
     * is a fact worth noticing. Twenty minutes is about when a person who sat down to read looks up. */
    private static final long SETTLED_SECONDS = 20*60;
    /** A candidate sentence for {@code ResidentSimulation.salientPerceptions}, phrased the same way
     * "柜台前有人在等" and "这杯已经等了一阵" already are: an external fact about the resident's own
     * situation, never a hidden gauge and never an instruction.
     * <p>The measured problem this exists for: across a simulated day {@code request_drink} was
     * offered 32 times and chosen zero times, and the cafe served nobody. Nothing in a resident's
     * context ever gave them a reason to want one - by design, since a "thirst" value is forbidden.
     * <p>Two situations, in the order a person would actually notice them. The first is the ordinary
     * one and the reason most drinks get ordered anywhere: you have been sitting in a cafe for a
     * while with nothing in front of you. The second is the tired band {@code ResidentSimulation}
     * already renders as "很累" - reused, not a second threshold invented here. Returns null far more
     * often than not, never fires for whoever is working the counter, and never fires for someone who
     * already has an order open. */
    static String drinkWantCue(CompanionWorld w, ResidentState r, String place, Instant now) {
        if (r == null || !PLACE.equals(place) || !acceptingOrders(w) || r.id.equals(operatorId(w))) return null;
        if (w.serviceRequests.stream().anyMatch(req -> r.id.equals(req.requesterId) && open(req.status))) return null;
        boolean settledAWhile = r.plan != null && PLACE.equals(r.plan.place()) && now != null
            && Duration.between(r.plan.startedAt(), now).getSeconds() >= SETTLED_SECONDS
            && Set.of("study","read","work","make","rest","observe").contains(r.plan.action());
        if (settledAWhile) return "在店里坐了有一阵了，手边还什么都没有";
        if (r.energy <= 20) return "很累，柜台那边说不定能弄点热的";
        return null;
    }

    // ---- the owner's side: duty vs. everything else -------------------------------------------

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

    // Package-visible (not private) so ResidentSimulation can expose "到了平常营业的时间" as a
    // qualitative cue. It is a fact the operator's own model may act on, never a rule that sends
    // them back to the counter - see docs/04-decisions.md "给真实处境，不用责任感阈值派工".
    static boolean businessHours(CompanionWorld w, Instant at) {
        return scheduledOpen(w,at);
    }

    static String scheduleCue(CompanionWorld w,String residentId,Instant at){
        if(!mayManage(w,residentId))return null;
        // Before opening and after closing are both "not within the usual hours", and telling them
        // apart matters more than it looks: the operator opened the shop half an hour early, was told
        // on the very next tick that it was past closing time, and closed it again fifty seconds
        // later. The town then had no cafe for the rest of the day - every habit that goes there is
        // gated on it being open - and the operator sat inside it looping on a decision that could
        // not be applied, 408 times.
        if("open".equals(w.cafeStatus)&&!scheduledOpen(w,at))
            return beforeUsualOpening(w,at)?"还没到咖啡馆平常开门的时间，门已经先开着了":"已经过了咖啡馆平常打烊的时间";
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
        w.cafeClosedForDayOn=at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString();
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
     * already left - in which case the link breaks quietly (a memory, no WorldEvent), not a shout. */
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

    // ---- ticking every request forward, or breaking the chain ---------------------------------

    /** Advances the whole request queue by one tick: waiters who ran out of patience or already left
     * are reaped, delivered drinks get picked up or go cold. Called once per world tick, independent
     * of whose plan is running - a request keeps its own clock even while its requester has moved on
     * to something else while they wait. */
    /** A shop with posted hours, whose operator has an active commitment to running it, opens at its
     * own opening time. This is not the rules deciding something on a resident's behalf - the
     * decisions are all still theirs and all still exist: whether to run a shop at all
     * ({@code cafeOperating}, cleared by pauseOperation and by changing your occupation), what the
     * hours are ({@code cafeOpenMinute}/{@code cafeCloseMinute}), and closing early on any given day
     * (close_cafe, which sets "closing" and is untouched here, so a shop closed for the day stays
     * closed for the day). What is removed is the busywork of re-deciding every morning to do the
     * thing you have already committed to doing.
     * <p>It is here because the alternative turned out to be a single point of failure for the whole
     * town. Opening was a model decision and nothing else; one wrong call - the operator was told at
     * 08:31 that it was past closing time and believed it - cost the town its only public indoor
     * space for a full simulated day, and with it every habit that goes there. A day with no model at
     * all had the same shape for the same reason: nobody ever opened the door, so five of six
     * residents stayed home from beginning to end. A town whose whole social life hangs on one
     * successful model call a day is not a town.
     * <p>Deliberately only "closed" -> "open": never reopens something the operator closed today
     * (that is "closing", and it stays), never overrides a pause, and never touches the hours. */
    private static void openOnSchedule(CompanionWorld w,Instant at) {
        if(!w.cafeOperating||!"closed".equals(w.cafeStatus)||!scheduledOpen(w,at))return;
        // An early close is a decision about TODAY, and reopening the same day would silently undo
        // it. Only that decision counts - not every other reason a shop is closed.
        if(at.atZone(ZoneId.of(w.timezone)).toLocalDate().toString().equals(w.cafeClosedForDayOn))return;
        ResidentState operator=ResidentSimulation.state(w,operatorId(w));
        if(operator==null)return;
        // He has to actually be there, and awake. The first version of this checked the clock and
        // the standing commitment and nothing else, so the shop unlocked itself at nine while its
        // owner was asleep in bed at home - and then wrote a world event saying he had opened it,
        // which was simply untrue. A door opens when the person with the key reaches it.
        Actor door=ResidentSimulation.actor(w,operator.id);
        if(!PLACE.equals(door.place())||"sleep".equals(door.activity()))return;
        w.cafeStatus="open";w.cafeStatusChangedAt=at;
        ResidentSimulation.event(w,at,"cafe_opened",PLACE,List.of(operator.id),
            door.name()+"照平常的钟点开了咖啡馆的门。",null);
    }
    static void tick(CompanionWorld w, Instant at) {
        openOnSchedule(w, at);
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

    private static void abandon(CompanionWorld w, ResidentState requester, ServiceRequest req, Instant at) {
        req.status = "abandoned"; req.resolvedAt = at;
        if (requester == null) return;
        ResidentSimulation.memory(w, requester.id, requester.id, "observed", at, "service", "离开咖啡馆时，那杯还没有等到。", List.of(), 3);
    }

    private static void resolveDelivered(CompanionWorld w, ServiceRequest req, Instant at) {
        ResidentState requester = ResidentSimulation.state(w, req.requesterId);
        boolean present = requester != null && ResidentSimulation.actor(w, req.requesterId).place().equals(req.place);
        if (present) consume(w, requester, req, at);
        else if (Duration.between(req.deliveredAt, at).getSeconds() > COLD_AFTER_SECONDS) goCold(w, req, at);
    }

    private static void consume(CompanionWorld w, ResidentState requester, ServiceRequest req, Instant at) {
        req.status = "consumed"; req.resolvedAt = at;
        ResidentState owner = ResidentSimulation.state(w, operatorId(w));
        if(owner==null)return;
        // A drink is a small immediate lift, not a substitute for sustained sleep.
        requester.energy = Math.min(100, requester.energy + 3);
        ResidentSimulation.relation(owner, requester, 3);
        String text = ResidentSimulation.actor(w, owner.id).name() + "端来了一杯，等了一会儿，但还是很暖。";
        ResidentSimulation.memory(w, requester.id, owner.id, "observed", at, "service", text, List.of(), 5);
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
