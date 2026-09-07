package com.betterself.growth.town;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 一个 NPC 一天去哪儿、在做什么——纯函数，只由 (npc_code, 日期) 决定。
 *
 * <p>刻意不落库：日程是可以重算的，存下来只会多一张需要和口径保持一致的表。HTTP 接口用它撑
 * 向后兼容的 {@code schedule} 字段（七个整点时段），也用来生成"NPC 今天在哪儿"这类事实文本
 * 与目击判定（{@link TownSocietyService#sawPlayer}）。
 *
 * <p>M7 之后，相遇序列改由更细粒度的 {@link TownDayPlan}（分钟级 errands/legs）推出，不再从这
 * 里的整点时段推——两种粒度都要能查"某一刻在哪"，但相遇判定只信一份，就是 {@link TownDayPlan}。
 *
 * <p>同一个 NPC 在同一天永远得到同一份日程（种子来自 npc_code 与日期），但换一天就会变；这样
 * 小镇既有稳定的作息，又不会天天完全一样。
 */
final class TownNpcSchedules {

    /** 一天切成 7 段，边界固定；地点和活动才是变的那部分。段与段之间不留空洞。 */
    private static final int[] BOUNDARIES = {0, 6, 9, 12, 14, 18, 21, 24};

    static final String HOME = "home";
    static final String ACADEMY = "academy";
    static final String GYM = "gym";
    static final String CAFE = "cafe";
    static final String PARK = "park";
    static final String PLAZA = "plaza";
    static final String STREET = "street";

    private static final List<String> DAY_PLACES = List.of(ACADEMY, GYM, CAFE, PARK, PLAZA, STREET);

    /** 深夜留在外面的比例。18 个人乘这个比例，正好落在护栏 B 说的"深夜 2~3 人"上。 */
    private static final double NIGHT_OWL_SHARE = 0.15;

    /**
     * 五维度各自最"像"的去处；NPC 的兴趣权重会把他往这些地方推。包可见（而非 private）：
     * {@link TownNpcRhythm} 推默认节律、{@link TownDayPlan} 排当天行程都要用同一份映射，
     * 不能各写一份——那就是"同一概念两套常数"。
     */
    static final Map<String, String> DIMENSION_PLACE = Map.of(
        "KNOWLEDGE", ACADEMY,
        "HEALTH", GYM,
        "CAREER", PLAZA,
        "RELATIONSHIP", CAFE,
        "WELLBEING", PARK
    );

    private TownNpcSchedules() {
    }

    record Slot(int startHour, int endHour, String place, String activity) {
    }

    /**
     * 生成覆盖 0~24 点、无空洞、按 startHour 升序的一天日程。
     *
     * @param interests 五维度权重，缺失按 0 处理
     */
    static List<Slot> forNpc(String npcCode, int layer, Map<String, Double> interests, LocalDate date) {
        RandomGenerator rng = seededRng(npcCode, date);
        Map<String, Double> weights = placeWeights(interests);
        List<Slot> slots = new ArrayList<>(BOUNDARIES.length - 1);
        for (int i = 0; i < BOUNDARIES.length - 1; i++) {
            int start = BOUNDARIES[i];
            int end = BOUNDARIES[i + 1];
            String place = placeFor(start, weights, rng);
            slots.add(new Slot(start, end, place, activityFor(place, layer, start, rng)));
        }
        return List.copyOf(slots);
    }

    /** 某一刻该把这个 NPC 画在哪个地点；hour 落在 [0,24) 之外时钳回来。 */
    static Slot slotAt(List<Slot> schedule, int hour) {
        int normalized = Math.floorMod(hour, 24);
        for (Slot slot : schedule) {
            if (normalized >= slot.startHour() && normalized < slot.endHour()) {
                return slot;
            }
        }
        return null;
    }

    private static String placeFor(int startHour, Map<String, Double> weights, RandomGenerator rng) {
        // 深夜绝大多数人在家，但不能一个不剩——plan §2.4 护栏 B 要的是"深夜 2~3 人"，
        // 全员回家会让凌晨的小镇变成一条空街（前端会把在家的人整个滤掉）。留一小撮夜猫子
        // 在外面：谁是夜猫子由种子决定，所以同一个人不会今晚在外面、明晚又在外面地乱跳。
        if (startHour < 6 || startHour >= 21) {
            return rng.nextDouble() < NIGHT_OWL_SHARE ? nightSpot(rng) : HOME;
        }
        // 午间是咖啡馆高峰，给它一次额外的抽签机会。
        if (startHour == 12 && rng.nextDouble() < 0.45) {
            return CAFE;
        }
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double roll = rng.nextDouble() * total;
        for (String place : DAY_PLACES) {
            roll -= weights.getOrDefault(place, 0.0);
            if (roll <= 0) {
                return place;
            }
        }
        return STREET;
    }

    /** 深夜还在外面的人只会在这几处：街上、广场、还开着的咖啡馆。 */
    private static String nightSpot(RandomGenerator rng) {
        double roll = rng.nextDouble();
        if (roll < 0.45) {
            return STREET;
        }
        return roll < 0.8 ? PLAZA : CAFE;
    }

    /** 包可见：{@link TownDayPlan} 排当天行程时复用同一份地点→活动映射，不重开一套常数。 */
    static String activityFor(String place, int layer, int startHour, RandomGenerator rng) {
        if (HOME.equals(place)) {
            return "idle";
        }
        return switch (place) {
            case ACADEMY -> "reading";
            case CAFE -> rng.nextDouble() < 0.3 ? "phone" : "sit";
            case GYM -> "idle";
            case PLAZA -> rng.nextDouble() < 0.5 ? "walking" : "phone";
            case STREET -> "walking";
            // 公园是唯一会出现劳作动画的地方——三层背景居民干活，一二层不干，
            // 免得"你熟悉的面孔"整天在浇水（M2-3）。
            case PARK -> layer == 3 ? labour(startHour, rng) : (rng.nextDouble() < 0.5 ? "sit" : "idle");
            default -> "idle";
        };
    }

    static String labour(int startHour, RandomGenerator rng) {
        List<String> pool = startHour >= 14
            ? List.of("fishing", "watering", "harvesting")
            : List.of("watering", "chopping", "digging");
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 把五维度兴趣摊到具体地点上，再给每个地点一个保底权重，免得有人一整天不出门。 */
    private static Map<String, Double> placeWeights(Map<String, Double> interests) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String place : DAY_PLACES) {
            weights.put(place, 0.12);
        }
        if (interests != null) {
            interests.forEach((dimension, weight) -> {
                String place = DIMENSION_PLACE.get(dimension);
                if (place != null && weight != null && weight > 0) {
                    weights.merge(place, weight, Double::sum);
                }
            });
        }
        return weights;
    }

    private static RandomGenerator seededRng(String npcCode, LocalDate date) {
        long seed = 1125899906842597L;
        for (char c : npcCode.toCharArray()) {
            seed = 31 * seed + c;
        }
        seed = 31 * seed + date.toEpochDay();
        return new java.util.SplittableRandom(seed);
    }
}
