package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/**
 * Ownership, borrowed and given away - docs/01-requirements.md 第二版「世界」「有所有权，可借可赠，不
 * 引入货币」: money settles "you owe me one" on the spot, and reciprocity is the bet this town is
 * making instead, so lending and gifting have to exist without it.
 *
 * <p>What lives here is deliberately thin. A {@link CompanionWorld.WorldObject} already carries an
 * {@code ownerId}; this class only ever moves that ownership (a gift) or moves who is currently
 * holding the thing while ownership stays put (a loan), and records the one fact a bystander standing
 * right there could have seen - who, what, to whom, when, and (for a loan) whether it has come back.
 * <b>Never why.</b> That is the same red line {@link CompanionWorld.Deed} already draws for a
 * habitual action, and it is, per docs/01's own words, "这一版最要紧的一条边界": if a resident ever
 * comes to believe they owe somebody a turn, that belief is theirs to write, in their own
 * self-authored memory text - never a field this class populates for them. Every method below returns
 * a plain boolean and writes only observable facts; none of them ever takes or stores a reason.
 *
 * <p>Both a lend and a gift require the two residents to be standing in the same place at the moment
 * of the handoff - a real object does not teleport, and this mirrors the same "only whoever was
 * actually there learns anything" shape {@code DoorService} already established for the cafe's door
 * (see this class's {@code witnessExchange}, which writes the exact same present-only witness memory
 * DoorService.lock does; the same asymmetry, generalised to a second class of physical fact rather
 * than copied into a second door). A recipient is never named by the model (docs/04-decisions.md "模
 * 型永远不被问'能不能'，只被问'要不要'和'为什么'" - who is even a legal recipient is the rules'
 * question): {@link #soleOtherResidentHere} resolves it deterministically as whoever else happens to
 * be right here, and simply declines (never guesses) when nobody or more than one somebody is
 * present. A real "hand this to a specific person out of several" would need a second target field in
 * the decision schema, which lives in adapters/QwenResidentMind.java - out of this batch's scope; see
 * the batch's own report.
 */
final class Lending {
    private Lending() {}

    private static WorldObject find(CompanionWorld w, String itemId) {
        return w.objects.stream().filter(o -> o.id().equals(itemId)).findFirst().orElse(null);
    }
    private static void replace(CompanionWorld w, WorldObject updated) {
        for (int i = 0; i < w.objects.size(); i++) if (w.objects.get(i).id().equals(updated.id())) { w.objects.set(i, updated); return; }
    }
    private static void replaceLoan(CompanionWorld w, Loan updated) {
        for (int i = 0; i < w.loans.size(); i++) if (w.loans.get(i).id().equals(updated.id())) { w.loans.set(i, updated); return; }
    }

    /** Whether itemId is currently out on a real, unreturned loan. A gift is never "on loan" - once
     * given, nothing about it is still outstanding, so it is immediately lendable again by its new
     * owner. */
    static boolean isOnLoan(CompanionWorld w, String itemId) {
        return w.loans.stream().anyMatch(l -> l.itemId().equals(itemId) && !l.gift() && l.returnedAt() == null);
    }

