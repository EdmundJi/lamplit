package com.betterself.growth.town.companion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/**
 * The physical rules for the town's lockable doors: the cafe's, and - since docs/01-requirements.md
 * 第二版「世界」asked for 「进别人家由所有权和门决定……默认进不去，被邀请或门没锁就进得去」 - one on
 * every home.
 *
 * <p>The two kinds of door start from opposite defaults, and that difference is the whole feature: the
 * cafe {@link #cafeDoor} defaults <b>unlocked</b> (a business wants customers to walk in), a home
 * {@link #homeDoor} defaults <b>locked</b> (nobody's front door is open to a stranger by default). What
 * they share is everything else - exactly two facts on the door itself (whether it is locked, and who
 * locked it - see {@link CompanionWorld.Door}), <b>whether</b> to lock or unlock is always a real model
 * choice with both directions offered, never a clock, and this class only ever validates physical
 * presence and flips the one bit.
 *
 * <p>A resident can never be locked out of their own home - {@link #canEnterHome} checks that first,
 * before the lock bit is even read. Home doors were left out of the first pass specifically to avoid
 * that accident (see docs/05-notes.md); this class closes the same door on it here.
 *
 * <p>「被邀请或门没锁就进得去」 is an OR, not an AND: {@link #canEnterHome} also passes with a live
 * {@link CompanionWorld.HomeInvitation}, regardless of the lock bit - an invitation is a private pass
 * good for one visit, a locked door is a public state anyone can see is shut. Neither implies the
 * other: an unlocked home lets anyone in without needing an invitation at all; an invitation gets its
 * one guest in even while the door stays locked to everyone else.
 *
 * <p>Consequence is perception, not instruction: a resident who cannot get in is told the one fact
 * ("门锁着") and nothing else - never "you should knock" or "you could go elsewhere" - see {@link
 * #perceiveLockedOut}, called from {@code ResidentSimulation.schedule} at the moment a travel plan
 * would otherwise land someone at a locked door.
 *
 * <p>Locking is a public, in-the-moment act: whoever is standing there at the time sees who did it (see
 * {@link #lock}'s witness pass, the same "in-place people get a memory, nobody else does" shape every
 * other public act in this town already follows). Anyone who was not there only ever learns the door is
 * locked, never from whom - this town's first real information asymmetry.
 */
final class DoorService {
    private DoorService() {}
    static final String CAFE_PLACE = "cafe";
    private static final String CAFE_DOOR_ID = "cafe-door";
    /** Both the witness memory at lock time and the arrival-blocked memory file under the same topic,
     * so a resident's own recall of "the door situation" is never split across two unrelated labels. */
    private static final String TOPIC = "cafe-door";
    private static final String HOME_TOPIC = "home-door";
    /** How long a single invitation stays good for. Long enough that "come by later" is a real answer
     * and not a race against the clock - the guest may be mid-plan, mid-conversation, or simply choose
     * not to go straight away, and none of that should burn the pass - but not a standing season ticket
     * either: docs/01's "只在此刻成立的问句" spirit applies here too, an invitation is for roughly one
     * visit's worth of time, not forever. */
    private static final long INVITATION_TTL_SECONDS = 3 * 3600;

    /** Self-healing lookup, exactly like the empty-list defaults elsewhere in this save format: an old
     * world simply has no door yet, and the first read creates the one door this batch knows about.
     * Also called explicitly from world init/repair (see ResidentSeed.initialize and
     * ResidentSimulation.reconcileLegacyPlaces) so a door exists before any decision ever needs one. */
    static CompanionWorld.Door cafeDoor(CompanionWorld w) { return doorAt(w, CAFE_PLACE, CAFE_DOOR_ID, false); }
    /** Same self-healing shape as {@link #cafeDoor}, one per home, defaulting locked - see this class's
     * own doc comment for why the two kinds of door start from opposite defaults. {@code homePlace} is
     * a {@code TownPlaces.homeOf(...)} id, shared by every flat-mate the way the home Location already
     * is - one door per household, not one per person inside it. */
    static CompanionWorld.Door homeDoor(CompanionWorld w, String homePlace) { return doorAt(w, homePlace, homePlace + "-door", true); }
    private static CompanionWorld.Door doorAt(CompanionWorld w, String place, String id, boolean defaultLocked) {
        for (CompanionWorld.Door d : w.doors) if (place.equals(d.place)) return d;
        CompanionWorld.Door d = new CompanionWorld.Door();
        d.id = id; d.place = place; d.locked = defaultLocked;
        w.doors.add(d);
        return d;
    }

    static boolean isLocked(CompanionWorld w) { return cafeDoor(w).locked; }

