package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Real room lighting - "让小人到房间可以操作开关". Before this, a lit window was a client-side guess
 * from the clock plus who happened to be nearby (frontend companion-scene.ts sync(): night from the
 * clock, "lit" from occupancy/cafeOpen/nothing at all). There was no backend light state, so nobody
 * ever actually switched anything on - it just was, whenever the frontend's own guess said so.
 *
 * <p>One {@link WorldObject} per lit indoor room this town already gives a room id to (see
 * TownPlaces): every resident's own bedroom, the cafe's main room, the academy, the gym, the shop.
 * {@code kind} is "light", {@code id} is {@code "light:"+roomId}, {@code state} is "on"/"off",
 * starting "off" - {@link #ensure} backfills any missing one the same idempotent way {@code
 * TownPlaces.seed} backfills everything else (and is called from there, so every existing call site
 * that already repairs an old save picks this up for free).
 *
 * <p>Turning it on or off is modelled as a short, ordinary activity (see {@code
 * ResidentSimulation.schedule}'s own "switch_light" step and its departure/sleep checks) rather than
 * a claimable {@link Position}: nobody ever contends for a light switch the way two people contend
 * for a bed or the stove - it is never scarce, never queued for - so the occupancy/FIFO machinery
 * {@code TownPlaces.claim} exists for genuinely contested furniture has nothing to do here. A light
 * is only ever on or off, and the one resident standing in the room is the only one who can change it.
 *
 * <p>Deterministic, and deliberately the whole of the rule: nobody's light changes itself just
 * because the clock crossed into night, or because this world happens to be looked at. It changes
 * only because a resident who is actually there, awake, walked up to the switch.
 */
final class LightService {
    private LightService() {}
    static final String KIND = "light";
    /** "A couple of sim minutes" at the switch before the resident's real plan begins - see
     * ResidentSimulation.schedule's "switch_light" step. */
    static final int SWITCH_SECONDS = 120;

    /** Same night thresholds the frontend already renders by (companion-scene.ts sync(): {@code
     * minutes<360||minutes>=1140}) - 06:00 to 19:00 local is day. Kept as the one place this backend
     * itself defines "dark", rather than reusing {@code CompanionWorld.period} (a different, wider
     * night window used for day-part labelling elsewhere and never meant to answer this question). */
    static boolean isDark(CompanionWorld w, Instant at) {
        var local = at.atZone(ZoneId.of(w.timezone));
        int minute = local.getHour() * 60 + local.getMinute();
        return minute < 360 || minute >= 1140;
    }

    static String lightId(String roomId) { return "light:" + roomId; }

    private static WorldObject light(CompanionWorld w, String roomId) {
        if (roomId == null) return null;
        return w.objects.stream().filter(o -> KIND.equals(o.kind()) && roomId.equals(o.roomId())).findFirst().orElse(null);
    }
    private static boolean isOn(CompanionWorld w, String roomId) {
        WorldObject l = light(w, roomId);
        return l != null && "on".equals(l.state());
    }

    /** Backfills a light for every lit indoor room this world already has a room id for. Idempotent -
     * an existing light is never replaced - and safe to call before every resident has an Actor yet
     * (very early in {@code ResidentSeed.initialize}, where {@code TownPlaces.seed} already runs once
     * before any resident state exists): a home room whose owner cannot be named yet is simply left
     * for {@code seed}'s own later call in the same method to fill in, the same two-pass healing its
     * other backfills already rely on. */
    static void ensure(CompanionWorld w) {
        for (Room room : w.rooms)
            if ("bedroom".equals(room.kind()))
                for (String residentId : room.residentIds()) ensureLight(w, room.id(), room.buildingId(), homeLabel(w, residentId));
        ensurePublicLight(w, "cafe-main", "cafe");
        ensurePublicLight(w, "academy-reading-room", "academy");
        ensurePublicLight(w, "gym-training-room", "gym");
        ensurePublicLight(w, "shop-workroom", "shop");
    }
    private static void ensurePublicLight(CompanionWorld w, String roomId, String place) {
        if (TownPlaces.room(w, roomId) != null) ensureLight(w, roomId, place, ResidentSimulation.placeName(place) + "的灯");
    }
    private static void ensureLight(CompanionWorld w, String roomId, String place, String label) {
        if (label == null) return; // resident not yet actorized - heals on a later seed() pass
        String id = lightId(roomId);
        if (w.objects.stream().anyMatch(o -> id.equals(o.id()))) return;
        w.objects.add(new WorldObject(id, KIND, place, roomId, label, "off", null, null));
    }
    private static String homeLabel(CompanionWorld w, String residentId) {
        String name = residentName(w, residentId);
        return name == null ? null : name + "家的灯";
    }
    private static String residentName(CompanionWorld w, String id) {
        if ("self".equals(id)) return w.avatar == null ? null : w.avatar.name();
        return w.residents.stream().filter(a -> a.id().equals(id)).findFirst().map(Actor::name).orElse(null);
    }
    private static Actor actorOrNull(CompanionWorld w, String id) {
        if ("self".equals(id)) return w.avatar;
        return w.residents.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    /** Whether a resident standing in {@code roomId} right now, in the dark, would find the switch
     * worth reaching for - see ResidentSimulation.schedule, the only caller. False for any room this
     * town gave no light object at all (the street, the garden, a shared home's common room or
     * bathroom - genuinely nothing to switch), and false whenever it is already on. */
    static boolean shouldSwitchOn(CompanionWorld w, String roomId, Instant at) {
        if (roomId == null || !isDark(w, at)) return false;
        WorldObject l = light(w, roomId);
        return l != null && "off".equals(l.state());
    }

    private static void setState(CompanionWorld w, String roomId, String state, Instant at) {
        for (int i = 0; i < w.objects.size(); i++) {
            WorldObject o = w.objects.get(i);
            if (!KIND.equals(o.kind()) || !roomId.equals(o.roomId())) continue;
            if (state.equals(o.state())) return;
            w.objects.set(i, new WorldObject(o.id(), o.kind(), o.place(), o.roomId(), o.label(), state, o.projectId(), o.ownerId(), o.holderId()));
            w.objectStateChangedAt.put(o.id(), at);
            return;
        }
    }

    /** The resident actually flips it on, once the short "开灯" activity has run its course - see
     * ResidentSimulation.schedule's "switch_light" step, the only caller. */
    static void turnOn(CompanionWorld w, String residentId, String place, String roomId, Instant at) {
        if (roomId == null || isOn(w, roomId)) return;
        setState(w, roomId, "on", at);
        ResidentSimulation.event(w, at, "light_on", place, List.of(residentId), ResidentSimulation.actor(w, residentId).name() + "摸到门边的开关，把灯打开了。", null);
    }

    /** True once nobody left awake in {@code roomId} still needs the light - the only question either
     * turn-off path below asks. {@code excludeId} is whoever is doing the leaving/falling asleep,
     * already off the "still needs it" list by the time this is asked, whatever their own state. */
    private static boolean nobodyElseAwakeThere(CompanionWorld w, String place, String roomId, String excludeId) {
        for (ResidentState other : w.residentStates) {
            if (other.id.equals(excludeId)) continue;
            Actor a = actorOrNull(w, other.id);
            if (a == null || !place.equals(a.place()) || "sleep".equals(a.activity())) continue;
            if (roomId.equals(ResidentSimulation.roomOf(w, other.id))) return false;
        }
        return true;
    }
    /** The last awake resident stepping out of a lit room switches it off on the way - see
     * ResidentSimulation.moveOrSchedule (the moment a cross-building trip actually sets out) and
     * schedule() (a same-building move from one room to another, which never goes through
     * moveOrSchedule at all). Idempotent by way of {@link #setState}'s own no-op-if-unchanged guard,
     * so both call sites are free to ask about the same departure without a duplicate event. */
    static void onLeftRoom(CompanionWorld w, String residentId, String place, String roomId, Instant at) {
        if (roomId == null || !isOn(w, roomId)) return;
        if (!nobodyElseAwakeThere(w, place, roomId, residentId)) return;
        setState(w, roomId, "off", at);
        ResidentSimulation.event(w, at, "light_off", place, List.of(residentId), ResidentSimulation.actor(w, residentId).name() + "临走前，顺手关了灯。", null);
    }
    /** The last awake resident there switches it off before going to sleep, rather than sleeping with
     * it on - see ResidentSimulation.schedule's own "sleep".equals(action) check, the only caller. */
    static void onGoesToSleep(CompanionWorld w, String residentId, String place, String roomId, Instant at) {
        if (roomId == null || !isOn(w, roomId)) return;
        if (!nobodyElseAwakeThere(w, place, roomId, residentId)) return;
        setState(w, roomId, "off", at);
        ResidentSimulation.event(w, at, "light_off", place, List.of(residentId), ResidentSimulation.actor(w, residentId).name() + "睡前把灯关了。", null);
    }
    /** The cafe's own operator switch, folded into an administrative moment that already narrates the
     * light going out in its own event text ({@code CafeService.finishClosingIfEmpty}'s "咖啡馆的灯熄
     * 了，今天已经打烊。") - a second "light_off" event here would only repeat what that one already
     * said, so this flips the state with no event of its own. */
    static void turnOffRoom(CompanionWorld w, String roomId, Instant at) {
        if (roomId == null || !isOn(w, roomId)) return;
        setState(w, roomId, "off", at);
    }
}
