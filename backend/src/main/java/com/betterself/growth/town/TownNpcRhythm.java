package com.betterself.growth.town;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个 NPC "平常的一天"——plan §2.9 D19 的节律层。纯函数，只由这个 NPC 固定不变的档案（五维度
 * 兴趣、分享欲、好奇心、npc_code 本身）决定，**不接受日期或种子**：节律是"这个人是谁"，会变的
 * 是 {@link TownDayPlan} 叠加的当天偏离，不是节律本身。
 *
 * <p>落库一次（{@link TownNpcProvisioner} 建号时算好写进 {@code town_npc.rhythm}），之后 HTTP
 * 与夜间 job 都从库里读同一份，而不是各自重算——这是"档案"和"日程"的分野：档案稳定，日程按天
 * 变化。
 */
final class TownNpcRhythm {

    /** 起床最早 5:00，最晚 8:00；好奇心越强，越早醒——好奇心驱动这个人主动去过日子。 */
    private static final int WAKE_FLOOR = 300;
    private static final int WAKE_CEILING = 480;
    private static final int WAKE_BASE = 420;
    private static final int WAKE_CURIOSITY_SWING = 120;

    /** 最早 21:00 收工回家，最晚 23:30；分享欲越强，在外待得越晚——社交型的人舍不得早回家。 */
    private static final int SLEEP_FLOOR = 1260;
    private static final int SLEEP_CEILING = 1410;
    private static final int SLEEP_BASE = 1320;
    private static final int SLEEP_SHARE_DRIVE_SWING = 90;

    /** 常态外出 2~4 件事；分享欲越强，愿意排的事越多。 */
    private static final int ERRAND_COUNT_MIN = 2;
    private static final int ERRAND_COUNT_MAX = 4;

    /** 单次外出最短 45 分钟、最长 150 分钟；具体时长由该地点对应维度的兴趣权重决定。 */
    private static final int DURATION_MIN = 45;
    private static final int DURATION_MAX = 150;
    private static final int DURATION_SCALE = 300;

    private TownNpcRhythm() {
    }

    /** 一件常态要办的事：地点、什么时候开始、打算待多久。活动与优先级留给 {@link TownDayPlan} 决定。 */
    record Errand(String place, int startMinute, int durationMinutes) {
        int endMinute() {
            return startMinute + durationMinutes;
        }
    }

    /** 一个 NPC 的常态作息：几点起、几点睡、平常会去的那几件事。 */
    record Rhythm(int wakeMinute, int sleepMinute, List<Errand> errands) {
    }

    /**
     * 由五维度兴趣与性格（分享欲 × 好奇心）推出这个 NPC 的默认节律。
     *
     * <p>兴趣权重相同（例如小助/纪麦五维度打平）时，仅靠权重排不出先后——这里用 {@code npcCode}
     * 的哈希去旋转维度的比较顺序作为稳定的平局判定，而不是引入一个真正的随机数：同一个人的
     * 节律永远长一个样，但"打平怎么排"这件事因人而异，所以 18 个人不会因为兴趣打平就撞成
     * 同一份作息。
     */
    static Rhythm defaultFor(String npcCode, Map<String, Double> interests, double shareDrive, double curiosity) {
        int wake = clamp(WAKE_BASE - (int) Math.round(curiosity * WAKE_CURIOSITY_SWING), WAKE_FLOOR, WAKE_CEILING);
        int sleep = clamp(SLEEP_BASE + (int) Math.round(shareDrive * SLEEP_SHARE_DRIVE_SWING),
            SLEEP_FLOOR, SLEEP_CEILING);

        List<String> places = rankedPlaces(npcCode, interests);
        int errandCount = clamp(ERRAND_COUNT_MIN + (int) Math.round(shareDrive * 2),
            ERRAND_COUNT_MIN, Math.min(ERRAND_COUNT_MAX, places.size()));

        int window = sleep - wake;
        int slot = window / (errandCount + 1);
        List<Errand> errands = new ArrayList<>(errandCount);
        for (int i = 0; i < errandCount; i++) {
            String place = places.get(i);
            double weight = interests.getOrDefault(dimensionFor(place), 0.2);
            // 时长不能顶到下一个 slot——两头都要给通勤和上一/下一件事留够空间，
            // 否则 TownDayPlan 排 legs 时会被迫互相挤占。
            int duration = clamp((int) Math.round(weight * DURATION_SCALE), DURATION_MIN,
                Math.min(DURATION_MAX, Math.max(DURATION_MIN, slot - 30)));
            int start = clamp(wake + slot * (i + 1) - duration / 2, wake, sleep - duration);
            errands.add(new Errand(place, start, duration));
        }
        errands.sort((a, b) -> Integer.compare(a.startMinute(), b.startMinute()));
        return new Rhythm(wake, sleep, List.copyOf(errands));
    }

    /**
     * 按兴趣权重从高到低排出这个人常去的地点，打平时按 {@code npcCode} 的哈希旋转
     * {@link TownNpcCatalog#DIMENSIONS} 的比较顺序（见类注释）。
     */
    private static List<String> rankedPlaces(String npcCode, Map<String, Double> interests) {
        List<String> dimensions = new ArrayList<>(TownNpcCatalog.DIMENSIONS);
        int offset = Math.floorMod(npcCode.hashCode(), dimensions.size());
        List<String> rotated = new ArrayList<>(dimensions.size());
        for (int i = 0; i < dimensions.size(); i++) {
            rotated.add(dimensions.get((i + offset) % dimensions.size()));
        }
        // Java 的 List.sort 是稳定排序：权重相等的两个维度保持在 rotated 里的相对顺序不变。
        rotated.sort((a, b) -> Double.compare(
            interests.getOrDefault(b, 0.0), interests.getOrDefault(a, 0.0)));

        List<String> places = new ArrayList<>();
        for (String dimension : rotated) {
            String place = TownNpcSchedules.DIMENSION_PLACE.get(dimension);
            if (place != null && !places.contains(place)) {
                places.add(place);
            }
        }
        return places;
    }

    private static String dimensionFor(String place) {
        for (Map.Entry<String, String> entry : TownNpcSchedules.DIMENSION_PLACE.entrySet()) {
            if (entry.getValue().equals(place)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
