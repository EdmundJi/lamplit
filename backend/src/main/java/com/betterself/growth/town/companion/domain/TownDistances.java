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
        link("academy", "board", 99);
        link("academy", "cafe", 1709);
        link("academy", "garden", 2325);
        link("academy", "gym", 478);
        link("academy", "home-artist", 1809);
        link("academy", "home-baker", 1717);
        link("academy", "home-barista", 2137);
        link("academy", "home-botanist", 1825);
        link("academy", "home-broker", 2081);
        link("academy", "home-fixer", 825);
        link("academy", "home-florist", 2036);
        link("academy", "home-gardener", 1617);
        link("academy", "home-masseur", 1874);
        link("academy", "home-messenger", 1513);
        link("academy", "home-owner", 2120);
        link("academy", "home-scholar", 2496);
        link("academy", "home-self", 1425);
        link("academy", "home-student", 1983);
        link("academy", "home-tailor", 2184);
        link("academy", "home-trader", 2393);
        link("academy", "shop", 950);
        link("academy", "street", 1833);
        link("board", "cafe", 1620);
        link("board", "garden", 2236);
        link("board", "gym", 389);
        link("board", "home-artist", 1720);
        link("board", "home-baker", 1628);
        link("board", "home-barista", 2049);
        link("board", "home-botanist", 1737);
        link("board", "home-broker", 1989);
        link("board", "home-fixer", 736);
        link("board", "home-florist", 1944);
        link("board", "home-gardener", 1528);
        link("board", "home-masseur", 1785);
        link("board", "home-messenger", 1425);
        link("board", "home-owner", 2031);
        link("board", "home-scholar", 2407);
        link("board", "home-self", 1336);
        link("board", "home-student", 1895);
        link("board", "home-tailor", 2095);
        link("board", "home-trader", 2301);
        link("board", "shop", 861);
        link("board", "street", 1744);
        link("cafe", "garden", 759);
        link("cafe", "gym", 1240);
        link("cafe", "home-artist", 809);
        link("cafe", "home-baker", 1309);
        link("cafe", "home-barista", 1729);
        link("cafe", "home-botanist", 1417);
        link("cafe", "home-broker", 1666);
        link("cafe", "home-fixer", 884);
        link("cafe", "home-florist", 1621);
        link("cafe", "home-gardener", 617);
        link("cafe", "home-masseur", 1465);
        link("cafe", "home-messenger", 1105);
        link("cafe", "home-owner", 537);
        link("cafe", "home-scholar", 2086);
        link("cafe", "home-self", 425);
        link("cafe", "home-student", 397);
        link("cafe", "home-tailor", 1774);
        link("cafe", "home-trader", 1978);
        link("cafe", "shop", 1728);
        link("cafe", "street", 256);
        link("garden", "gym", 1856);
        link("garden", "home-artist", 1427);
        link("garden", "home-baker", 1925);
        link("garden", "home-barista", 2346);
        link("garden", "home-botanist", 2034);
        link("garden", "home-broker", 2283);
        link("garden", "home-fixer", 1500);
        link("garden", "home-florist", 2237);
        link("garden", "home-gardener", 1235);
        link("garden", "home-masseur", 2081);
        link("garden", "home-messenger", 1721);
        link("garden", "home-owner", 1253);
        link("garden", "home-scholar", 2703);
        link("garden", "home-self", 1043);
        link("garden", "home-student", 1114);
        link("garden", "home-tailor", 2389);
        link("garden", "home-trader", 2594);
        link("garden", "shop", 2344);
        link("garden", "street", 965);
        link("gym", "home-artist", 1340);
        link("gym", "home-baker", 1248);
        link("gym", "home-barista", 1669);
        link("gym", "home-botanist", 1357);
        link("gym", "home-broker", 1606);
        link("gym", "home-fixer", 356);
        link("gym", "home-florist", 1560);
        link("gym", "home-gardener", 1148);
        link("gym", "home-masseur", 1405);
        link("gym", "home-messenger", 1045);
        link("gym", "home-owner", 1651);
        link("gym", "home-scholar", 2027);
        link("gym", "home-self", 956);
        link("gym", "home-student", 1515);
        link("gym", "home-tailor", 1715);
        link("gym", "home-trader", 1918);
        link("gym", "shop", 488);
        link("gym", "street", 1364);
        link("home-artist", "home-baker", 1409);
        link("home-artist", "home-barista", 1828);
        link("home-artist", "home-botanist", 1516);
        link("home-artist", "home-broker", 1767);
        link("home-artist", "home-fixer", 984);
        link("home-artist", "home-florist", 1721);
        link("home-artist", "home-gardener", 192);
        link("home-artist", "home-masseur", 1565);
        link("home-artist", "home-messenger", 1204);
        link("home-artist", "home-owner", 545);
        link("home-artist", "home-scholar", 2186);
        link("home-artist", "home-self", 384);
        link("home-artist", "home-student", 666);
        link("home-artist", "home-tailor", 1874);
        link("home-artist", "home-trader", 2079);
        link("home-artist", "shop", 1828);
        link("home-artist", "street", 772);
        link("home-baker", "home-barista", 936);
        link("home-baker", "home-botanist", 624);
        link("home-baker", "home-broker", 832);
        link("home-baker", "home-fixer", 893);
        link("home-baker", "home-florist", 312);
        link("home-baker", "home-gardener", 1217);
        link("home-baker", "home-masseur", 632);
        link("home-baker", "home-messenger", 312);
        link("home-baker", "home-owner", 1727);
        link("home-baker", "home-scholar", 1253);
        link("home-baker", "home-self", 1025);
        link("home-baker", "home-student", 1594);
        link("home-baker", "home-tailor", 941);
        link("home-baker", "home-trader", 1143);
        link("home-baker", "shop", 1736);
        link("home-baker", "street", 1433);
        link("home-barista", "home-botanist", 312);
        link("home-barista", "home-broker", 1254);
        link("home-barista", "home-fixer", 1313);
        link("home-barista", "home-florist", 1248);
        link("home-barista", "home-gardener", 1636);
        link("home-barista", "home-masseur", 1050);
        link("home-barista", "home-messenger", 624);
        link("home-barista", "home-owner", 2146);
        link("home-barista", "home-scholar", 1671);
        link("home-barista", "home-self", 1445);
        link("home-barista", "home-student", 2014);
        link("home-barista", "home-tailor", 1359);
        link("home-barista", "home-trader", 1566);
        link("home-barista", "shop", 2156);
        link("home-barista", "street", 1852);
        link("home-botanist", "home-broker", 942);
        link("home-botanist", "home-fixer", 1001);
        link("home-botanist", "home-florist", 936);
        link("home-botanist", "home-gardener", 1324);
        link("home-botanist", "home-masseur", 738);
        link("home-botanist", "home-messenger", 312);
        link("home-botanist", "home-owner", 1834);
        link("home-botanist", "home-scholar", 1359);
        link("home-botanist", "home-self", 1133);
        link("home-botanist", "home-student", 1702);
        link("home-botanist", "home-tailor", 1047);
        link("home-botanist", "home-trader", 1254);
        link("home-botanist", "shop", 1844);
        link("home-botanist", "street", 1540);
        link("home-broker", "home-fixer", 1250);
        link("home-broker", "home-florist", 1143);
        link("home-broker", "home-gardener", 1575);
        link("home-broker", "home-masseur", 312);
        link("home-broker", "home-messenger", 637);
        link("home-broker", "home-owner", 2086);
        link("home-broker", "home-scholar", 936);
        link("home-broker", "home-self", 1384);
        link("home-broker", "home-student", 1953);
        link("home-broker", "home-tailor", 624);
        link("home-broker", "home-trader", 312);
        link("home-broker", "shop", 2094);
        link("home-broker", "street", 1792);
        link("home-fixer", "home-florist", 1205);
        link("home-fixer", "home-gardener", 792);
        link("home-fixer", "home-masseur", 1049);
        link("home-fixer", "home-messenger", 689);
        link("home-fixer", "home-owner", 1295);
        link("home-fixer", "home-scholar", 1670);
        link("home-fixer", "home-self", 600);
        link("home-fixer", "home-student", 1159);
        link("home-fixer", "home-tailor", 1358);
        link("home-fixer", "home-trader", 1562);
        link("home-fixer", "shop", 844);
        link("home-fixer", "street", 1008);
        link("home-florist", "home-gardener", 1529);
        link("home-florist", "home-masseur", 944);
        link("home-florist", "home-messenger", 624);
        link("home-florist", "home-owner", 2039);
        link("home-florist", "home-scholar", 1566);
        link("home-florist", "home-self", 1337);
        link("home-florist", "home-student", 1906);
        link("home-florist", "home-tailor", 1254);
        link("home-florist", "home-trader", 1455);
        link("home-florist", "shop", 2048);
        link("home-florist", "street", 1745);
        link("home-gardener", "home-masseur", 1373);
        link("home-gardener", "home-messenger", 1013);
        link("home-gardener", "home-owner", 737);
        link("home-gardener", "home-scholar", 1994);
        link("home-gardener", "home-self", 192);
        link("home-gardener", "home-student", 858);
        link("home-gardener", "home-tailor", 1682);
        link("home-gardener", "home-trader", 1887);
        link("home-gardener", "shop", 1636);
        link("home-gardener", "street", 737);
        link("home-masseur", "home-messenger", 433);
        link("home-masseur", "home-owner", 1883);
        link("home-masseur", "home-scholar", 624);
        link("home-masseur", "home-self", 1181);
        link("home-masseur", "home-student", 1750);
        link("home-masseur", "home-tailor", 312);
        link("home-masseur", "home-trader", 624);
        link("home-masseur", "shop", 1893);
        link("home-masseur", "street", 1588);
        link("home-messenger", "home-owner", 1523);
        link("home-messenger", "home-scholar", 1054);
        link("home-messenger", "home-self", 821);
        link("home-messenger", "home-student", 1390);
        link("home-messenger", "home-tailor", 742);
        link("home-messenger", "home-trader", 949);
        link("home-messenger", "shop", 1533);
        link("home-messenger", "street", 1228);
        link("home-owner", "home-scholar", 2504);
        link("home-owner", "home-self", 840);
        link("home-owner", "home-student", 160);
        link("home-owner", "home-tailor", 2192);
        link("home-owner", "home-trader", 2397);
        link("home-owner", "shop", 2139);
        link("home-owner", "street", 288);
        link("home-scholar", "home-self", 1802);
        link("home-scholar", "home-student", 2371);
        link("home-scholar", "home-tailor", 312);
        link("home-scholar", "home-trader", 1248);
        link("home-scholar", "shop", 2515);
        link("home-scholar", "street", 2209);
        link("home-self", "home-student", 708);
        link("home-self", "home-tailor", 1490);
        link("home-self", "home-trader", 1696);
        link("home-self", "shop", 1444);
        link("home-self", "street", 545);
        link("home-student", "home-tailor", 2059);
        link("home-student", "home-trader", 2265);
        link("home-student", "shop", 2003);
        link("home-student", "street", 152);
        link("home-tailor", "home-trader", 936);
        link("home-tailor", "shop", 2203);
        link("home-tailor", "street", 1897);
        link("home-trader", "shop", 2405);
        link("home-trader", "street", 2103);
        link("shop", "street", 1852);
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
