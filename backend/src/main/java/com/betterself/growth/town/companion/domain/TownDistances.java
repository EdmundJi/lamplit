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
        link("academy", "cafe", 1549);
        link("academy", "garden", 2164);
        link("academy", "gym", 478);
        link("academy", "home-artist", 1649);
        link("academy", "home-baker", 1461);
        link("academy", "home-barista", 1881);
        link("academy", "home-botanist", 1569);
        link("academy", "home-broker", 1761);
        link("academy", "home-fixer", 665);
        link("academy", "home-florist", 1780);
        link("academy", "home-gardener", 1457);
        link("academy", "home-masseur", 1554);
        link("academy", "home-messenger", 1258);
        link("academy", "home-owner", 1960);
        link("academy", "home-scholar", 2174);
        link("academy", "home-self", 1265);
        link("academy", "home-student", 1823);
        link("academy", "home-tailor", 1863);
        link("academy", "home-trader", 2073);
        link("academy", "shop", 854);
        link("academy", "street", 1673);
        link("board", "cafe", 1460);
        link("board", "garden", 2076);
        link("board", "gym", 389);
        link("board", "home-artist", 1560);
        link("board", "home-baker", 1372);
        link("board", "home-barista", 1793);
        link("board", "home-botanist", 1481);
        link("board", "home-broker", 1669);
        link("board", "home-fixer", 576);
        link("board", "home-florist", 1688);
        link("board", "home-gardener", 1368);
        link("board", "home-masseur", 1465);
        link("board", "home-messenger", 1169);
        link("board", "home-owner", 1871);
        link("board", "home-scholar", 2086);
        link("board", "home-self", 1176);
        link("board", "home-student", 1735);
        link("board", "home-tailor", 1774);
        link("board", "home-trader", 1981);
        link("board", "shop", 765);
        link("board", "street", 1584);
        link("cafe", "garden", 759);
        link("cafe", "gym", 1080);
        link("cafe", "home-artist", 809);
        link("cafe", "home-baker", 1213);
        link("cafe", "home-barista", 1633);
        link("cafe", "home-botanist", 1321);
        link("cafe", "home-broker", 1506);
        link("cafe", "home-fixer", 884);
        link("cafe", "home-florist", 1525);
        link("cafe", "home-gardener", 617);
        link("cafe", "home-masseur", 1305);
        link("cafe", "home-messenger", 1009);
        link("cafe", "home-owner", 537);
        link("cafe", "home-scholar", 1926);
        link("cafe", "home-self", 425);
        link("cafe", "home-student", 397);
        link("cafe", "home-tailor", 1614);
        link("cafe", "home-trader", 1818);
        link("cafe", "shop", 1472);
        link("cafe", "street", 256);
        link("garden", "gym", 1696);
        link("garden", "home-artist", 1427);
        link("garden", "home-baker", 1829);
        link("garden", "home-barista", 2250);
        link("garden", "home-botanist", 1938);
        link("garden", "home-broker", 2123);
        link("garden", "home-fixer", 1500);
        link("garden", "home-florist", 2141);
        link("garden", "home-gardener", 1235);
        link("garden", "home-masseur", 1921);
        link("garden", "home-messenger", 1625);
        link("garden", "home-owner", 1253);
        link("garden", "home-scholar", 2543);
        link("garden", "home-self", 1043);
        link("garden", "home-student", 1114);
        link("garden", "home-tailor", 2231);
        link("garden", "home-trader", 2434);
        link("garden", "shop", 2088);
        link("garden", "street", 965);
        link("gym", "home-artist", 1180);
        link("gym", "home-baker", 993);
        link("gym", "home-barista", 1413);
        link("gym", "home-botanist", 1101);
        link("gym", "home-broker", 1286);
        link("gym", "home-fixer", 196);
        link("gym", "home-florist", 1304);
        link("gym", "home-gardener", 988);
        link("gym", "home-masseur", 1085);
        link("gym", "home-messenger", 789);
        link("gym", "home-owner", 1491);
        link("gym", "home-scholar", 1707);
        link("gym", "home-self", 796);
        link("gym", "home-student", 1355);
        link("gym", "home-tailor", 1396);
        link("gym", "home-trader", 1598);
        link("gym", "shop", 392);
        link("gym", "street", 1204);
        link("home-artist", "home-baker", 1313);
        link("home-artist", "home-barista", 1732);
        link("home-artist", "home-botanist", 1420);
        link("home-artist", "home-broker", 1607);
        link("home-artist", "home-fixer", 984);
        link("home-artist", "home-florist", 1625);
        link("home-artist", "home-gardener", 192);
        link("home-artist", "home-masseur", 1405);
        link("home-artist", "home-messenger", 1108);
        link("home-artist", "home-owner", 545);
        link("home-artist", "home-scholar", 2026);
        link("home-artist", "home-self", 384);
        link("home-artist", "home-student", 666);
        link("home-artist", "home-tailor", 1714);
        link("home-artist", "home-trader", 1919);
        link("home-artist", "shop", 1572);
        link("home-artist", "street", 772);
        link("home-baker", "home-barista", 936);
        link("home-baker", "home-botanist", 624);
        link("home-baker", "home-broker", 768);
        link("home-baker", "home-fixer", 797);
        link("home-baker", "home-florist", 312);
        link("home-baker", "home-gardener", 1121);
        link("home-baker", "home-masseur", 568);
        link("home-baker", "home-messenger", 312);
        link("home-baker", "home-owner", 1631);
        link("home-baker", "home-scholar", 1190);
        link("home-baker", "home-self", 929);
        link("home-baker", "home-student", 1498);
        link("home-baker", "home-tailor", 879);
        link("home-baker", "home-trader", 1079);
        link("home-baker", "shop", 1384);
        link("home-baker", "street", 1337);
        link("home-barista", "home-botanist", 312);
        link("home-barista", "home-broker", 1190);
        link("home-barista", "home-fixer", 1217);
        link("home-barista", "home-florist", 1248);
        link("home-barista", "home-gardener", 1540);
        link("home-barista", "home-masseur", 986);
        link("home-barista", "home-messenger", 624);
        link("home-barista", "home-owner", 2050);
        link("home-barista", "home-scholar", 1607);
        link("home-barista", "home-self", 1349);
        link("home-barista", "home-student", 1918);
        link("home-barista", "home-tailor", 1295);
        link("home-barista", "home-trader", 1502);
        link("home-barista", "shop", 1805);
        link("home-barista", "street", 1756);
        link("home-botanist", "home-broker", 878);
        link("home-botanist", "home-fixer", 905);
        link("home-botanist", "home-florist", 936);
        link("home-botanist", "home-gardener", 1228);
        link("home-botanist", "home-masseur", 674);
        link("home-botanist", "home-messenger", 312);
        link("home-botanist", "home-owner", 1738);
        link("home-botanist", "home-scholar", 1295);
        link("home-botanist", "home-self", 1037);
        link("home-botanist", "home-student", 1606);
        link("home-botanist", "home-tailor", 983);
        link("home-botanist", "home-trader", 1190);
        link("home-botanist", "shop", 1493);
        link("home-botanist", "street", 1444);
        link("home-broker", "home-fixer", 1090);
        link("home-broker", "home-florist", 1079);
        link("home-broker", "home-gardener", 1416);
        link("home-broker", "home-masseur", 312);
        link("home-broker", "home-messenger", 573);
        link("home-broker", "home-owner", 1926);
        link("home-broker", "home-scholar", 936);
        link("home-broker", "home-self", 1224);
        link("home-broker", "home-student", 1793);
        link("home-broker", "home-tailor", 624);
        link("home-broker", "home-trader", 312);
        link("home-broker", "shop", 1678);
        link("home-broker", "street", 1632);
        link("home-fixer", "home-florist", 1109);
        link("home-fixer", "home-gardener", 792);
        link("home-fixer", "home-masseur", 889);
        link("home-fixer", "home-messenger", 593);
        link("home-fixer", "home-owner", 1295);
        link("home-fixer", "home-scholar", 1510);
        link("home-fixer", "home-self", 600);
        link("home-fixer", "home-student", 1159);
        link("home-fixer", "home-tailor", 1198);
        link("home-fixer", "home-trader", 1402);
        link("home-fixer", "shop", 588);
        link("home-fixer", "street", 1008);
        link("home-florist", "home-gardener", 1433);
        link("home-florist", "home-masseur", 880);
        link("home-florist", "home-messenger", 624);
        link("home-florist", "home-owner", 1943);
        link("home-florist", "home-scholar", 1502);
        link("home-florist", "home-self", 1241);
        link("home-florist", "home-student", 1810);
        link("home-florist", "home-tailor", 1190);
        link("home-florist", "home-trader", 1391);
        link("home-florist", "shop", 1696);
        link("home-florist", "street", 1649);
        link("home-gardener", "home-masseur", 1213);
        link("home-gardener", "home-messenger", 917);
        link("home-gardener", "home-owner", 737);
        link("home-gardener", "home-scholar", 1834);
        link("home-gardener", "home-self", 192);
        link("home-gardener", "home-student", 858);
        link("home-gardener", "home-tailor", 1522);
        link("home-gardener", "home-trader", 1727);
        link("home-gardener", "shop", 1380);
        link("home-gardener", "street", 737);
        link("home-masseur", "home-messenger", 369);
        link("home-masseur", "home-owner", 1723);
        link("home-masseur", "home-scholar", 624);
        link("home-masseur", "home-self", 1021);
        link("home-masseur", "home-student", 1590);
        link("home-masseur", "home-tailor", 312);
        link("home-masseur", "home-trader", 624);
        link("home-masseur", "shop", 1477);
        link("home-masseur", "street", 1428);
        link("home-messenger", "home-owner", 1427);
        link("home-messenger", "home-scholar", 990);
        link("home-messenger", "home-self", 725);
        link("home-messenger", "home-student", 1294);
        link("home-messenger", "home-tailor", 678);
        link("home-messenger", "home-trader", 885);
        link("home-messenger", "shop", 1181);
        link("home-messenger", "street", 1132);
        link("home-owner", "home-scholar", 2344);
        link("home-owner", "home-self", 840);
        link("home-owner", "home-student", 160);
        link("home-owner", "home-tailor", 2032);
        link("home-owner", "home-trader", 2237);
        link("home-owner", "shop", 1883);
        link("home-owner", "street", 288);
        link("home-scholar", "home-self", 1642);
        link("home-scholar", "home-student", 2211);
        link("home-scholar", "home-tailor", 312);
        link("home-scholar", "home-trader", 1248);
        link("home-scholar", "shop", 2099);
        link("home-scholar", "street", 2049);
        link("home-self", "home-student", 708);
        link("home-self", "home-tailor", 1330);
        link("home-self", "home-trader", 1536);
        link("home-self", "shop", 1188);
        link("home-self", "street", 545);
        link("home-student", "home-tailor", 1899);
        link("home-student", "home-trader", 2105);
        link("home-student", "shop", 1747);
        link("home-student", "street", 152);
        link("home-tailor", "home-trader", 936);
        link("home-tailor", "shop", 1787);
        link("home-tailor", "street", 1737);
        link("home-trader", "shop", 1989);
        link("home-trader", "street", 1943);
        link("shop", "street", 1596);
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
