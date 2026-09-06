package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日程是前后端共用的一份：夜间 job 用它推相遇，HTTP 接口用它告诉前端把人画在哪儿。
 * 所以这里钉死的都是"两边都依赖"的性质：覆盖满一天、可复现、以及 §2.4 护栏 B 的人口密度。
 */
class TownNpcSchedulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    private static Map<String, Double> interests(String dominant) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String dimension : List.of("KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING")) {
            weights.put(dimension, dimension.equals(dominant) ? 0.6 : 0.1);
        }
        return weights;
    }

    @Test
    void coversTheWholeDayWithNoHolesAndNoOverlap() {
        List<TownNpcSchedules.Slot> schedule = TownNpcSchedules.forNpc("KE_YUN", 2, interests("KNOWLEDGE"), DAY);

        assertThat(schedule.get(0).startHour()).isZero();
        assertThat(schedule.get(schedule.size() - 1).endHour()).isEqualTo(24);
        for (int i = 1; i < schedule.size(); i++) {
            assertThat(schedule.get(i).startHour())
                .as("slot %d must start exactly where the previous one ended", i)
                .isEqualTo(schedule.get(i - 1).endHour());
        }
        // 一天里的任意一小时都必须能问出"他在哪儿"，否则前端会拿到一个空位。
        for (int hour = 0; hour < 24; hour++) {
            assertThat(TownNpcSchedules.slotAt(schedule, hour)).as("hour %d", hour).isNotNull();
        }
    }

    @Test
    void isReproducibleForTheSameNpcAndDayButChangesAcrossDays() {
        List<TownNpcSchedules.Slot> first = TownNpcSchedules.forNpc("LU_XIA", 2, interests("HEALTH"), DAY);
        List<TownNpcSchedules.Slot> again = TownNpcSchedules.forNpc("LU_XIA", 2, interests("HEALTH"), DAY);
        assertThat(again).isEqualTo(first);

        // 换一天该变——否则小镇每天长得一模一样。
        List<TownNpcSchedules.Slot> tomorrow =
            TownNpcSchedules.forNpc("LU_XIA", 2, interests("HEALTH"), DAY.plusDays(1));
        assertThat(tomorrow).isNotEqualTo(first);
    }

    @Test
    void leavesSomeoneOutAfterDarkRatherThanEmptyingTheStreet() {
        // plan §2.4 护栏 B 的深夜档是 2~3 人。这里按 15% 的夜猫子比例算，期望值 2.7 正落在
        // 那个档上，但它是抽样不是配额，个别日子会到 5~6 个——所以断言写成"有人但仍然稀疏"，
        // 而不是假装能精确卡在 2~3。真正保证同屏人数的是前端的 densityCap（深夜返回 3）。
        for (int hour : new int[]{0, 3, 5, 22, 23}) {
            long outside = TownNpcCatalog.all().stream()
                .filter(archetype -> {
                    TownNpcSchedules.Slot slot = TownNpcSchedules.slotAt(
                        TownNpcSchedules.forNpc(archetype.code(), archetype.layer(),
                            archetype.interests(), DAY),
                        hour);
                    return slot != null && !TownNpcSchedules.HOME.equals(slot.place());
                })
                .count();
            assertThat(outside).as("hour %d: the town must not be completely deserted", hour).isPositive();
            assertThat(outside).as("hour %d: deep night should stay sparse", hour).isLessThanOrEqualTo(6);
        }
    }

    @Test
    void keepsMostPeopleHomeAtNightAndSendsThemOutByDay() {
        long homeAtThree = countAtHome(3);
        long homeAtNoon = countAtHome(12);

        assertThat(homeAtThree).as("most of the town is asleep at 3am").isGreaterThan(homeAtNoon);
        assertThat(homeAtNoon).as("midday is the cafe peak, not nap time").isLessThan(6);
    }

    private long countAtHome(int hour) {
        return TownNpcCatalog.all().stream()
            .filter(archetype -> {
                TownNpcSchedules.Slot slot = TownNpcSchedules.slotAt(
                    TownNpcSchedules.forNpc(archetype.code(), archetype.layer(), archetype.interests(), DAY),
                    hour);
                return slot != null && TownNpcSchedules.HOME.equals(slot.place());
            })
            .count();
    }

    @Test
    void onlyBackgroundResidentsEverDoChores() {
        // M2-3：三层背景居民干活，一二层不干——否则"你熟悉的面孔"会整天在浇水。
        Set<String> labour = Set.of("watering", "chopping", "fishing", "harvesting", "digging");
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            if (archetype.layer() == 3) {
                continue;
            }
            for (TownNpcSchedules.Slot slot :
                TownNpcSchedules.forNpc(archetype.code(), archetype.layer(), archetype.interests(), DAY)) {
                assertThat(labour).as("%s is layer %d and must not be doing chores",
                    archetype.code(), archetype.layer()).doesNotContain(slot.activity());
            }
        }
    }

    // 相遇判定（同处停留 / 路上相遇 / 擦肩）M7 起改由 TownDayPlan.encounters 推出，
    // 不再从这里的整点时段推——见 TownDayPlanTest。
}
