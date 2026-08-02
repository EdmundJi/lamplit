package com.betterself.growth.career;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleProgressionServiceTest {

    @Test
    void usesFibonacciLevelRequirementsAndCapsLevelTenExperience() {
        assertThat(RoleProgressionService.levelFor(0)).isEqualTo(1);
        assertThat(RoleProgressionService.levelFor(9)).isEqualTo(1);
        assertThat(RoleProgressionService.levelFor(10)).isEqualTo(2);
        assertThat(RoleProgressionService.levelFor(24)).isEqualTo(2);
        assertThat(RoleProgressionService.levelFor(25)).isEqualTo(3);
        assertThat(RoleProgressionService.levelFor(1_149)).isEqualTo(9);
        assertThat(RoleProgressionService.levelFor(1_150)).isEqualTo(10);
        assertThat(RoleProgressionService.levelFor(2_149)).isEqualTo(10);
        assertThat(RoleProgressionService.levelFor(50_000)).isEqualTo(10);

        assertThat(RoleProgressionService.requirementForLevel(1)).isEqualTo(10);
        assertThat(RoleProgressionService.requirementForLevel(2)).isEqualTo(15);
        assertThat(RoleProgressionService.requirementForLevel(3)).isEqualTo(25);
        assertThat(RoleProgressionService.requirementForLevel(4)).isEqualTo(40);
        assertThat(RoleProgressionService.requirementForLevel(5)).isEqualTo(65);
        assertThat(RoleProgressionService.requirementForLevel(6)).isEqualTo(105);
        assertThat(RoleProgressionService.requirementForLevel(7)).isEqualTo(170);
        assertThat(RoleProgressionService.requirementForLevel(8)).isEqualTo(275);
        assertThat(RoleProgressionService.requirementForLevel(9)).isEqualTo(445);
        assertThat(RoleProgressionService.requirementForLevel(10)).isEqualTo(999);
    }
}