    /** The one other resident standing at `place` right now, or null if that is nobody or more than
     * one somebody. Deliberately declines rather than picking among several - see this class's own
     * doc comment for why the rules, not a guess, must decide who is even a legal recipient. */
    static String soleOtherResidentHere(CompanionWorld w, String residentId, String place) {
        String only = null;
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(residentId)) continue;
            Actor a = ResidentSimulation.actor(w, other.id);
            if (a == null || !place.equals(a.place()) || !ResidentSimulation.sameRoom(w,residentId,other.id)) continue;
            if (only != null) return null;
            only = other.id;
        }
        return only;
    }

    /** A real loan: ownership stays with lenderId, but the object visibly moves to wherever
     * borrowerId is standing - the same thing that happens when you actually hand somebody something.
     * Fails (no state changed) if the item does not exist, is not lenderId's own, is already out on
     * loan, or the two are not standing together right now. */
    static boolean lend(CompanionWorld w, String lenderId, String borrowerId, String itemId, Instant at) {
        WorldObject item = find(w, itemId);
        if (item == null || lenderId.equals(borrowerId) || !lenderId.equals(item.ownerId()) || isOnLoan(w, itemId)) return false;
        String place = ResidentSimulation.actor(w, lenderId).place();
        Actor borrower = ResidentSimulation.actor(w, borrowerId);
        if (borrower == null || !ResidentSimulation.sameRoom(w,lenderId,borrowerId)) return false;
        w.loans.add(new Loan("loan-" + (++w.eventSequence), itemId, lenderId, borrowerId, at, null, false));
        replace(w, new WorldObject(item.id(), item.kind(), place, TownPlaces.roomForResident(w,borrowerId,place), item.label(), item.state(), item.projectId(), item.ownerId(), borrowerId));
        witnessExchange(w, "lend", lenderId, borrowerId, item, place, at);
        return true;
    }

    /** An outright gift: ownership itself moves to recipientId, permanently - nothing is left owing,
     * which is why this never creates an open (returnable) loan. Same preconditions as {@link #lend}. */
    static boolean gift(CompanionWorld w, String giverId, String recipientId, String itemId, Instant at) {
        WorldObject item = find(w, itemId);
        if (item == null || giverId.equals(recipientId) || !giverId.equals(item.ownerId()) || isOnLoan(w, itemId)) return false;
        String place = ResidentSimulation.actor(w, giverId).place();
        Actor recipient = ResidentSimulation.actor(w, recipientId);
        if (recipient == null || !ResidentSimulation.sameRoom(w,giverId,recipientId)) return false;
        w.loans.add(new Loan("loan-" + (++w.eventSequence), itemId, giverId, recipientId, at, at, true));
        replace(w, new WorldObject(item.id(), item.kind(), place, TownPlaces.roomForResident(w,recipientId,place), item.label(), item.state(), item.projectId(), recipientId, recipientId));
        witnessExchange(w, "gift", giverId, recipientId, item, place, at);
        return true;
    }

    /** Handing a borrowed item back - either side of the open loan may initiate it, but (mirroring
     * {@link #lend}) only while lender and borrower are standing together again. Marks the loan
     * returned and moves the object back to wherever the lender now is; does nothing to a gift (there
     * is nothing to return) or to an item with no open loan for residentId at all. */
    static boolean returnItem(CompanionWorld w, String residentId, String itemId, Instant at) {
        Loan open = w.loans.stream()
            .filter(l -> l.itemId().equals(itemId) && !l.gift() && l.returnedAt() == null)
            .filter(l -> l.lenderId().equals(residentId) || l.borrowerId().equals(residentId))
            .findFirst().orElse(null);
        if (open == null) return false;
        Actor lender = ResidentSimulation.actor(w, open.lenderId()), borrower = ResidentSimulation.actor(w, open.borrowerId());
        if (lender == null || borrower == null || !ResidentSimulation.sameRoom(w,open.lenderId(),open.borrowerId())) return false;
        String place = lender.place();
        replaceLoan(w, new Loan(open.id(), open.itemId(), open.lenderId(), open.borrowerId(), open.lentAt(), at, false));
        WorldObject item = find(w, itemId);
        if (item != null) replace(w, new WorldObject(item.id(), item.kind(), place, TownPlaces.roomForResident(w,open.lenderId(),place), item.label(), item.state(), item.projectId(), item.ownerId(), open.lenderId()));
        witnessExchange(w, "return", open.borrowerId(), open.lenderId(), item, place, at);
        return true;
    }

    /** Owned portable things follow their current holder. The open Loan remains the authority for
     * why holder differs from owner; this merely keeps visibility and the four-level address honest
     * while that person walks around. */
    static void followHolder(CompanionWorld w,String residentId,String place){
        for(int i=0;i<w.objects.size();i++){
            WorldObject item=w.objects.get(i);
            if(!residentId.equals(item.holderId()))continue;
            w.objects.set(i,new WorldObject(item.id(),item.kind(),place,TownPlaces.roomForResident(w,residentId,place),item.label(),item.state(),item.projectId(),item.ownerId(),item.holderId()));
        }
    }

    private static final String TOPIC = "lending";
    /** The witness pass every physical handoff gets - see this class's own doc comment for why this
     * mirrors {@code DoorService.lock} rather than inventing a second asymmetry. Both parties always
     * get the plain fact; anyone else only if they were actually standing right there and not asleep
     * or mid-walk, and only ever the same bare fact, never a reason. */
    private static void witnessExchange(CompanionWorld w, String kind, String fromId, String toId, WorldObject item, String place, Instant at) {
        if (item == null) return;
        String fromName = ResidentSimulation.actor(w, fromId).name(), toName = ResidentSimulation.actor(w, toId).name();
        String text = switch (kind) {
            case "lend" -> fromName + "把" + item.label() + "借给了" + toName + "。";
            case "gift" -> fromName + "把" + item.label() + "送给了" + toName + "。";
            default -> fromName + "把" + item.label() + "还给了" + toName + "。";
        };
        List<String> actorIds = new ArrayList<>(List.of(fromId, toId));
        ResidentSimulation.memory(w, fromId, fromId, "observed", at, TOPIC, text, List.of(), 5);
        ResidentSimulation.memory(w, toId, fromId, "observed", at, TOPIC, text, List.of(), 5);
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(fromId) || other.id.equals(toId)) continue;
            Actor oa = ResidentSimulation.actor(w, other.id);
            if (oa == null || !place.equals(oa.place()) || !ResidentSimulation.sameRoom(w,fromId,other.id) || "sleep".equals(oa.activity()) || "walk".equals(oa.activity())) continue;
            ResidentSimulation.memory(w, other.id, fromId, "observed", at, TOPIC, "看见" + text, List.of(), 4);
            actorIds.add(other.id);
        }
        ResidentSimulation.event(w, at, "item_" + kind, place, actorIds, text, null);
    }
}