    /** Whether residentId may pass through the cafe's door right now: unlocked, or locked by
     * residentId themselves - see this class's own doc comment, item five ("锁上的人自己能开"). Never
     * checked against anyone already standing inside; only {@code ResidentSimulation.schedule} decides
     * when an arrival actually needs asking. */
    static boolean canEnter(CompanionWorld w, String residentId) {
        CompanionWorld.Door door = cafeDoor(w);
        return !door.locked || residentId.equals(door.lockedBy);
    }

    /** Whether residentId may pass through the door of {@code homePlace} right now. Checked in this
     * order because each earlier check makes the later ones irrelevant, not because of any priority
     * between them: (1) living there always works, lock state or not - see this class's own doc
     * comment on why a resident can never be shut out of their own home; (2) whoever locked it can
     * always let themselves back in, same as the cafe; (3) a live invitation is a private pass that
     * works regardless of the lock bit; (4) otherwise, only an unlocked door lets anyone through. */
    static boolean canEnterHome(CompanionWorld w, String residentId, String homePlace, Instant now) {
        if (homePlace.equals(TownPlaces.homeOf(residentId))) return true;
        CompanionWorld.Door door = homeDoor(w, homePlace);
        if (residentId.equals(door.lockedBy)) return true;
        if (hasLiveInvitation(w, residentId, homePlace, now)) return true;
        return !door.locked;
    }

    private static boolean hasLiveInvitation(CompanionWorld w, String guestId, String homePlace, Instant now) {
        return w.homeInvitations.stream().anyMatch(inv -> inv.homeId.equals(homePlace) && inv.guestId.equals(guestId) && now.isBefore(inv.expiresAt));
    }

    /**
     * A resident standing in the cafe right now decides to lock its door. No connection to
     * cafeStatus/business hours on purpose (see this class's own doc comment) - any resident actually
     * present may do this, not only the operator; who ends up locked out and how they feel about it is
     * exactly the point docs/05-notes.md is after. Returns false (nothing applied) if the resident is
     * not actually there or the door is already locked - re-locking an already-locked door is not a
     * new fact.
     */
    static boolean lock(CompanionWorld w, ResidentState resident, Instant at) {
        return setLocked(w, resident, cafeDoor(w), CAFE_PLACE, TOPIC, "咖啡馆", true, at);
    }

    /** The home equivalent of {@link #lock}: only a resident who actually lives there (host or
     * flat-mate - {@code homePlace.equals(TownPlaces.homeOf(resident.id))} is true for both, since
     * {@code homeOf} already resolves a flat-mate straight through to the shared address) may lock or
     * unlock its door, and only while physically standing in it. {@code locking=false} unlocks - the
     * two are the same operation on opposite starting states, offered as two distinct decision actions
     * so each reads as its own equally ordinary choice rather than one action that means different
     * things depending on where the bit already was. */
    static boolean setHomeLocked(CompanionWorld w, ResidentState resident, boolean locking, Instant at) {
        String home = TownPlaces.homeOf(resident.id);
        return setLocked(w, resident, homeDoor(w, home), home, HOME_TOPIC, "自己家", locking, at);
    }

