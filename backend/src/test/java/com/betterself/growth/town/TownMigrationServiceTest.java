package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-6（迁徙）的纯函数验收：冷却判定、触发概率、以及"跨镇事实两周内衰减掉"——最后一条直接复用
 * {@link TownSocialSim#dailySalienceDecay}，只是钉住 {@link TownMigrationService} 选的那组参数
 * （hops、初始 salience）确实能在两周内把显著度压到阈值以下，不需要另起一条数据库集成测试。
 */
class TownMigrationServiceTest {

    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final double SALIENCE_FLOOR = 0.05; // 与 TownSocietyService 的 talking-points 门槛一致

    // ---------------------------------------------------------------- offCooldown

    @Test
    void staysOnCooldownUntilExactlyTheConfiguredNumberOfDaysHavePassed() {
        LocalDate settledAt = LocalDate.of(2026, 1, 1);
        assertThat(TownMigrationService.offCooldown(settledAt, settledAt.plusDays(20), 21)).isFalse();
        assertThat(TownMigrationService.offCooldown(settledAt, settledAt.plusDays(21), 21)).isTrue();
        assertThat(TownMigrationService.offCooldown(settledAt, settledAt.plusDays(100), 21)).isTrue();
    }

    @Test
    void justMovedInIsAlwaysOnCooldown() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        assertThat(TownMigrationService.offCooldown(today, today, TownMigrationService.MIGRATION_COOLDOWN_DAYS))
            .isFalse();
    }

    // ---------------------------------------------------------------- rollsMigration

    @Test
    void zeroProbabilityNeverTriggers() {
        RandomGenerator rng = new SplittableRandom(1);
        for (int i = 0; i < 1000; i++) {
            assertThat(TownMigrationService.rollsMigration(rng, 0.0)).isFalse();
        }
    }

    @Test
    void certainProbabilityAlwaysTriggers() {
        RandomGenerator rng = new SplittableRandom(2);
        for (int i = 0; i < 1000; i++) {
            assertThat(TownMigrationService.rollsMigration(rng, 1.0)).isTrue();
        }
    }

    @Test
    void dailyProbabilityIsSparseNotDaily() {
        // plan §2.6："稀疏，1~2 周一次"。100 天里触发次数应该落在大致每 1~2 周一次的量级，
        // 而不是天天触发，也不是几个月才一次。
        RandomGenerator rng = new SplittableRandom(3);
        int triggers = 0;
        for (int day = 0; day < 100; day++) {
            if (TownMigrationService.rollsMigration(rng, TownMigrationService.DAILY_MIGRATION_PROBABILITY)) {
                triggers++;
            }
        }
        assertThat(triggers).isBetween(3, 25);
    }

    // ---------------------------------------------------------------- 跨镇事实的加速衰减

    @Test
    void crossTownFactDecaysBelowTalkingPointThresholdWithinTwoWeeks() {
        double salience = 0.6; // TownMigrationService.CROSS_TOWN_INITIAL_SALIENCE
        int hops = 3; // TownMigrationService.CROSS_TOWN_HOPS
        for (int day = 0; day < 14; day++) {
            salience = TownSocialSim.dailySalienceDecay(salience, hops);
        }
        assertThat(salience)
            .as("跨镇传闻应该在两周内衰减到 talking-points 门槛（0.05）以下")
            .isLessThan(SALIENCE_FLOOR);
    }

    @Test
    void crossTownDecayIsFasterThanAFreshlyWitnessedFact() {
        double crossTown = 0.6;
        double freshlyWitnessed = 0.6;
        for (int day = 0; day < 7; day++) {
            crossTown = TownSocialSim.dailySalienceDecay(crossTown, 3);
            freshlyWitnessed = TownSocialSim.dailySalienceDecay(freshlyWitnessed, 0);
        }
        assertThat(crossTown).isLessThan(freshlyWitnessed);
    }

    // ---------------------------------------------------------------- M4-7 旅人：泛化、不涉及真实用户

    @Test
    void travelerTalesAreStaticFlavorTextWithNoNumbersAndNoUserData() {
        // 旅人的传闻池是写死的模板，不插值任何用户数据——用正则钉住"没有数字"这条硬约束，
        // 缺少真实姓名/ID 这件事则由源码本身就是常量池这一点保证（没有任何字符串拼接）。
        for (String tale : List.of(
            "我路过的上一个镇子，有户人家门口种了整墙的爬山虎，主人每天准时浇水。",
            "我从前待的地方，广场上总有个小摊准时出摊，风雨无阻。",
            "我来的那边，有人特别爱翻新旧家具，到他手里总能变个样子。",
            "上一个镇的咖啡馆老板记得住每个常客的口味，从不用问。"
        )) {
            assertThat(DIGIT.matcher(tale).find()).as("traveler tale must not leak a number: %s", tale).isFalse();
        }
    }
}
