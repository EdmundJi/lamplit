package com.betterself.growth.town.companion.domain;

import java.time.Instant;
import java.util.*;
import static com.betterself.growth.town.companion.domain.CompanionWorld.*;

/**
 * The two-layer place model. A location (the street, the cafe, the garden, or one resident's own
 * home) holds one or more positions (a bed, a window seat, a shared table); each position has an
 * optional owner, a capacity and current occupants. Pure structure and ownership - no pixel
 * coordinates, no framework, no clock reads; time comes in as a parameter. The frontend maps ids to
 * art and layout on its own.
 *
 * A position's owner gets it back on demand (a "let" moment for whoever was using it); everyone else
 * prefers an unowned spot with room, and only falls back to someone else's spot when nothing shared
 * is free. When even that fails, the caller is told to wait rather than being placed anyway.
 */
public final class TownPlaces {
    private TownPlaces() {}
    /** The five residents who each have a home: the four NPCs plus the user's own avatar, "self". */
    public static final List<String> RESIDENT_IDS = List.of("owner", "student", "artist", "gardener", "self");
    private static final Set<String> PLACES;
    static {
        Set<String> set = new LinkedHashSet<>(List.of("street", "cafe", "garden"));
        for (String id : RESIDENT_IDS) set.add(homeOf(id));
        PLACES = Collections.unmodifiableSet(set);
    }

    public static String homeOf(String residentId) { return "home-" + residentId; }
    public static boolean isHome(String place) { return place != null && place.startsWith("home-"); }
    public static Set<String> places() { return PLACES; }

    /** Populate the location/position catalog once; a no-op on a world that already has one, so it
     * is safe to call on every advance to repair an older save that predates this structure. */
    public static void seed(CompanionWorld w) {
        if (!w.locations.isEmpty()) return;
        w.locations.add(new Location("street", "street", null));
        w.locations.add(new Location("cafe", "cafe", null));
        w.locations.add(new Location("garden", "garden", null));
        for (String id : RESIDENT_IDS) w.locations.add(new Location(homeOf(id), "home", id));
        w.positions.add(position("street-bench", "street", "bench", null, 4));
        w.positions.add(position("cafe-worktable", "cafe", "table", null, 4));
        w.positions.add(position("cafe-window-seat", "cafe", "seat", "student", 1));
        w.positions.add(position("garden-bench", "garden", "bench", null, 3));
        w.positions.add(position("garden-plot", "garden", "plot", "gardener", 1));
        for (String id : RESIDENT_IDS) w.positions.add(position(homeOf(id) + "-bed", homeOf(id), "bed", id, 1));
    }
    private static Position position(String id, String place, String kind, String owner, int capacity) {
        Position p = new Position(); p.id = id; p.place = place; p.kind = kind; p.ownerId = owner; p.capacity = capacity; return p;
    }

    public static Position position(CompanionWorld w, String id) { return w.positions.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
    public static List<Position> at(CompanionWorld w, String place) { return w.positions.stream().filter(p -> p.place.equals(place)).toList(); }

    /** Give up whatever spot residentId currently holds, if any. */
    public static void release(CompanionWorld w, String residentId) {
        for (Position p : w.positions) p.occupantIds.remove(residentId);
        ResidentState r = ResidentSimulation.state(w, residentId);
        if (r != null) r.positionId = null;
    }

    public enum Outcome { SEATED, YIELDED, WAITING }

    /** Claim a spot for residentId at `place`, preferring a position of kind `kind` when it matters
     * for the action (a bed for sleeping, a seat for studying). Always releases any spot the
     * resident previously held first. */
    public static Outcome claim(CompanionWorld w, String residentId, String place, String kind, Instant now) {
        release(w, residentId);
        List<Position> here = at(w, place);
        Position mine = here.stream().filter(p -> residentId.equals(p.ownerId)).findFirst().orElse(null);
        if (mine != null) {
            boolean displaced = !mine.occupantIds.isEmpty();
            List<String> displacedIds = new ArrayList<>(mine.occupantIds);
            mine.occupantIds.clear();
            for (String other : displacedIds) { ResidentState r = ResidentSimulation.state(w, other); if (r != null) r.positionId = null; }
            seat(w, residentId, mine);
            return displaced ? Outcome.YIELDED : Outcome.SEATED;
        }
        Comparator<Position> preference = Comparator
            .<Position>comparingInt(p -> p.ownerId == null ? 0 : 1)
            .thenComparingInt(p -> kind != null && kind.equals(p.kind) ? 0 : 1);
        Position choice = here.stream().filter(p -> p.occupantIds.size() < p.capacity).sorted(preference).findFirst().orElse(null);
        if (choice != null) { seat(w, residentId, choice); return Outcome.SEATED; }
        return Outcome.WAITING;
    }
    private static void seat(CompanionWorld w, String residentId, Position p) {
        p.occupantIds.add(residentId);
        ResidentState r = ResidentSimulation.state(w, residentId);
        if (r != null) r.positionId = p.id;
    }
}
