package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-2/M7-3/M7-4 的验收：一天覆盖满无空洞、同 (npc,date) 可复现、换一天会变；偏离可复现且受
 * 心情/活动驱动；相遇能分出三类，在家仍然不算相遇。
 */
class TownDayPlanTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    private static Map<String, Double> interests(String dominant) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String dimension : List.of("KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING")) {
            weights.put(dimension, dimension.equals(dominant) ? 0.6 : 0.1);
        }
        return weights;
    }

    private static TownNpcRhythm.Rhythm rhythmFor(String npcCode, String dominant, double shareDrive,
                                                   double curiosity) {
        return TownNpcRhythm.defaultFor(npcCode, interests(dominant), shareDrive, curiosity);
    }

    private static TownDayPlan.DayPlanContext neutralContext() {
        return new TownDayPlan.DayPlanContext(0.0, false, 0.0, List.of());
    }

    // ---------------------------------------------------------------- M7-2 generate()

    @Test
    void compactTownCommutesAreVisibleAndOneMinuteCrossingsAreSampled() {
        var date=java.time.LocalDate.of(2026,9,6);
        var rhythm=TownNpcRhythm.defaultFor("KE_YUN",java.util.Map.of("KNOWLEDGE",1.0),0.5,0.5);
        var context=new TownDayPlan.DayPlanContext(0,false,0.15,java.util.List.of());
        var plan=TownDayPlan.generate("KE_YUN",2,rhythm,context,date);
        assertThat(plan.legs()).isNotEmpty().allSatisfy(leg ->
            assertThat(leg.arriveMinute()-leg.departMinute()).isBetween(1,2));
        assertThat(TownDayPlan.generate("KE_YUN",2,rhythm,context,date)).isEqualTo(plan);
        var a=new TownDayPlan.DayPlan(date, java.util.List.of(
            new TownDayPlan.Errand("home","idle",0,601,1,"RHYTHM"),
            new TownDayPlan.Errand("cafe","sit",601,602,1,"RHYTHM"),
            new TownDayPlan.Errand("home","idle",602,1440,1,"RHYTHM")),java.util.List.of());
        var plans=java.util.Map.of("A",a,"B",a);
        assertThat(TownDayPlan.encounters(plans)).isNotEmpty();
        assertThat(TownDayPlan.encounters(plans)).isEqualTo(TownDayPlan.encounters(plans));
    }

    @Test
    void coversTheWholeDayWithNoHolesAndNoOverlap() {
        TownNpcRhythm.Rhythm rhythm = rhythmFor("KE_YUN", "KNOWLEDGE", 0.5, 0.85);
        TownDayPlan.DayPlan plan = TownDayPlan.generate("KE_YUN", 2, rhythm, neutralContext(), DAY);

        assertThat(plan.errands().get(0).startMinute()).isZero();
        assertThat(plan.errands().get(plan.errands().size() - 1).endMinute()).isEqualTo(TownDayPlan.MINUTES_PER_DAY);

        List<TownDayPlan.Errand> errands = plan.errands();
        List<TownDayPlan.Leg> legs = plan.legs();
        // errands ∪ legs 无缝覆盖：每个 errand 结束的地方，要么是下一个 errand 的开始，
        // 要么是一条从这里出发的 leg；每条 leg 到达的地方要等于下一个 errand 的开始。
        int cursor = 0;
        int legIndex = 0;
        for (TownDayPlan.Errand errand : errands) {
            if (errand.startMinute() > cursor) {
                assertThat(legIndex).as("must have a leg to fill the gap before %s", errand).isLessThan(legs.size());
                TownDayPlan.Leg leg = legs.get(legIndex++);
                assertThat(leg.departMinute()).isEqualTo(cursor);
                assertThat(leg.arriveMinute()).isEqualTo(errand.startMinute());
            } else {
                assertThat(errand.startMinute()).isEqualTo(cursor);
            }
            cursor = errand.endMinute();
        }
        assertThat(cursor).isEqualTo(TownDayPlan.MINUTES_PER_DAY);
        assertThat(legIndex).isEqualTo(legs.size());

        // errands 互不重叠、按 startMinute 升序。
        for (int i = 1; i < errands.size(); i++) {
            assertThat(errands.get(i).startMinute())
                .isGreaterThanOrEqualTo(errands.get(i - 1).endMinute());
        }

        // 任意一分钟都能问出"他在哪儿"。
        for (int minute = 0; minute < TownDayPlan.MINUTES_PER_DAY; minute += 17) {
            assertThat(TownDayPlan.positionAt(plan, minute)).isNotNull();
        }
    }

    @Test
    void isReproducibleForTheSameNpcAndDayButChangesAcrossDays() {
        TownNpcRhythm.Rhythm rhythm = rhythmFor("LU_XIA", "HEALTH", 0.85, 0.5);
        TownDayPlan.DayPlan first = TownDayPlan.generate("LU_XIA", 2, rhythm, neutralContext(), DAY);
        TownDayPlan.DayPlan again = TownDayPlan.generate("LU_XIA", 2, rhythm, neutralContext(), DAY);
        assertThat(again).isEqualTo(first);

        TownDayPlan.DayPlan tomorrow = TownDayPlan.generate("LU_XIA", 2, rhythm, neutralContext(), DAY.plusDays(1));
        assertThat(tomorrow).isNotEqualTo(first);
    }

    @Test
    void positionAtReturnsAtDuringAnErrandAndWalkingDuringALeg() {
        TownNpcRhythm.Rhythm rhythm = rhythmFor("WEN_QING", "RELATIONSHIP", 0.9, 0.9);
        TownDayPlan.DayPlan plan = TownDayPlan.generate("WEN_QING", 2, rhythm, neutralContext(), DAY);

        TownDayPlan.Leg someLeg = plan.legs().get(0);
        TownDayPlan.Position mid = TownDayPlan.positionAt(plan, (someLeg.departMinute() + someLeg.arriveMinute()) / 2);
        assertThat(mid.kind()).isEqualTo("WALKING");
        assertThat(mid.fromPlace()).isEqualTo(someLeg.fromPlace());
        assertThat(mid.toPlace()).isEqualTo(someLeg.toPlace());
        assertThat(mid.progress()).isBetween(0.0, 1.0);

        TownDayPlan.Errand someErrand = plan.errands().get(0);
        TownDayPlan.Position at = TownDayPlan.positionAt(plan, someErrand.startMinute());
        assertThat(at.kind()).isEqualTo("AT");
        assertThat(at.place()).isEqualTo(someErrand.place());
        assertThat(at.activity()).isEqualTo(someErrand.activity());
    }

    @Test
    void legacyProfilesGainLifeWithoutMutatingTheirStoredErrands() {
        var rhythm = new TownNpcRhythm.Rhythm(360, 1380, List.of(
            new TownNpcRhythm.Errand("academy", 600, 100),
            new TownNpcRhythm.Errand("gym", 1000, 60)));
        var plan = TownDayPlan.generate("KE_YUN", 2, rhythm, neutralContext(), DAY);
        assertThat(rhythm.errands()).hasSize(2);
        assertThat(plan.errands()).anySatisfy(e -> {
            assertThat(e.place()).isNotEqualTo("home");
            assertThat(e.startMinute()).isBetween(390, 480);
        });
        assertThat(plan.legs()).anySatisfy(leg -> {
            assertThat(leg.fromPlace()).isEqualTo("gym");
            assertThat(leg.toPlace()).isNotEqualTo("home");
            assertThat(leg.departMinute()).isEqualTo(1060);
        });
        for (int minute = 0; minute < 1440; minute++) {
            final int m = minute;
            long coverage = plan.errands().stream().filter(e -> e.startMinute() <= m && m < e.endMinute()).count()
                + plan.legs().stream().filter(l -> l.departMinute() <= m && m < l.arriveMinute()).count();
            assertThat(coverage).as("minute %s", minute).isEqualTo(1);
        }
    }

    @Test
    void optionalLifeStillRespectsRainRestAndPriorityEventArrival() {
        var rhythm = new TownNpcRhythm.Rhythm(360, 1380, List.of(
            new TownNpcRhythm.Errand("park", 600, 100),
            new TownNpcRhythm.Errand("gym", 1000, 60)));
        var dry = TownDayPlan.generate("KE_YUN", 2, rhythm, neutralContext(), DAY);
        var wet = TownDayPlan.generate("KE_YUN", 2, rhythm,
            new TownDayPlan.DayPlanContext(0, true, 0, List.of()), DAY);
        assertThat(wet.errands().stream().filter(e -> e.place().equals("park"))
            .mapToInt(e -> e.endMinute() - e.startMinute()).sum()).isLessThan(
                dry.errands().stream().filter(e -> e.place().equals("park"))
                    .mapToInt(e -> e.endMinute() - e.startMinute()).sum());
        assertThat(TownDayPlan.positionAt(dry, 300).place()).isEqualTo("home");
        assertThat(TownDayPlan.positionAt(dry, 1400).place()).isEqualTo("home");
        var invited = TownDayPlan.generate("KE_YUN", 2, rhythm,
            new TownDayPlan.DayPlanContext(0, false, 0,
                List.of(new TownDayPlan.EventSlot("cafe", "sit", 1061, 30))), DAY);
        assertThat(invited.errands().stream().filter(e -> e.origin().equals("EVENT")))
            .singleElement().satisfies(e -> assertThat(e.startMinute()).isEqualTo(1061));
    }

    // ---------------------------------------------------------------- M7-3 当天偏离

    @Test
    void lowMoodValenceProducesFewerOrShorterOutingsThanNeutral() {
        TownNpcRhythm.Rhythm rhythm = rhythmFor("WEN_QING", "RELATIONSHIP", 0.9, 0.9);
        TownDayPlan.DayPlan neutral = TownDayPlan.generate("WEN_QING", 2, rhythm, neutralContext(), DAY);
        TownDayPlan.DayPlan gloomy = TownDayPlan.generate("WEN_QING", 2, rhythm,
            new TownDayPlan.DayPlanContext(-0.8, false, 0.0, List.of()), DAY);

        long neutralAwayMinutes = awayMinutes(neutral);
        long gloomyAwayMinutes = awayMinutes(gloomy);

        assertThat(gloomyAwayMinutes).isLessThan(neutralAwayMinutes);
    }

    @Test
    void deviationIsReproducibleForTheSameSeed() {
        TownNpcRhythm.Rhythm rhythm = rhythmFor("AN_HE", "WELLBEING", 0.2, 0.8);
        TownDayPlan.DayPlanContext context = new TownDayPlan.DayPlanContext(-0.7, true, 0.9, List.of());

        TownDayPlan.DayPlan first = TownDayPlan.generate("AN_HE", 2, rhythm, context, DAY);
        TownDayPlan.DayPlan second = TownDayPlan.generate("AN_HE", 2, rhythm, context, DAY);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void anEventInContextAlwaysInsertsAPriorityTwoErrand() {
        TownNpcRhythm.Rhythm rhythm = rhythmFor("SHEN_MU", "CAREER", 0.15, 0.3);
        TownDayPlan.EventSlot event = new TownDayPlan.EventSlot(TownNpcSchedules.PLAZA, "sit", 900, 60);
        TownDayPlan.DayPlanContext context = new TownDayPlan.DayPlanContext(0.0, false, 0.0, List.of(event));

        TownDayPlan.DayPlan plan = TownDayPlan.generate("SHEN_MU", 2, rhythm, context, DAY);

        List<TownDayPlan.Errand> eventErrands = plan.errands().stream()
            .filter(errand -> "EVENT".equals(errand.origin()))
            .toList();
        assertThat(eventErrands).hasSize(1);
        TownDayPlan.Errand inserted = eventErrands.get(0);
        assertThat(inserted.priority()).isEqualTo(2);
        assertThat(inserted.place()).isEqualTo(TownNpcSchedules.PLAZA);
        assertThat(inserted.startMinute()).isEqualTo(900);
        assertThat(inserted.endMinute()).isEqualTo(960);
    }

    private static long awayMinutes(TownDayPlan.DayPlan plan) {
        return plan.errands().stream()
            .filter(errand -> !TownNpcSchedules.HOME.equals(errand.place()))
            .mapToLong(errand -> errand.endMinute() - errand.startMinute())
            .sum();
    }

    // ---------------------------------------------------------------- M7-4 相遇判定

    private static TownDayPlan.DayPlan planOf(TownDayPlan.Errand... errands) {
        // 测试夹具：手搭 errands，legs 留空——只测 positionAt/classify 的分类逻辑，
        // 不需要 assemble() 帮着补全整天。
        return new TownDayPlan.DayPlan(DAY, List.of(errands), List.of());
    }

    @Test
    void twoNpcsAtTheSamePlaceAtTheSameTimeAreCoLocated() {
        Map<String, TownDayPlan.DayPlan> plans = Map.of(
            "A", planOf(new TownDayPlan.Errand(TownNpcSchedules.CAFE, "sit", 0, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM")),
            "B", planOf(new TownDayPlan.Errand(TownNpcSchedules.CAFE, "sit", 0, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM"))
        );

        List<TownSocialSim.Encounter> encounters = TownDayPlan.encounters(plans);

        assertThat(encounters).isNotEmpty();
        assertThat(encounters).allSatisfy(e -> assertThat(e.type()).isEqualTo(TownSocialSim.EncounterType.CO_LOCATED));
    }

    @Test
    void beingAtHomeTogetherIsNeverAnEncounter() {
        Map<String, TownDayPlan.DayPlan> plans = Map.of(
            "A", planOf(new TownDayPlan.Errand(TownNpcSchedules.HOME, "idle", 0, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM")),
            "B", planOf(new TownDayPlan.Errand(TownNpcSchedules.HOME, "idle", 0, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM"))
        );

        assertThat(TownDayPlan.encounters(plans)).isEmpty();
    }

    @Test
    void twoNpcsWalkingTheSameRouteAtTheSameTimeAreEnRoute() {
        // A 和 B 都从家出发，但去往不同的地方（GYM / CAFE）——共享的只是起点这一段路，
        // 分开之后各自到家目的地，不会又巧合地同处一地变成"同处停留"，方便这条测试只钉
        // EN_ROUTE 这一种分类。
        Map<String, TownDayPlan.DayPlan> plans = new LinkedHashMap<>();
        plans.put("A", new TownDayPlan.DayPlan(DAY,
            List.of(new TownDayPlan.Errand(TownNpcSchedules.HOME, "idle", 0, 400, 1, "RHYTHM"),
                new TownDayPlan.Errand(TownNpcSchedules.GYM, "idle", 430, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM")),
            List.of(new TownDayPlan.Leg(TownNpcSchedules.HOME, TownNpcSchedules.GYM, 400, 430))));
        plans.put("B", new TownDayPlan.DayPlan(DAY,
            List.of(new TownDayPlan.Errand(TownNpcSchedules.HOME, "idle", 0, 405, 1, "RHYTHM"),
                new TownDayPlan.Errand(TownNpcSchedules.CAFE, "sit", 435, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM")),
            List.of(new TownDayPlan.Leg(TownNpcSchedules.HOME, TownNpcSchedules.CAFE, 405, 435))));

        List<TownSocialSim.Encounter> encounters = TownDayPlan.encounters(plans);

        assertThat(encounters).isNotEmpty();
        assertThat(encounters).allSatisfy(e -> assertThat(e.type()).isEqualTo(TownSocialSim.EncounterType.EN_ROUTE));
    }

    @Test
    void oneWalkingThroughWhereTheOtherStandsIsPassing() {
        Map<String, TownDayPlan.DayPlan> plans = new LinkedHashMap<>();
        plans.put("A", planOf(new TownDayPlan.Errand(TownNpcSchedules.PLAZA, "sit", 0, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM")));
        plans.put("B", new TownDayPlan.DayPlan(DAY,
            List.of(new TownDayPlan.Errand(TownNpcSchedules.HOME, "idle", 0, 400, 1, "RHYTHM"),
                new TownDayPlan.Errand(TownNpcSchedules.CAFE, "sit", 430, TownDayPlan.MINUTES_PER_DAY, 1, "RHYTHM")),
            List.of(new TownDayPlan.Leg(TownNpcSchedules.HOME, TownNpcSchedules.PLAZA, 400, 415),
                new TownDayPlan.Leg(TownNpcSchedules.PLAZA, TownNpcSchedules.CAFE, 415, 430))));

        List<TownSocialSim.Encounter> encounters = TownDayPlan.encounters(plans);

        assertThat(encounters).isNotEmpty();
        assertThat(encounters).allSatisfy(e -> assertThat(e.type()).isEqualTo(TownSocialSim.EncounterType.PASSING));
    }

    @Test
    void encountersAreReproducibleForTheSameInput() {
        TownNpcRhythm.Rhythm rhythmA = rhythmFor("KE_YUN", "KNOWLEDGE", 0.5, 0.85);
        TownNpcRhythm.Rhythm rhythmB = rhythmFor("LU_XIA", "HEALTH", 0.85, 0.5);
        Map<String, TownDayPlan.DayPlan> plans = Map.of(
            "KE_YUN", TownDayPlan.generate("KE_YUN", 2, rhythmA, neutralContext(), DAY),
            "LU_XIA", TownDayPlan.generate("LU_XIA", 2, rhythmB, neutralContext(), DAY)
        );

        assertThat(TownDayPlan.encounters(plans)).isEqualTo(TownDayPlan.encounters(plans));
    }
}
