package com.betterself.growth.town;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Each NPC's day is generated, not stored: the same (date, time-of-day segment, npc code)
 * always yields the same mood, whereabouts and small event, so every resident who talks to
 * that NPC in the same stretch of the day hears a consistent character without a table to
 * keep in sync. The segment is what keeps it from being one flat, repeatable line for the
 * whole day — talk to the same NPC in the morning and again at night and the small event has
 * actually moved on, the way a real day would. Busy windows are a fixed daily routine per
 * character, not part of the random pick.
 */
record TownNpcDailyState(String mood, String whereabouts, String situation) {

    enum Segment { EARLY_MORNING, MORNING, AFTERNOON, EVENING, NIGHT }

    private static final List<String> GUIDE_WHERE = List.of("学院门口", "自习室窗边", "公告栏旁边");
    private static final List<String> POSTMAN_WHERE = List.of("街角邮筒边", "广场喷泉旁", "镇口的老槐树下");

    private static final List<String> GUIDE_MOOD_DAY = List.of("精神不错", "心情平静", "略带兴奋");
    private static final List<String> GUIDE_MOOD_NIGHT = List.of("有点犯困", "心情平静", "略显疲惫");
    private static final List<String> POSTMAN_MOOD_DAY = List.of("脚程轻快", "心情不错", "有点赶时间");
    private static final List<String> POSTMAN_MOOD_NIGHT = List.of("略显疲惫", "脚步放慢了些", "心情平静");

    private static final Map<Segment, List<String>> GUIDE_EVENTS = Map.of(
        Segment.EARLY_MORNING, List.of(
            "天刚亮就来开院门了，走廊灯还没全开",
            "很早就到了，桌上还摆着昨晚没归位的书"
        ),
        Segment.MORNING, List.of(
            "刚把自习室的书重新码了一遍，摞得整整齐齐",
            "刚把公告栏上过期的通知撕了下来",
            "帮一个刚来的新人指了路，多聊了两句"
        ),
        Segment.AFTERNOON, List.of(
            "在门口晒了会儿太阳，脑子清醒了不少",
            "刚吃过午饭，回来继续在门口待着",
            "把窗台那盆多肉挪到了向阳的地方"
        ),
        Segment.EVENING, List.of(
            "傍晚人多起来，一直在招呼进出的人",
            "整理了一下今天收到的几张留言条",
            "跟路过的熟人多聊了两句"
        ),
        Segment.NIGHT, List.of(
            "准备锁自习室的门，最后又检查了一圈",
            "夜里安静下来，坐在门口发了会儿呆",
            "把桌上的灯挪得亮堂了一点"
        )
    );

    private static final Map<Segment, List<String>> POSTMAN_EVENTS = Map.of(
        Segment.EARLY_MORNING, List.of(
            "天没亮就出门分拣今天的信了",
            "刚从邮局取了今天的第一批信"
        ),
        Segment.MORNING, List.of(
            "今天有三封信送不出去，收件人都不在家",
            "邮包比平时沉了一点，可能是季度信件多"
        ),
        Segment.AFTERNOON, List.of(
            "刚从镇口绕回来，帽子被风吹掉了一次",
            "路过广场时，长椅被几只鸽子占了",
            "今天的路线临时改了道，绕了点远路"
        ),
        Segment.EVENING, List.of(
            "傍晚这一趟收了好几张回执单",
            "天快黑了，还剩最后两户没送到"
        ),
        Segment.NIGHT, List.of(
            "最后一趟投递刚跑完，脚有点酸",
            "夜里路灯下清点了一遍还没送出的信"
        )
    );

    static Segment segmentFor(LocalTime time) {
        if (time.isBefore(LocalTime.of(7, 0))) {
            return Segment.EARLY_MORNING;
        }
        if (time.isBefore(LocalTime.of(11, 0))) {
            return Segment.MORNING;
        }
        if (time.isBefore(LocalTime.of(15, 0))) {
            return Segment.AFTERNOON;
        }
        if (time.isBefore(LocalTime.of(19, 0))) {
            return Segment.EVENING;
        }
        return Segment.NIGHT;
    }

    static TownNpcDailyState forMoment(LocalDate date, LocalTime time, String npc) {
        Segment segment = segmentFor(time);
        Random random = new Random(date.toEpochDay() * 1_000_003L + npc.hashCode() * 31L + segment.ordinal());
        boolean guide = TownPersonas.GUIDE.equals(npc);
        boolean night = segment == Segment.NIGHT || segment == Segment.EARLY_MORNING;
        List<String> moods = guide ? (night ? GUIDE_MOOD_NIGHT : GUIDE_MOOD_DAY) : (night ? POSTMAN_MOOD_NIGHT : POSTMAN_MOOD_DAY);
        List<String> wheres = guide ? GUIDE_WHERE : POSTMAN_WHERE;
        List<String> events = (guide ? GUIDE_EVENTS : POSTMAN_EVENTS).get(segment);
        return new TownNpcDailyState(pick(random, moods), pick(random, wheres), pick(random, events));
    }

    /** Whether this NPC is in the middle of its own routine and would rather keep things short. */
    static boolean busyNow(String npc, LocalTime time) {
        if (TownPersonas.GUIDE.equals(npc)) {
            return !time.isBefore(LocalTime.of(12, 0)) && time.isBefore(LocalTime.of(13, 0));
        }
        return !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(11, 0));
    }

    private static String pick(Random random, List<String> options) {
        return options.get(random.nextInt(options.size()));
    }
}
