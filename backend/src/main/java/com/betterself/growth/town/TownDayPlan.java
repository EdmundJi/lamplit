package com.betterself.growth.town;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * plan §2.9 / §3.5 / CONTRACT-M7.md §1-2：把一个 NPC 的{@link TownNpcRhythm 节律}变成"今天具体
 * 怎么过"——一串要办的事（errands）加上事情之间的通行（legs），以及"这一刻他在哪儿"的纯函数
 * {@link #positionAt}。
 *
 * <p><b>纯函数，输入只有 (npc_code, rhythm, 当日上下文, date)</b>——不读数据库、不看时钟。
 * 夜间 job（{@link TownSocietyService#runNightly}）用它推相遇序列，HTTP 接口用它下发
 * {@code dayPlan} 给前端渲染，前端按同一份契约各自实现一遍——两边必须算出同一份，所以这里的每
 * 一步都不能引入除入参之外的任何随机性或外部状态。
 *
 * <p>当天偏离（D19/D20）由 {@code seed(npc_code, date)} 决定，输入取自心情、天气、亲密度、
 * {@code town_event}：
 * <ul>
 *   <li>心情低落 → 减少外出（{@link #applyMoodDeviation}）；</li>
 *   <li>下雨 → 户外行程缩短（{@link #applyWeather}）；</li>
 *   <li>对玩家亲密度高 → 有概率顺路加一件小事（{@link #applyAffinityDeviation}）；</li>
 *   <li>{@code town_event}（M4 的表）→ 必定插入一条 priority=2 的行程（{@link #applyEvents}）。
 *       那张表由并行开发接 V23，这里只接收调用方传入的 {@link DayPlanContext#events()}——
 *       生产路径目前传空集合，TODO：M4 落地后把 town_event 查询接进来。</li>
 * </ul>
 */
final class TownDayPlan {

    static final int MINUTES_PER_DAY = 1440;

    /** 所有地点之间统一按 15 分钟通勤算——MVP 简化，日程里的地点本来就没有真实的地理距离。 */
    private static final int COMMUTE_MINUTES = 15;

    /** 心情阈值：低于此值开始减少外出，更低则减得更多（plan §3.5「输入取自心情」）。 */
    private static final double MOOD_LOW = -0.2;
    private static final double MOOD_VERY_LOW = -0.6;

    /** 雨天户外行程按此比例缩短，"缩短"而不是"取消"——雨停了照样得回去。 */
    private static final double RAIN_DURATION_SCALE = 0.6;
    private static final Set<String> OUTDOOR_PLACES =
        Set.of(TownNpcSchedules.PARK, TownNpcSchedules.PLAZA, TownNpcSchedules.STREET);

    /** 对玩家亲密度够高时，有一定概率顺路多做一件不打紧的小事（priority=0）。 */
    private static final double AFFINITY_EXTRA_THRESHOLD = 0.5;
    private static final double AFFINITY_EXTRA_PROBABILITY = 0.3;
    private static final int AFFINITY_EXTRA_MIN_DURATION = 30;
    private static final int AFFINITY_EXTRA_DURATION_SPAN = 30;
    private static final int AFFINITY_EXTRA_ATTEMPTS = 20;

    /** encounters() 按这个步长抽样两条时间线；15 分钟的通勤腿至少能撞上 2~3 个采样点。 */
    private static final int ENCOUNTER_SAMPLE_STEP_MINUTES = 5;

    private TownDayPlan() {
    }

    /** 一件事：去哪儿、做什么、什么时候到什么时候走、有多要紧、从哪层逻辑加进来的。 */
    record Errand(String place, String activity, int startMinute, int endMinute, int priority, String origin) {
    }

    /** 两件事之间的通行，由 {@link #generate} 从相邻 errand 推导，不单独存。 */
    record Leg(String fromPlace, String toPlace, int departMinute, int arriveMinute) {
    }

    /** 一个 NPC 一天的行程：errands ∪ legs 无空洞覆盖 [0,1440)。 */
    record DayPlan(LocalDate date, List<Errand> errands, List<Leg> legs) {
    }

    /** M4 的活动/请柬会插入的一条"要紧"行程；由 town_event 投影而来。 */
    record EventSlot(String place, String activity, int startMinute, int durationMinutes) {
    }

    /** 生成当天行程要看的上下文——全部是已经算好的值，这个类不负责去查它们从哪儿来。 */
    record DayPlanContext(double moodValence, boolean rainy, double affinityToPlayer, List<EventSlot> events) {
        DayPlanContext {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /** {@link #positionAt} 的返回值：要么钉在一个地点，要么在两个地点之间的路上。 */
    record Position(String kind, String place, String fromPlace, String toPlace, double progress, String activity) {
        static Position at(String place, String activity) {
            return new Position("AT", place, null, null, 0.0, activity);
        }

        static Position walking(String fromPlace, String toPlace, double progress) {
            return new Position("WALKING", null, fromPlace, toPlace, progress, "walking");
        }
    }

    /**
     * CONTRACT-M7.md §1：由节律叠加当天偏离，产出覆盖全天、无空洞、按 (npc_code, date) 确定性
     * 生成的一份行程。
     */
    static DayPlan generate(String npcCode, int layer, TownNpcRhythm.Rhythm rhythm, DayPlanContext context,
                            LocalDate date) {
        List<Draft> drafts = new ArrayList<>();
        for (TownNpcRhythm.Errand errand : rhythm.errands()) {
            drafts.add(new Draft(errand.place(), errand.startMinute(), errand.durationMinutes(), 1, "RHYTHM", null));
        }

        applyWeather(drafts, context.rainy());
        applyMoodDeviation(drafts, context.moodValence());
        applyEvents(drafts, context.events());
        applyAffinityDeviation(drafts, rhythm, context.affinityToPlayer(),
            rngFor(npcCode, date, "affinity-deviation"));

        drafts.sort((a, b) -> Integer.compare(a.start, b.start));
        return assemble(date, drafts, layer, rngFor(npcCode, date, "activity"));
    }

    /**
     * CONTRACT-M7.md §2：给定行程与一天中的某一刻，答"这一刻他在哪儿"。`minuteOfDay` 用
     * {@code floorMod} 归一，前后端两份实现算出来的必须是同一个答案。
     */
    static Position positionAt(DayPlan plan, int minuteOfDay) {
        int minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY);
        for (Errand errand : plan.errands()) {
            if (minute >= errand.startMinute() && minute < errand.endMinute()) {
                return Position.at(errand.place(), errand.activity());
            }
        }
        for (Leg leg : plan.legs()) {
            if (minute >= leg.departMinute() && minute < leg.arriveMinute()) {
                int span = leg.arriveMinute() - leg.departMinute();
                double progress = span > 0 ? clamp01((minute - leg.departMinute()) / (double) span) : 1.0;
                return Position.walking(leg.fromPlace(), leg.toPlace(), progress);
            }
        }
        // errands ∪ legs 本该无缝覆盖全天；万一某份行程有洞（例如手搭的测试夹具），宁可假装
        // 在家，也不能让相遇判定或渲染因为一个 null 而炸掉。
        return Position.at(TownNpcSchedules.HOME, "idle");
    }

    /**
     * plan §3.5「相遇判定的改造」/ CONTRACT-M7.md §3：把每两个 NPC 的当天行程按
     * {@link #ENCOUNTER_SAMPLE_STEP_MINUTES} 抽样比对，分出同处停留 / 路上相遇 / 擦肩三类。
     * 这是 {@link #positionAt} 在生产路径上的真正调用者——夜间 job 用这里产出的相遇序列喂给
     * {@link TownSocialSim#simulate}。
     *
     * <p>同一对 (a,b) 只在"从没遇上"切到"遇上"的那一刻记一条，避免同一段相遇被抽样切成几十条
     * 几乎相同的记录；换成同一天重跑，抽样点固定、行程固定，输出逐字节一样。
     */
    static List<TownSocialSim.Encounter> encounters(Map<String, DayPlan> plansByNpc) {
        List<String> codes = new ArrayList<>(plansByNpc.keySet());
        codes.sort(String::compareTo);
        List<TownSocialSim.Encounter> encounters = new ArrayList<>();

        for (int i = 0; i < codes.size(); i++) {
            for (int j = i + 1; j < codes.size(); j++) {
                String a = codes.get(i);
                String b = codes.get(j);
                DayPlan planA = plansByNpc.get(a);
                DayPlan planB = plansByNpc.get(b);
                TownSocialSim.EncounterType active = null;
                for (int minute = 0; minute < MINUTES_PER_DAY; minute += ENCOUNTER_SAMPLE_STEP_MINUTES) {
                    Classification classification = classify(positionAt(planA, minute), positionAt(planB, minute));
                    TownSocialSim.EncounterType type = classification == null ? null : classification.type();
                    if (type != null && type != active) {
                        encounters.add(new TownSocialSim.Encounter(a, b, classification.place(), minute, type));
                    }
                    active = type;
                }
            }
        }
        return List.copyOf(encounters);
    }

    private record Classification(String place, TownSocialSim.EncounterType type) {
    }

    /** 在家永远不算相遇（plan D21 / CONTRACT-M7.md §3），三个分支都要挡这一条。 */
    private static Classification classify(Position a, Position b) {
        boolean aWalking = "WALKING".equals(a.kind());
        boolean bWalking = "WALKING".equals(b.kind());

        if (!aWalking && !bWalking) {
            if (a.place().equals(b.place()) && !TownNpcSchedules.HOME.equals(a.place())) {
                return new Classification(a.place(), TownSocialSim.EncounterType.CO_LOCATED);
            }
            return null;
        }
        if (aWalking && bWalking) {
            // 两人都在路上：只有当他们的路线共享一个端点（同一起点或同一终点）才算"同一条路
            // 上"，否则是镇上两条毫不相干的路各走各的，不该算相遇。
            boolean sameRoute = a.fromPlace().equals(b.fromPlace()) || a.fromPlace().equals(b.toPlace())
                || a.toPlace().equals(b.fromPlace()) || a.toPlace().equals(b.toPlace());
            return sameRoute ? new Classification(TownNpcSchedules.STREET, TownSocialSim.EncounterType.EN_ROUTE)
                : null;
        }
        Position atPos = aWalking ? b : a;
        Position walkPos = aWalking ? a : b;
        boolean passesThrough = atPos.place().equals(walkPos.fromPlace()) || atPos.place().equals(walkPos.toPlace());
        if (passesThrough && !TownNpcSchedules.HOME.equals(atPos.place())) {
            return new Classification(atPos.place(), TownSocialSim.EncounterType.PASSING);
        }
        return null;
    }

    // ---------------------------------------------------------------- 当天偏离

    /** 下雨天缩短户外停留——不取消，只是待得没那么久。 */
    private static void applyWeather(List<Draft> drafts, boolean rainy) {
        if (!rainy) {
            return;
        }
        for (Draft draft : drafts) {
            if (OUTDOOR_PLACES.contains(draft.place)) {
                draft.duration = Math.max(20, (int) Math.round(draft.duration * RAIN_DURATION_SCALE));
            }
        }
    }

    /**
     * 心情低落 → 外出行程减少。按时长从短到长挑着删，值越低删得越多；这一步完全由 valence 本身
     * 决定，不额外掷骰子——同一个 valence 永远删同一批，"偏离可复现"不需要靠种子撑，靠输入本身
     * 就是确定的。
     */
    private static void applyMoodDeviation(List<Draft> drafts, double valence) {
        int removeCount;
        if (valence >= MOOD_LOW) {
            removeCount = 0;
        } else if (valence >= MOOD_VERY_LOW) {
            removeCount = 1;
        } else {
            removeCount = 2;
        }
        if (removeCount <= 0 || drafts.isEmpty()) {
            return;
        }
        List<Draft> byDuration = new ArrayList<>(drafts);
        byDuration.sort((a, b) -> Integer.compare(a.duration, b.duration));
        for (int i = 0; i < removeCount && i < byDuration.size(); i++) {
            drafts.remove(byDuration.get(i));
        }
    }

    /**
     * town_event 插入的行程"要紧"（priority=2），必定出现：先清掉和它撞车的其它安排，
     * 再把它加进去，而不是让它去和别的行程抢时间段。
     */
    private static void applyEvents(List<Draft> drafts, List<EventSlot> events) {
        for (EventSlot event : events) {
            int start = event.startMinute();
            int end = start + event.durationMinutes();
            drafts.removeIf(draft -> overlaps(draft.start, draft.end(), start, end));
            drafts.add(new Draft(event.place(), start, event.durationMinutes(), 2, "EVENT", event.activity()));
        }
    }

    /**
     * 对玩家亲密度够高时，有概率顺路加一件不打紧的小事（plan §3.5「关系高就更可能顺路过
     * 去」）。找不到不冲突的空档就放弃，不强行插进已经排满的一天。
     */
    private static void applyAffinityDeviation(List<Draft> drafts, TownNpcRhythm.Rhythm rhythm,
                                               double affinityToPlayer, RandomGenerator rng) {
        if (affinityToPlayer < AFFINITY_EXTRA_THRESHOLD || rng.nextDouble() >= AFFINITY_EXTRA_PROBABILITY) {
            return;
        }
        String place = rng.nextBoolean() ? TownNpcSchedules.CAFE : TownNpcSchedules.PLAZA;
        int duration = AFFINITY_EXTRA_MIN_DURATION + rng.nextInt(AFFINITY_EXTRA_DURATION_SPAN + 1);
        int start = findFreeSlot(drafts, rhythm.wakeMinute(), rhythm.sleepMinute(), duration, rng);
        if (start >= 0) {
            drafts.add(new Draft(place, start, duration, 0, "DEVIATION", null));
        }
    }

    private static int findFreeSlot(List<Draft> drafts, int wake, int sleep, int duration, RandomGenerator rng) {
        int room = sleep - wake - duration;
        if (room <= 0) {
            return -1;
        }
        for (int attempt = 0; attempt < AFFINITY_EXTRA_ATTEMPTS; attempt++) {
            int start = wake + rng.nextInt(room + 1);
            int end = start + duration;
            boolean clash = drafts.stream()
                .anyMatch(draft -> overlaps(start - COMMUTE_MINUTES, end + COMMUTE_MINUTES, draft.start, draft.end()));
            if (!clash) {
                return start;
            }
        }
        return -1;
    }

    private static boolean overlaps(int startA, int endA, int startB, int endB) {
        return startA < endB && startB < endA;
    }

    // ---------------------------------------------------------------- 组装

    /**
     * 把外出草案铺回全天：每件事之前从家出发、办完立刻回家，事情之间空出来的时间待在家里，
     * 而不是留在刚才那个地方等下一件事——回家是默认状态，这样"外出时间"就是行程本身的时间，
     * 心情低落删掉几件事就实打实地减少了外出，不会因为"少了一件事、中间那段无所事事的时间
     * 反而更长"而抵消掉。这也让循环不用再跟踪"人现在在哪"：每次进入循环体之前，人总是在家。
     */
    private static DayPlan assemble(LocalDate date, List<Draft> away, int layer, RandomGenerator activityRng) {
        List<Errand> errands = new ArrayList<>();
        List<Leg> legs = new ArrayList<>();
        int cursor = 0;

        for (Draft draft : away) {
            int departMinute = Math.max(cursor, draft.start - COMMUTE_MINUTES);
            int arriveMinute = Math.max(departMinute, draft.start);
            if (departMinute > cursor) {
                errands.add(new Errand(TownNpcSchedules.HOME, "idle", cursor, departMinute, 1, "RHYTHM"));
            }
            if (arriveMinute > departMinute) {
                legs.add(new Leg(TownNpcSchedules.HOME, draft.place, departMinute, arriveMinute));
            }
            int end = Math.min(MINUTES_PER_DAY, arriveMinute + draft.duration);
            if (end <= arriveMinute) {
                continue;
            }
            String activity = draft.activity != null ? draft.activity
                : TownNpcSchedules.activityFor(draft.place, layer, arriveMinute / 60, activityRng);
            errands.add(new Errand(draft.place, activity, arriveMinute, end, draft.priority, draft.origin));
            cursor = end;

            if (cursor < MINUTES_PER_DAY) {
                int homeDepart = cursor;
                int homeArrive = Math.min(MINUTES_PER_DAY, cursor + COMMUTE_MINUTES);
                if (homeArrive > homeDepart) {
                    legs.add(new Leg(draft.place, TownNpcSchedules.HOME, homeDepart, homeArrive));
                }
                cursor = homeArrive;
            }
        }

        if (cursor < MINUTES_PER_DAY) {
            errands.add(new Errand(TownNpcSchedules.HOME, "idle", cursor, MINUTES_PER_DAY, 1, "RHYTHM"));
        }

        return new DayPlan(date, List.copyOf(errands), List.copyOf(legs));
    }

    /** 可变的行程草案——deviation 阶段要能就地增删改，落定后才转成不可变的 {@link Errand}。 */
    private static final class Draft {
        final String place;
        int start;
        int duration;
        final int priority;
        final String origin;
        final String activity;

        Draft(String place, int start, int duration, int priority, String origin, String activity) {
            this.place = place;
            this.start = start;
            this.duration = duration;
            this.priority = priority;
            this.origin = origin;
            this.activity = activity;
        }

        int end() {
            return start + duration;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 与 {@link TownNpcSchedules} 同样的散列打法，但加了 salt，避免和节律的随机流撞在一起。 */
    private static RandomGenerator rngFor(String npcCode, LocalDate date, String salt) {
        long seed = 1125899906842597L;
        for (char c : npcCode.toCharArray()) {
            seed = 31 * seed + c;
        }
        for (char c : salt.toCharArray()) {
            seed = 31 * seed + c;
        }
        seed = 31 * seed + date.toEpochDay();
        return new SplittableRandom(seed);
    }
}
