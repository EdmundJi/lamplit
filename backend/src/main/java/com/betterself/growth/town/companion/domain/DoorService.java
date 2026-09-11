package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/**
 * The physical rules for the town's first lockable door - currently only the cafe's. Only home doors
 * were deliberately left out: locking the user's own avatar out of their own home would be a product
 * accident, and today there is no reason to lock a home at all (see docs/06-society.md).
 *
 * <p>Exactly two facts live here, on the door itself: whether it is locked, and who locked it (see
 * {@link CompanionWorld.Door}). Everything else is a resident's own decision, never this class's:
 * <b>whether</b> to lock is a real model choice with both directions offered (see
 * {@code ResidentSimulation.availableActions}/{@code DECISION_ACTIONS} and the balanced prompt
 * language in {@code QwenResidentMind.decide}), never a clock ("过了营业时间自动锁" would be exactly
 * the kind of table-clock rule docs/04-decisions.md and docs/06-society.md both rule out). This class
 * only ever validates physical presence and flips the one bit.
 *
 * <p>Consequence is perception, not instruction: a resident who cannot get in is told the one fact
 * ("咖啡馆的门锁着") and nothing else - never "you should knock" or "you could go elsewhere" - see
 * {@link #perceiveLockedOut}, called from {@code ResidentSimulation.schedule} at the moment a travel
 * plan would otherwise land someone at the cafe's door.
 *
 * <p>Locking is a public, in-the-moment act: whoever is standing there at the time sees who did it
 * (see {@link #lock}'s witness pass, the same "in-place people get a memory, nobody else does" shape
 * every other public act in this town already follows - conversation turns, contributions,
 * celebrations). Anyone who was not there only ever learns the door is locked, never from whom -
 * this town's first real information asymmetry.
 */
final class DoorService {
    private DoorService() {}
    static final String CAFE_PLACE = "cafe";
    private static final String CAFE_DOOR_ID = "cafe-door";
    /** Both the witness memory at lock time and the arrival-blocked memory file under the same topic,
     * so a resident's own recall of "the door situation" is never split across two unrelated labels. */
    private static final String TOPIC = "cafe-door";

    /** Self-healing lookup, exactly like the empty-list defaults elsewhere in this save format: an old
     * world simply has no door yet, and the first read creates the one door this batch knows about.
     * Also called explicitly from world init/repair (see ResidentSeed.initialize and
     * ResidentSimulation.reconcileLegacyPlaces) so a door exists before any decision ever needs one. */
    static CompanionWorld.Door cafeDoor(CompanionWorld w) {
        for (CompanionWorld.Door d : w.doors) if (CAFE_PLACE.equals(d.place)) return d;
        CompanionWorld.Door d = new CompanionWorld.Door();
        d.id = CAFE_DOOR_ID; d.place = CAFE_PLACE; d.locked = false;
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

    /**
     * A resident standing in the cafe right now decides to lock its door. No connection to
     * cafeStatus/business hours on purpose (see this class's own doc comment) - any resident actually
     * present may do this, not only the operator; who ends up locked out and how they feel about it is
     * exactly the point docs/06-society.md is after. Returns false (nothing applied) if the resident is
     * not actually there or the door is already locked - re-locking an already-locked door is not a
     * new fact.
     */
    static boolean lock(CompanionWorld w, ResidentState resident, Instant at) {
        Actor a = ResidentSimulation.actor(w, resident.id);
        if (a == null || !CAFE_PLACE.equals(a.place())) return false;
        CompanionWorld.Door door = cafeDoor(w);
        if (door.locked) return false;
        door.locked = true; door.lockedBy = resident.id; door.lockedAt = at;
        String name = a.name();
        ResidentSimulation.memory(w, resident.id, resident.id, "observed", at, TOPIC, "我把咖啡馆的门锁上了。", List.of(), 5);
        List<String> witnessIds = new ArrayList<>();
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(resident.id)) continue;
            Actor oa = ResidentSimulation.actor(w, other.id);
            if (oa == null || !CAFE_PLACE.equals(oa.place()) || "sleep".equals(oa.activity()) || "walk".equals(oa.activity())) continue;
            ResidentSimulation.memory(w, other.id, resident.id, "observed", at, TOPIC, "看见" + name + "把咖啡馆的门锁上了。", List.of(), 6);
            witnessIds.add(other.id);
        }
        List<String> actorIds = new ArrayList<>(); actorIds.add(resident.id); actorIds.addAll(witnessIds);
        ResidentSimulation.event(w, at, "door_locked", CAFE_PLACE, actorIds, name + "把咖啡馆的门锁上了。", null);
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
}
