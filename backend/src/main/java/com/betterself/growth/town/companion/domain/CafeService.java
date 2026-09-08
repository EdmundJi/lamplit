package com.betterself.growth.town.companion.domain;

import java.time.*;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/**
 * The coffee/water service chain: a resident wants a drink, the owner - when present, free at the
 * counter, and when duty actually wins out over whatever else they were doing - makes and delivers
 * it, and the resident drinks it. Every link can break: the owner is out, the counter never gets
 * tended before patience runs out, a drink is made for someone who has already left, or a proactive
 * pour goes to someone who did not want it this time. A broken link is written only into memory,
 * never announced as a {@link WorldEvent} - see the "does not shout" requirement in 01-requirements.
 *
 * <p>This is deliberately not "if someone is waiting, go serve them". The owner accumulates a
 * {@code dutyPressure} every tick the counter has someone waiting (heavier during business hours,
 * lighter but never zero off them - business hours only change how heavy the pressure feels, not
 * whether it exists), and {@link #decide} compares that pressure against a competing pull toward
 * whatever else the owner would rather be doing right now - their own unfinished project, and a term
 * that grows as their own conscientiousness erodes. Either can win. Duty pressure decays on its own
 * when nobody is waiting, so it is a real, moving quantity the owner weighs each time they are free to
 * choose, not a boolean.
 *
 * <p>Two pieces of evidence feed back into the owner's own conscientiousness, in {@link #reflectOnDuty}:
 * repeated, voiced complaints about waiting nudge it up; repeated interruptions of the owner's own
 * project with nobody complaining nudge it down. Both are small, bounded, and require more than one
 * occurrence - a single bad afternoon cannot flip a personality.
 *
 * <p>Regulars are also where an expectation gets learned (and can be learned wrong): when the same
 * customer comes back for another drink shortly after the last one, twice in a row, the owner starts
 * pouring before being asked. Two coincidences are enough - it is not guarded against being wrong, and
 * a proactive pour the customer does not actually want this time just goes cold, same as any other
 * broken link.
 */
final class CafeService {
    private CafeService() {}
    static final String OWNER = "owner";
    static final String PLACE = "cafe";
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

    /** A resident, wanting a drink at the cafe, asks for one - unless they already have a request in
     * flight. Also where the owner's learned-expectation counter for this customer moves: a request
     * that follows the last one they were actually served within {@link #FOLLOWUP_WINDOW_SECONDS} is
     * one more piece of (possibly coincidental) evidence that this person always wants another. */
    static void request(CompanionWorld w, ResidentState requester, Instant at) {
        if (requester == null || requester.id.equals(OWNER)) return;
        if (w.serviceRequests.stream().anyMatch(r -> r.requesterId.equals(requester.id) && open(r.status))) return;
        ResidentState owner = ResidentSimulation.state(w, OWNER);
        if (owner != null) {
            Instant last = owner.lastServedAt.get(requester.id);
            int streak = last != null && Duration.between(last, at).getSeconds() <= FOLLOWUP_WINDOW_SECONDS
                ? owner.repeatVisitStreak.getOrDefault(requester.id, 0) + 1 : 0;
            owner.repeatVisitStreak.put(requester.id, streak);
            if (streak >= 2 && !Boolean.TRUE.equals(owner.anticipatesRefill.get(requester.id))) {
                owner.anticipatesRefill.put(requester.id, true);
                ResidentSimulation.memory(w, owner.id, owner.id, "reflection", at, "service",
                    ResidentSimulation.actor(w, requester.id).name() + "最近总是不一会儿又要一杯，我记住了，下次想在她开口前就准备好。", List.of(), 7);
            }
        }
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
        int hour = at.atZone(ZoneId.of(w.timezone)).getHour();
        return hour >= 8 && hour < 21;
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
        proactivelyServe(w, owner, at);
    }

    /** Pours, unprompted, for at most one learned regular currently at the cafe with nothing already
     * pending for them and the per-customer cooldown elapsed - the concrete form of "predicting the
     * user's need" the requirements ask for. Whether it actually lands is judged later, in
     * {@link #tick}: an explicit request is always wanted; a guess is only picked up if the customer
     * still plausibly wants it, which is exactly what lets a learned rule misfire. */
    private static void proactivelyServe(CompanionWorld w, ResidentState owner, Instant at) {
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(OWNER) || other.id.equals("self")) continue;
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
        if (!present) { abandon(w, requester, req, at, false); return; }
        int familiarity = ResidentSimulation.state(w, OWNER).relationships.getOrDefault(req.requesterId, 40);
        long patience = patienceSeconds(requester, familiarity);
        if (Duration.between(req.requestedAt, at).getSeconds() >= patience) abandon(w, requester, req, at, true);
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

    private static void abandon(CompanionWorld w, ResidentState requester, ServiceRequest req, Instant at, boolean waitedTooLong) {
        req.status = "abandoned"; req.resolvedAt = at;
        if (requester == null) return;
        String privateText = waitedTooLong ? "等了好一会儿也没人来，我先去忙别的了。" : "本来想喝口热的，柜台没人，先去忙别的了。";
        ResidentSimulation.memory(w, requester.id, requester.id, "observed", at, "service", privateText, List.of(), waitedTooLong ? 5 : 3);
        // Not saying anything out loud is not the same as not minding: whether this shows is gated on
        // the requester's own extroversion, exactly the "speaks up or keeps it to themselves" pattern
        // affectionExpressed already uses elsewhere - nothing reaches the owner unless it is said.
        if (waitedTooLong && Personality.of(requester).extroversion() >= 55) {
            ResidentState owner = ResidentSimulation.state(w, OWNER);
            String heardId = ResidentSimulation.memory(w, owner.id, requester.id, "heard", at, "service",
                ResidentSimulation.actor(w, requester.id).name() + "抱怨说等太久了，" + ResidentSimulation.actor(w, owner.id).name() + "心里有点不是滋味。", List.of(), 8);
            owner.complaintsSinceDutyReflection++;
            pushEvidence(owner.dutyComplaintEvidenceIds, heardId);
            ResidentSimulation.relation(requester, owner, -4);
            ResidentSimulation.event(w, at, "complaint", req.place, List.of(requester.id, owner.id),
                ResidentSimulation.actor(w, requester.id).name() + "等太久了，跟" + ResidentSimulation.actor(w, owner.id).name() + "说了一句。", null);
        }
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
        ResidentState owner = ResidentSimulation.state(w, OWNER);
        owner.lastServedAt.put(requester.id, at);
        requester.energy = Math.min(100, requester.energy + 6);
        requester.mood = "被照顾到";
        ResidentSimulation.relation(owner, requester, req.proactive ? 5 : 3);
        String text = req.proactive
            ? "还没开口，" + ResidentSimulation.actor(w, owner.id).name() + "就端了一杯过来，像是记住了我的习惯。"
            : ResidentSimulation.actor(w, owner.id).name() + "端来了一杯，等了一会儿，但还是很暖。";
        ResidentSimulation.memory(w, requester.id, owner.id, "observed", at, "service", text, List.of(), req.proactive ? 7 : 5);
    }

    private static void goCold(CompanionWorld w, ServiceRequest req, Instant at) {
        req.status = "cold"; req.resolvedAt = at;
        ResidentState owner = ResidentSimulation.state(w, OWNER);
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
        if (!r.id.equals(OWNER)) return;
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