    private static boolean setLocked(CompanionWorld w, ResidentState resident, CompanionWorld.Door door, String place, String topic, String placeName, boolean locking, Instant at) {
        Actor a = ResidentSimulation.actor(w, resident.id);
        if (a == null || !place.equals(a.place())) return false;
        if (door.locked == locking) return false;
        door.locked = locking; door.lockedBy = locking ? resident.id : null; door.lockedAt = locking ? at : null;
        String name = a.name();
        String selfText = locking ? "我把" + placeName + "的门锁上了。" : "我把" + placeName + "的门打开了。";
        String witnessText = locking ? "看见" + name + "把" + placeName + "的门锁上了。" : "看见" + name + "把" + placeName + "的门打开了。";
        ResidentSimulation.memory(w, resident.id, resident.id, "observed", at, topic, selfText, List.of(), 5);
        List<String> witnessIds = new ArrayList<>();
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(resident.id)) continue;
            Actor oa = ResidentSimulation.actor(w, other.id);
            if (oa == null || !place.equals(oa.place()) || "sleep".equals(oa.activity()) || "walk".equals(oa.activity())) continue;
            ResidentSimulation.memory(w, other.id, resident.id, "observed", at, topic, witnessText, List.of(), 6);
            witnessIds.add(other.id);
        }
        List<String> actorIds = new ArrayList<>(); actorIds.add(resident.id); actorIds.addAll(witnessIds);
        ResidentSimulation.event(w, at, locking ? "door_locked" : "door_unlocked", place, actorIds, name + (locking ? "把" + placeName + "的门锁上了。" : "把" + placeName + "的门打开了。"), null);
        return true;
    }

    /**
     * The one fact a resident who cannot get in receives - never an instruction (see this class's own
     * doc comment). Deliberately silent about who locked it: this resident was not there when it
     * happened, and {@code lockedBy} exists precisely so someone who WAS there (see {@link #lock})
     * knows something this resident does not.
     */
    static void perceiveLockedOut(CompanionWorld w, ResidentState resident, Instant at) {
        ResidentSimulation.memory(w, resident.id, resident.id, "observed", at, TOPIC, "咖啡馆的门锁着，我进不去。", List.of(), 5);
    }

    /** Same shape as {@link #perceiveLockedOut}, for a home instead of the cafe - deliberately says
     * only that the door was shut, never whose fault that is or who could have let them in. */
    static void perceiveLockedOutOfHome(CompanionWorld w, ResidentState resident, Instant at) {
        ResidentSimulation.memory(w, resident.id, resident.id, "observed", at, HOME_TOPIC, "那扇门锁着，我进不去。", List.of(), 5);
    }

    /**
     * Grants guestId a one-visit pass into hostId's home, issued the moment hostId decides to invite
     * them - see {@code ResidentSimulation}'s {@code invite_home}. Structural fact only: who, whose
     * home, until when, exactly {@link CompanionWorld.HomeInvitation}'s own doc comment. Superseding
     * behaviour is deliberately absent - a resident can hold more than one live invitation into the
     * same home at once (visited last week, invited again today) and that is fine; nothing here needs
     * to decide which one is "the" invitation.
     */
    static void invite(CompanionWorld w, String hostId, String guestId, Instant at) {
        CompanionWorld.HomeInvitation invite = new CompanionWorld.HomeInvitation();
        invite.id = "hi-" + (++w.eventSequence); invite.homeId = TownPlaces.homeOf(hostId);
        invite.hostId = hostId; invite.guestId = guestId; invite.at = at; invite.expiresAt = at.plusSeconds(INVITATION_TTL_SECONDS);
        w.homeInvitations.add(invite);
    }

    /** Whether guestId currently holds a usable pass into hostId's home - what {@code
     * ResidentSimulation.availableActions} offers {@code visit_home} against, so the model is only ever
     * shown a home it has a real, known-to-it reason to believe it can enter (docs/04-decisions.md
     * 「模型永远不被问'能不能'，只被问'要不要'和'为什么'」). Does not itself consult the lock bit - an
     * unlocked-but-never-invited home is not something this guest has any way of knowing about, so it
     * is correctly absent from what they may choose to visit; see this class's own doc comment on the
     * OR only mattering once someone actually tries the door. */
    static boolean isInvited(CompanionWorld w, String guestId, String homeId, Instant now) { return hasLiveInvitation(w, guestId, homeId, now); }

    /** Every home guestId currently holds a live invitation into, oldest first - what {@code
     * availableActions} turns into legal {@code visit_home} targets. A plain lookup, not a consuming
     * read: {@link #consumeInvitation} is the only place a pass is actually spent. */
    static List<String> invitedHomes(CompanionWorld w, String guestId, Instant now) {
        List<String> homes = new ArrayList<>();
        for (CompanionWorld.HomeInvitation inv : w.homeInvitations)
            if (inv.guestId.equals(guestId) && now.isBefore(inv.expiresAt) && !homes.contains(inv.homeId)) homes.add(inv.homeId);
        return homes;
    }

    /** Spends one invitation into homeId on guestId's behalf, on an actual successful entry - a pass
     * used to get in once is used, the same way a promise settles once its moment comes. Removes
     * exactly one matching invitation (the earliest, so an old one is used before a newer one) rather
     * than every one for this pair, so an extra standing invitation is not silently thrown away by one
     * visit. No-op, never throws, if there was none - a resident who got in through an unlocked door
     * had nothing to spend. */
    static void consumeInvitation(CompanionWorld w, String guestId, String homeId, Instant now) {
        CompanionWorld.HomeInvitation earliest = null;
        for (CompanionWorld.HomeInvitation inv : w.homeInvitations) {
            if (!inv.guestId.equals(guestId) || !inv.homeId.equals(homeId) || !now.isBefore(inv.expiresAt)) continue;
            if (earliest == null || inv.at.isBefore(earliest.at)) earliest = inv;
        }
        if (earliest != null) w.homeInvitations.remove(earliest);
    }

    /** Whether an invitation from hostId to guestId, issued this recently, is still live - the guard
     * against inviting the same person again a moment later just because the fact of having done so is
     * not itself remembered as "already handled" anywhere else. Mirrors the cooldown shape every other
     * repeatable social action in this town already uses (encounters, ventures, promises): a real,
     * bounded gap, never a permanent one-time-only rule. */
    static boolean recentlyInvited(CompanionWorld w, String hostId, String guestId, Instant now) {
        return w.homeInvitations.stream().anyMatch(inv -> inv.hostId.equals(hostId) && inv.guestId.equals(guestId)
            && Duration.between(inv.at, now).getSeconds() < INVITATION_TTL_SECONDS);
    }
}
