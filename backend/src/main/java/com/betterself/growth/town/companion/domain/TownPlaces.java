package com.betterself.growth.town.companion.domain;

import java.time.Duration;
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
    /** Flat-mates: an id in here does not get a Location of its own - {@code homeOf} resolves
     * straight through to the resident it lives with, so every "go to my own home" check already in
     * ResidentSimulation (it computes {@code homeOf(residentId)} directly, with no world lookup)
     * lands both flat-mates on the exact same location without that file needing to know sharing
     * exists at all. See {@link #addFlatmate} for how the shared occupant still ends up with
     * separately-owned furniture rather than nothing. Declared before {@link #PLACES} below, which
     * calls {@code homeOf} during class initialization - out of order here means a null map at that
     * moment, not merely a wrong answer. */
    private static final Map<String, String> HOME_SHARED_WITH = Map.of("weaver", "artist");
    private static final Set<String> PLACES;
    static {
        Set<String> set = new LinkedHashSet<>(List.of("street", "cafe", "garden"));
        for (String id : RESIDENT_IDS) set.add(homeOf(id));
        PLACES = Collections.unmodifiableSet(set);
    }

    public static String homeOf(String residentId) { return "home-" + HOME_SHARED_WITH.getOrDefault(residentId, residentId); }
    public static boolean isHome(String place) { return place != null && place.startsWith("home-"); }
    public static Set<String> places() { return PLACES; }
    /** Shared places are fixed, but a deliberately authored resident can bring a home without
     * becoming a new hard-coded enum entry. */
    public static boolean contains(CompanionWorld w,String place){return PLACES.contains(place)||w.locations.stream().anyMatch(l->l.id().equals(place));}
    static void addHome(CompanionWorld w,String residentId){
        String home=homeOf(residentId);
        if(w.locations.stream().noneMatch(l->l.id().equals(home)))w.locations.add(new Location(home,"home",residentId));
        if(position(w,home+"-bed")==null)w.positions.add(position(home+"-bed",home,"bed",residentId,1));
        if(position(w,home+"-desk")==null)w.positions.add(position(home+"-desk",home,"desk",residentId,1));
    }

    /** A flat-mate moving into an existing resident's home: no new Location, but a real bed and a
     * real desk of their own inside it. Ids are suffixed with the flat-mate's own id specifically so
     * they can never collide with (or, like a plain second {@code addHome} call would, silently be
     * skipped in favour of) the host's own bed and desk - that four-people-one-bed shape was exactly
     * the earlier bug the per-resident home fix corrected, and two people sharing one address on
     * purpose must not quietly regress back into it. Callers should route {@code homeOf(flatmateId)}
     * through {@link #HOME_SHARED_WITH} first, or nothing else in the simulation will ever think to
     * send the flat-mate here at all. */
    static void addFlatmate(CompanionWorld w, String flatmateId, String hostId) {
        addHome(w, hostId);
        String home = homeOf(hostId);
        // Named "home-<flatmate>-bed", not "home-<host>-bed-<flatmate>": every other resident's
        // furniture follows the first shape and the frontend's POSITION_SLOTS table is keyed on it,
        // so the second shape silently loses her pixels and drops her onto a guessed spot. Which
        // location she is in is already carried by Position.place - the id does not need to repeat it.
        String bed = "home-" + flatmateId + "-bed", desk = "home-" + flatmateId + "-desk";
        if (position(w, bed) == null) w.positions.add(position(bed, home, "bed", flatmateId, 1));
        if (position(w, desk) == null) w.positions.add(position(desk, home, "desk", flatmateId, 1));
    }

    /** A position kind that cannot be borrowed even when nothing shared is free: the coffee machine
     * and counter are the owner's tools, not a spare chair. See {@code claim()}'s fallback filter. */
    private static final String EQUIPMENT = "equipment";

    /** Populate the location/position catalog once; a no-op on a world that already has one, so it
     * is safe to call on every advance to repair an older save that predates this structure.
     * {@code ensureCounter} runs unconditionally afterward so a save from before the cafe counter
     * existed still self-heals it, exactly like this method self-heals the rest of the catalog. */
    public static void seed(CompanionWorld w) {
        if (w.locations.isEmpty()) {
            w.locations.add(new Location("street", "street", null));
            w.locations.add(new Location("cafe", "cafe", null));
            w.locations.add(new Location("garden", "garden", null));
            for (String id : RESIDENT_IDS) w.locations.add(new Location(homeOf(id), "home", id));
            w.positions.add(position("street-bench", "street", "bench", null, 4));
            w.positions.add(position("cafe-worktable", "cafe", "table", null, 4));
            w.positions.add(position("cafe-window-seat", "cafe", "seat", "student", 1));
            w.positions.add(position("garden-bench", "garden", "bench", null, 3));
            w.positions.add(position("garden-plot", "garden", "plot", "gardener", 1));
            for (String id : RESIDENT_IDS) {
                w.positions.add(position(homeOf(id) + "-bed", homeOf(id), "bed", id, 1));
                w.positions.add(position(homeOf(id) + "-desk", homeOf(id), "desk", id, 1));
            }
        }
        // Repair saves created after beds existed but before home desks did, including manually
        // authored residents whose ids are intentionally absent from RESIDENT_IDS.
        for (Location location : new ArrayList<>(w.locations))
            if ("home".equals(location.kind()) && location.ownerId() != null) addHome(w, location.ownerId());
        // A flat-mate owns no Location of their own, so the repair loop above never notices them;
        // self-heal their furniture explicitly the same way, for the same reason.
        for (Map.Entry<String, String> flatmate : HOME_SHARED_WITH.entrySet()) addFlatmate(w, flatmate.getKey(), flatmate.getValue());
        ensureQuietCafeSeats(w);
        ensureCounter(w);
    }
    private static void ensureQuietCafeSeats(CompanionWorld w){
        // The original window place stays the student's owned seat. Five neighbouring one-person
        // desks are public and independently claimable; they are not extra capacity on one slot.
        for(int index=2;index<=6;index++){String id="cafe-window-"+index;if(position(w,id)==null)w.positions.add(position(id,"cafe","seat",null,1));}
    }
    private static void ensureCounter(CompanionWorld w) {
        String operator=w.cafeOperatorId==null?"owner":w.cafeOperatorId;
        Position counter=position(w,"cafe-counter");
        if (counter == null) w.positions.add(position("cafe-counter", "cafe", EQUIPMENT, operator, 1));
        else if(operator.equals(counter.ownerId)||"owner".equals(counter.ownerId)&&!"owner".equals(operator))counter.ownerId=operator;
    }
    private static Position position(String id, String place, String kind, String owner, int capacity) {
        Position p = new Position(); p.id = id; p.place = place; p.kind = kind; p.ownerId = owner; p.capacity = capacity; return p;
    }

    public static Position position(CompanionWorld w, String id) { return w.positions.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
    public static List<Position> at(CompanionWorld w, String place) { return w.positions.stream().filter(p -> p.place.equals(place)).toList(); }

    /** Give up whatever spot residentId currently holds, if any. No event is recorded - see the
     * {@code (w, residentId, at)} overload below for the version that records one. This bare form
     * stays for callers with no clock in scope (this file's own tests) and for {@link #claim}'s own
     * internal first step, which must not announce an intermediate clearing that the same call is
     * about to immediately supersede with a real seat or a genuine wait. */
    public static void release(CompanionWorld w, String residentId) {
        for (Position p : w.positions) p.occupantIds.remove(residentId);
        ResidentState r = ResidentSimulation.state(w, residentId);
        if (r != null) r.positionId = null;
    }
    /** Same release, but when this resident actually held a position, records the moment as a
     * "left_spot" world event (see {@link #writeSeatEvent}) - the occupancy half of the town's
     * seating norm ("座位谁先占谁用，后来者道歉或让开") that otherwise leaves no trace at all. */
    public static void release(CompanionWorld w, String residentId, Instant at) {
        ResidentState r = ResidentSimulation.state(w, residentId);
        String previous = r != null ? r.positionId : null;
        release(w, residentId);
        if (previous != null && seatEventDue(w, residentId, previous, at)) writeSeatEvent(w, "left_spot", residentId, previous, at);
    }

    public enum Outcome { SEATED, YIELDED, WAITING }

    /** Claim a spot for residentId at `place`, preferring a position of kind `kind` when it matters
     * for the action (a bed for sleeping, a seat for studying). Always releases any spot the
     * resident previously held first.
     * <p>Records "took_spot"/"left_spot" world events (see {@link #writeSeatEvent}) for every net
     * change of who holds which named position - the claimant leaving wherever they were and landing
     * somewhere new, and anyone actually displaced from an owned spot they were only borrowing. A
     * resident re-claiming the exact same position they already held (the common case: the same
     * habitual action re-scheduled against a seat nobody ever left) produces no event at all - only a
     * change in who occupies a position is a fact worth recording, not the bookkeeping call itself. */
    public static Outcome claim(CompanionWorld w, String residentId, String place, String kind, Instant now) {
        ResidentState self = ResidentSimulation.state(w, residentId);
        String previousPositionId = self != null ? self.positionId : null;
        release(w, residentId);
        List<Position> here = at(w, place);
        // Matched by kind too (when the caller asked for one): an owner reclaims a specific spot of
        // theirs - the counter when they mean to work it, a shared table when they don't - rather than
        // always landing on whichever position they happen to own first at this place.
        Position mine = here.stream().filter(p -> (residentId.equals(p.ownerId) || (EQUIPMENT.equals(p.kind) && CafeService.mayTend(w,residentId))) && (kind == null || kind.equals(p.kind))).findFirst().orElse(null);
        if (mine != null) {
            boolean displaced = !mine.occupantIds.isEmpty();
            List<String> displacedIds = new ArrayList<>(mine.occupantIds);
            mine.occupantIds.clear();
            for (String other : displacedIds) {
                ResidentState r = ResidentSimulation.state(w, other);
                if (r != null) r.positionId = null;
                // The owner reclaiming a spot they already occupied themselves is not a displacement
                // of anyone - only an actual visitor being asked to give up a borrowed seat is.
                if (!other.equals(residentId) && seatEventDue(w, other, mine.id, now)) writeSeatEvent(w, "left_spot", other, mine.id, now);
            }
            seat(w, residentId, mine);
            recordSeatTransition(w, residentId, previousPositionId, mine.id, now);
            return displaced ? Outcome.YIELDED : Outcome.SEATED;
        }
        Comparator<Position> preference = Comparator
            .<Position>comparingInt(p -> p.ownerId == null ? 0 : 1)
            .thenComparingInt(p -> kind != null && kind.equals(p.kind) ? 0 : 1);
        Position choice = here.stream().filter(p -> p.occupantIds.size() < p.capacity)
            // An owned seat can be borrowed when nothing shared is free; an owned piece of equipment
            // (the coffee machine, the counter) cannot - falling back onto someone else's tools is not
            // the same thing as falling back onto their chair.
            .filter(p -> p.ownerId == null || p.ownerId.equals(residentId) || !EQUIPMENT.equals(p.kind) || CafeService.mayTend(w,residentId))
            .sorted(preference).findFirst().orElse(null);
        if (choice != null) { seat(w, residentId, choice); recordSeatTransition(w, residentId, previousPositionId, choice.id, now); return Outcome.SEATED; }
        // Nowhere to sit: whatever this resident held a moment ago (release() above already cleared
        // it) is genuinely given up now, not merely re-confirmed.
        recordSeatTransition(w, residentId, previousPositionId, null, now);
        return Outcome.WAITING;
    }
    /** Records the "left_spot" for wherever residentId just came from and the "took_spot" for
     * wherever they landed (either may be null - arriving from nowhere, or ending up waiting with
     * nowhere at all) as one atomic decision, so the two halves of a single hop are never throttled
     * independently of each other - see the note on {@link #seatEventDue} for why that matters. A
     * no-op when the position genuinely did not change (the common re-scheduling case). */
    private static void recordSeatTransition(CompanionWorld w, String residentId, String previousPositionId, String newPositionId, Instant now) {
        if (Objects.equals(previousPositionId, newPositionId)) return;
        if (!seatEventDue(w, residentId, previousPositionId, now) && !seatEventDue(w, residentId, newPositionId, now)) return;
        if (previousPositionId != null) writeSeatEvent(w, "left_spot", residentId, previousPositionId, now);
        if (newPositionId != null) writeSeatEvent(w, "took_spot", residentId, newPositionId, now);
    }
    /** Writes the one trace the town's seating norm ("谁在用什么东西，别人默认不动，除非物主表态") had
     * never left anywhere: a "took_spot"/"left_spot" world event naming who, which exact
     * {@code positionId}, and when. Text is a bare fact (who, verb, which named spot) - never a
     * reason, so it can never repeat a resident's own private label for why they were there. Callers
     * decide whether the event is due (see {@link #seatEventDue}) before ever reaching here - this
     * method only ever writes, on the assumption the position genuinely exists. */
    private static void writeSeatEvent(CompanionWorld w, String type, String residentId, String positionId, Instant at) {
        Position p = position(w, positionId);
        // A world built directly for a narrow unit test (this file's own tests, several already in
        // this package) may carry a ResidentState/Position without ever bothering to seed a matching
        // Actor - actor() throws in that case, which is exactly the kind of test-only inconvenience
        // this fact-recording side effect should never be the thing that breaks. No actor, no event.
        if (p == null || !hasActor(w, residentId)) return;
        String name = ResidentSimulation.actor(w, residentId).name();
        String where = ResidentSimulation.seatPhrase(w, positionId);
        String text = "took_spot".equals(type) ? name + "占了" + where + "。" : name + "离开了" + where + "。";
        ResidentSimulation.event(w, at, type, p.place, List.of(residentId), text, null, positionId);
    }
    /** The three positions the seating-norm measurement cares about most: each sits in a shared
     * public place and has one specific, known owner (the student's window seat, the gardener's
     * plot, the cafe operator's counter), so whether anyone else avoids one while its owner is away -
     * or yields it back the moment the owner returns - is the entire point of ever recording these
     * events. Every took_spot/left_spot on one of these three is always recorded, never throttled. */
    private static final Set<String> PRIORITY_POSITIONS = Set.of("cafe-window-seat", "garden-plot", "cafe-counter");
    /** How long one resident's own comings and goings from every OTHER (non-priority: an unowned
     * seat, a shared bench, their own home bed or desk) position stay quiet before a second one is
     * worth recording. Chosen empirically: an unthrottled two-day rule-only run produced 857
     * took_spot/left_spot events out of 910 total (94%) - almost all of it one resident restlessly
     * cycling through their own home desk/bed and the street/garden benches every few minutes, which
     * would silently evict every contribution, celebration and conversation from the shared
     * 80-event window (see ResidentSimulation.event's own eviction) - a second, quieter way for this
     * same measurement effort to erase itself. Thirty minutes brought that down to a minority of the
     * window (see the report for the exact re-measured count) while still letting a later, separate
     * stretch at an unowned spot the same afternoon get its own event - see SeatClaimEventTest for
     * the number this constant is pinned to. */
    static final long NON_PRIORITY_SEAT_EVENT_COOLDOWN_SECONDS = 30 * 60;
    /** Whether a took_spot/left_spot for this resident at this position is worth writing at all:
     * always true for one of the three {@link #PRIORITY_POSITIONS}, otherwise only once this
     * resident's most recent non-priority seat event still sitting in the shared event window is far
     * enough behind `at`. Returns true for a null positionId (nothing to gate) so callers can pass
     * either half of a transition through uniformly.
     * <p>Callers that write two related events (a left_spot and a took_spot from one hop) must call
     * this once for the pair, before writing either - checking it again before the second write would
     * see the first write's own just-recorded timestamp and always find the gap too small, silently
     * dropping every non-priority took_spot that follows a non-priority left_spot.
     * <p>Scans the existing event list rather than keeping a per-resident bookkeeping field of its
     * own, so this cooldown adds no new persisted state to the world. */
    private static boolean seatEventDue(CompanionWorld w, String residentId, String positionId, Instant at) {
        if (positionId == null) return true;
        if (PRIORITY_POSITIONS.contains(positionId)) return true;
        for (int i = w.events.size() - 1; i >= 0; i--) {
            WorldEvent e = w.events.get(i);
            if (!"took_spot".equals(e.type()) && !"left_spot".equals(e.type())) continue;
            if (PRIORITY_POSITIONS.contains(e.positionId())) continue;
            if (!e.actorIds().contains(residentId)) continue;
            return Duration.between(e.at(), at).getSeconds() >= NON_PRIORITY_SEAT_EVENT_COOLDOWN_SECONDS;
        }
        return true;
    }
    /** Whether residentId has an actual Actor to be named in a seat event's text - see the note on
     * {@link #writeSeatEvent}. Mirrors exactly how {@link ResidentSimulation#actor} itself resolves
     * "self" (through {@code w.avatar}) versus every NPC (through {@code w.residents}), just without
     * that method's throw when nothing is found. */
    private static boolean hasActor(CompanionWorld w, String residentId) {
        if ("self".equals(residentId)) return w.avatar != null;
        return w.residents.stream().anyMatch(a -> a.id().equals(residentId));
    }
    private static void seat(CompanionWorld w, String residentId, Position p) {
        p.occupantIds.add(residentId);
        ResidentState r = ResidentSimulation.state(w, residentId);
        if (r != null) r.positionId = p.id;
    }
    /** A completed takeover changes the actual equipment owner; an assist/delegation only grants
     * temporary use through CafeService.mayTend and therefore does not silently transfer property. */
    static void transferCafeCounter(CompanionWorld w,String operatorId){
        w.cafeOperatorId=operatorId;ensureCounter(w);Position counter=position(w,"cafe-counter");counter.ownerId=operatorId;
    }
}
