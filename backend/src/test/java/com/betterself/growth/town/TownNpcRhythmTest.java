package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-1 的验收：18 个 NPC 各有一份可读的常态作息，且彼此不雷同（至少 12 种不同的地点序列）。
 */
class TownNpcRhythmTest {

    @Test
    void isAPureFunctionOfTheArchetypesFixedTraits() {
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            TownNpcRhythm.Rhythm first = TownNpcRhythm.defaultFor(
                archetype.code(), archetype.interests(), archetype.shareDrive(), archetype.curiosity());
            TownNpcRhythm.Rhythm again = TownNpcRhythm.defaultFor(
                archetype.code(), archetype.interests(), archetype.shareDrive(), archetype.curiosity());
            assertThat(again).as("%s must be deterministic", archetype.code()).isEqualTo(first);
        }
    }

    @Test
    void everyRhythmIsReadableAndWithinItsOwnWakeSleepWindow() {
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            TownNpcRhythm.Rhythm rhythm = TownNpcRhythm.defaultFor(
                archetype.code(), archetype.interests(), archetype.shareDrive(), archetype.curiosity());

            assertThat(rhythm.wakeMinute()).as("%s wake", archetype.code()).isBetween(0, 1439);
            assertThat(rhythm.sleepMinute()).as("%s sleep", archetype.code()).isGreaterThan(rhythm.wakeMinute());
            assertThat(rhythm.errands()).as("%s has at least two customary errands", archetype.code())
                .hasSizeGreaterThanOrEqualTo(2);

            int previousEnd = rhythm.wakeMinute();
            for (TownNpcRhythm.Errand errand : rhythm.errands()) {
                assertThat(errand.place()).as("no rhythm errand is ever 'home'").isNotEqualTo(TownNpcSchedules.HOME);
                assertThat(errand.startMinute())
                    .as("%s errand must start no earlier than the previous one ended", archetype.code())
                    .isGreaterThanOrEqualTo(previousEnd);
                assertThat(errand.endMinute())
                    .as("%s errand must end before bedtime", archetype.code())
                    .isLessThanOrEqualTo(rhythm.sleepMinute());
                previousEnd = errand.endMinute();
            }
        }
    }

    @Test
    void the18NpcsDoNotAllLookAlike() {
        Set<List<String>> distinctPlaceSequences = new HashSet<>();
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            TownNpcRhythm.Rhythm rhythm = TownNpcRhythm.defaultFor(
                archetype.code(), archetype.interests(), archetype.shareDrive(), archetype.curiosity());
            distinctPlaceSequences.add(rhythm.errands().stream().map(TownNpcRhythm.Errand::place).toList());
        }

        assertThat(TownNpcCatalog.all()).hasSize(18);
        assertThat(distinctPlaceSequences)
            .as("18 个人至少要有 12 种不同的常去地点序列，否则节律等于白推")
            .hasSizeGreaterThanOrEqualTo(12);
    }
}
