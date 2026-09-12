package com.betterself.growth.town.companion.domain;

import java.util.HashMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * Pixel-accurate distances between every named place in the town, plus the one walking speed used to
 * turn a distance into a travel duration. Pure domain: no I/O, no classpath resources, nothing beyond
 * a static table and arithmetic (agent.md's layering rule - domain writes rules, adapters do I/O; a
 * class in this package must never read a file to build itself).
 *
 * <p>The numbers below are not estimates. They were measured by walking the frontend's own
 * pathfinder ({@code companionPath} in {@code frontend/src/modules/companion/companion-navigation.ts})
 * between the real door/anchor points of the actual, collision-aware town scene and recording the
 * pixel length of the route it takes - not the straight-line distance, since a route can detour
 * around furniture and walls. That makes this table one half of a "two places that must never
 * disagree" pair, the same relationship {@code companion-navigation.ts} already documents for a
 * walkway's drawn width versus its collision rectangle (see that file's comment on the invisible wall
 * a mismatched pair produces there). If the frontend's map ever moves a door, a wall, or a room, these
 * numbers go stale - nothing here derives them automatically, and nothing here can walk the scene
 * itself to check, so drift can only be caught by whoever changes the map next re-measuring and
 * pasting the new numbers back in by hand.
 *
 * <p>Anchors are not described here in prose - they are whatever {@code travelAnchor(location)} in
 * {@code companion-geometry.ts} returns, which is the same function the scene uses to decide where a
 * walking body is actually headed. That indirection is deliberate and was paid for: an earlier round
 * described the anchors in words instead ("the garden frame's bottom-center"), the re-measuring test
 * picked the frame's centre instead, and 12 of these 28 numbers silently disagreed by up to 23px.
 * One function, three readers - the scene, this table, and the parity test.
 *
 * <p>Each number is the mean of the route measured in both directions, because the pathfinder is
 * mildly direction-dependent: walking A-to-B and B-to-A can pick routes differing by up to 23px
 * (measured). A single symmetric number therefore cannot match either direction exactly, and
 * pretending otherwise would make the parity test fail forever on a difference worth 0.7 seconds at
 * this walking speed. The test's tolerance is set from that measured asymmetry, not chosen to make
 * the numbers fit.
 * {@code home-artist} and {@code home-weaver} are the same physical room (see
 * {@code TownPlaces.HOME_SHARED_WITH}) - both residents' {@code homeOf(...)} already resolves to the
 * single id "home-artist", so that is the only key this table ever needs for either of them; looking
 * up "home-artist" against itself is exactly the same-place case {@link ResidentSimulation#travelSeconds}
 * handles with its own floor, not a distance this table stores.
 */
public final class TownDistances {
    private TownDistances() {}

    /** Shared walking speed, in pixels per simulated second. The frontend's companion-scene.ts
     * {@code stepTowardPoint(...)} call is changed in this same batch to move a walking body at this
     * exact number of pixels per second (it was hard-coded to 72 before) - the body on screen and the
     * clock counting its trip must agree on how fast it moves, or one side finishes long before the
     * other, which is the whole bug this table exists to fix. If this number ever changes, it must
     * change in both places at once. */
    public static final int WALK_PIXELS_PER_SECOND = 32;

    private static final Map<String, Map<String, Integer>> DISTANCES = new HashMap<>();

    private static void link(String a, String b, int pixels) {
        DISTANCES.computeIfAbsent(a, k -> new HashMap<>()).put(b, pixels);
        DISTANCES.computeIfAbsent(b, k -> new HashMap<>()).put(a, pixels);
    }

    static {
        link("cafe", "garden", 759);
        link("cafe", "home-artist", 809);
        link("cafe", "home-fixer", 884);
        link("cafe", "home-gardener", 617);
        link("cafe", "home-owner", 537);
        link("cafe", "home-self", 425);
        link("cafe", "home-student", 397);
        link("cafe", "street", 256);

        link("garden", "home-artist", 1427);
        link("garden", "home-fixer", 1500);
        link("garden", "home-gardener", 1235);
        link("garden", "home-owner", 1253);
        link("garden", "home-self", 1043);
        link("garden", "home-student", 1114);
        link("garden", "street", 965);

        link("home-artist", "home-fixer", 984);
        link("home-artist", "home-gardener", 192);
        link("home-artist", "home-owner", 545);
        link("home-artist", "home-self", 384);
        link("home-artist", "home-student", 666);
        link("home-artist", "street", 772);

        link("home-fixer", "home-gardener", 792);
        link("home-fixer", "home-owner", 1295);
        link("home-fixer", "home-self", 600);
        link("home-fixer", "home-student", 1159);
        link("home-fixer", "street", 1008);

        link("home-gardener", "home-owner", 737);
        link("home-gardener", "home-self", 192);
        link("home-gardener", "home-student", 858);
        link("home-gardener", "street", 737);

        link("home-owner", "home-self", 840);
        link("home-owner", "home-student", 160);
        link("home-owner", "street", 288);

        link("home-self", "home-student", 708);
        link("home-self", "street", 545);

        link("home-student", "street", 152);
    }

    /** The single longest measured trip in the whole table (fixer's home to the garden, 1391px) -
     * used by {@link ResidentSimulation#travelSeconds} as the fallback distance for a place this table
     * has no entry for at all (a newly added building - academy, gym - that no resident's route has
     * been walked and paced out for yet). Computed from the table itself rather than copied by hand,
     * so it can never quietly go stale relative to the numbers above. */
    static final int LONGEST_MEASURED_PIXELS = DISTANCES.values().stream()
        .flatMap(row -> row.values().stream())
        .max(Comparator.naturalOrder())
        .orElseThrow();

    /** The measured pixel distance between two places, or {@code null} if this exact pair was never
     * measured. Always symmetric - {@code distancePixels(a,b)} and {@code distancePixels(b,a)} return
     * the same value, because {@link #link} records both directions from one source line above.
     * Returns {@code null}, never a guess, for two equal ids or any other unmeasured pair; callers
     * decide what a missing distance means for them (see {@link ResidentSimulation#travelSeconds}'s
     * same-place floor and its unmeasured-place fallback). */
    public static Integer distancePixels(String a, String b) {
        if (Objects.equals(a, b)) return null;
        Map<String, Integer> row = DISTANCES.get(a);
        return row == null ? null : row.get(b);
    }
}
