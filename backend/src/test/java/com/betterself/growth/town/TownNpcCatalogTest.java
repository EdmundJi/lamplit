package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Pure checks on the fixed 18-NPC roster — CONTRACT.md §7. */
class TownNpcCatalogTest {

    @Test
    void hasExactlyEighteenArchetypes() {
        assertThat(TownNpcCatalog.all()).hasSize(18);
    }

    @Test
    void layerCountsAreTwoSixTen() {
        List<TownNpcCatalog.Archetype> all = TownNpcCatalog.all();
        assertThat(all.stream().filter(a -> a.layer() == 1).count()).isEqualTo(2);
        assertThat(all.stream().filter(a -> a.layer() == 2).count()).isEqualTo(6);
        assertThat(all.stream().filter(a -> a.layer() == 3).count()).isEqualTo(10);
    }

    @Test
    void codesAreUnique() {
        Set<String> codes = TownNpcCatalog.all().stream()
            .map(TownNpcCatalog.Archetype::code)
            .collect(Collectors.toSet());
        assertThat(codes).hasSize(18);
    }

    @Test
    void spritesAreUniqueAmongLayerThree() {
        List<String> layerThreeSprites = TownNpcCatalog.all().stream()
            .filter(a -> a.layer() == 3)
            .map(TownNpcCatalog.Archetype::sprite)
            .toList();
        assertThat(new HashSet<>(layerThreeSprites)).hasSameSizeAs(layerThreeSprites);
    }

    @Test
    void everyArchetypesInterestsSumToOne() {
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            double sum = archetype.interests().values().stream().mapToDouble(Double::doubleValue).sum();
            assertThat(sum)
                .as("interests for %s", archetype.code())
                .isCloseTo(1.0, offset(1e-9));
        }
    }

    @Test
    void layerTwoCoversEachDimensionExactlyOncePlusOneFreeSlot() {
        List<TownNpcCatalog.Archetype> layerTwo = TownNpcCatalog.all().stream()
            .filter(a -> a.layer() == 2)
            .toList();
        assertThat(layerTwo).hasSize(6);

        List<String> dimensions = layerTwo.stream()
            .map(TownNpcCatalog.Archetype::dimension)
            .filter(dimension -> dimension != null)
            .toList();
        assertThat(dimensions).containsExactlyInAnyOrderElementsOf(TownNpcCatalog.DIMENSIONS);

        long freeSlots = layerTwo.stream().filter(a -> a.dimension() == null).count();
        assertThat(freeSlots).isEqualTo(1);
    }

    @Test
    void layerTwoArchetypesWeighTheirBoundDimensionHighest() {
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            if (archetype.layer() != 2 || archetype.dimension() == null) {
                continue;
            }
            double own = archetype.interests().get(archetype.dimension());
            double max = archetype.interests().values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            assertThat(own)
                .as("%s should weigh its own dimension %s highest", archetype.code(), archetype.dimension())
                .isEqualTo(max);
            long timesAtMax = archetype.interests().values().stream().filter(v -> v == max).count();
            assertThat(timesAtMax).as("%s's bound dimension should be the unique maximum", archetype.code()).isEqualTo(1);
        }
    }

    @Test
    void quirksComeOnlyFromTheAllowedEnum() {
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            assertThat(archetype.quirks())
                .as("quirks for %s", archetype.code())
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(2)
                .isSubsetOf(TownNpcCatalog.ALLOWED_QUIRKS);
        }
    }

    @Test
    void byCodeLooksUpEachArchetype() {
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            assertThat(TownNpcCatalog.byCode(archetype.code())).isEqualTo(archetype);
        }
        assertThat(TownNpcCatalog.byCode("NOT_A_REAL_CODE")).isNull();
    }
}
