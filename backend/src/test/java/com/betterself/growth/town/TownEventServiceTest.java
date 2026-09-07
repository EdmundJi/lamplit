package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-1（NPC 自主办活动）与 M4-4（请柬）的纯函数验收：不依赖数据库，直接钉住
 * {@link TownEventService#hostProbability}、{@link TownEventService#pickHost} 和
 * {@link TownEventService#rankRecipients} 的行为。
 */
class TownEventServiceTest {

    // ---------------------------------------------------------------- hostProbability

    @Test
    void probabilityNeverLeavesUnitInterval() {
        for (double shareDrive = 0; shareDrive <= 1.0; shareDrive += 0.1) {
            for (int friends = 0; friends <= 10; friends++) {
                for (double valence = -1.0; valence <= 1.0; valence += 0.2) {
                    double p = TownEventService.hostProbability(shareDrive, friends, valence);
                    assertThat(p).isBetween(0.0, 1.0);
                }
            }
        }
    }

    @Test
    void higherShareDriveNeverLowersProbability() {
        double low = TownEventService.hostProbability(0.1, 3, 0.0);
        double high = TownEventService.hostProbability(0.9, 3, 0.0);
        assertThat(high).isGreaterThan(low);
    }

    // ---------------------------------------------------------------- pickHost 的 30 天模拟

    /**
     * plan §4 M4-1 验收：「不会连续一周冷场，也不会天天办」。用一组混合性格的候选人跑满 30 天，
     * 断言：既不是天天办（总次数远小于 30），也不是一整周没人办过。
     */
    @Test
    void thirtyDaySimulationNeitherGoesSilentForAWeekNorHostsEveryDay() {
        List<TownEventService.HostCandidate> candidates = List.of(
            new TownEventService.HostCandidate("KE_YUN", "柯云", "KNOWLEDGE", 0.50, 2, 0.1),
            new TownEventService.HostCandidate("LU_XIA", "陆夏", "HEALTH", 0.85, 4, 0.3),
            new TownEventService.HostCandidate("SHEN_MU", "沈牧", "CAREER", 0.15, 1, -0.1),
            new TownEventService.HostCandidate("WEN_QING", "温晴", "RELATIONSHIP", 0.90, 5, 0.4),
            new TownEventService.HostCandidate("AN_HE", "安禾", "WELLBEING", 0.20, 2, 0.0),
            new TownEventService.HostCandidate("JI_MAI", "纪麦", null, 0.60, 3, 0.2)
        );

        int hostedDays = 0;
        int longestSilentStreak = 0;
        int currentSilentStreak = 0;
        for (int day = 0; day < 30; day++) {
            RandomGenerator rng = new SplittableRandom(seedForTest(day));
            Optional<String> host = TownEventService.pickHost(candidates, rng);
            if (host.isPresent()) {
                hostedDays++;
                currentSilentStreak = 0;
            } else {
                currentSilentStreak++;
                longestSilentStreak = Math.max(longestSilentStreak, currentSilentStreak);
            }
        }

        assertThat(hostedDays)
            .as("30 天里应该有活动，但不该天天都有")
            .isGreaterThan(0)
            .isLessThan(20);
        assertThat(longestSilentStreak)
            .as("不该连续一周（7 天）都没有任何人办活动")
            .isLessThan(7);
    }

    private static long seedForTest(int day) {
        return 42L * 1_000_033L + day + 7;
    }

    // ---------------------------------------------------------------- rankRecipients

    @Test
    void rankRecipientsOrdersByAffinityDescending() {
        List<TownEventService.Recipient> ranked = TownEventService.rankRecipients(List.of(
            new TownEventService.Recipient("NPC", "LOW", 0.10),
            new TownEventService.Recipient("PLAYER", "PLAYER", 0.55),
            new TownEventService.Recipient("NPC", "HIGH", 0.80)
        ));

        assertThat(ranked).extracting(TownEventService.Recipient::ref)
            .containsExactly("HIGH", "PLAYER", "LOW");
    }

    @Test
    void rankRecipientsBreaksTiesByRefForReproducibility() {
        List<TownEventService.Recipient> ranked = TownEventService.rankRecipients(List.of(
            new TownEventService.Recipient("NPC", "B", 0.5),
            new TownEventService.Recipient("NPC", "A", 0.5)
        ));
        assertThat(ranked).extracting(TownEventService.Recipient::ref).containsExactly("A", "B");
    }

    // ---------------------------------------------------------------- 红线：不占主动性预算 / 不涉及 regard

    @Test
    void hostCandidateShapeCarriesNothingButWhatThePolicyNeeds() {
        // 纯粹的形状断言：HostCandidate 里没有 regard/regardKind 这类字段——办活动这条链路
        // 完全不碰 plan §2.5 的牵挂系统，四条红线里"不占主动性预算""不做确认界面"天然无从违反。
        var fields = Set.of("npcCode", "displayName", "dimension", "shareDrive", "friendCount", "moodValence");
        var declared = new ArrayList<String>();
        for (var field : TownEventService.HostCandidate.class.getRecordComponents()) {
            declared.add(field.getName());
        }
        assertThat(declared).containsExactlyInAnyOrderElementsOf(fields);
    }
}
