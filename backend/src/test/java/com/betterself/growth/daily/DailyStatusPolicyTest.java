package com.betterself.growth.daily;

import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

class DailyStatusPolicyTest {

    @Test
    void lowEnergyOrShortTimeShrinks() {
        assertThat(DailyStatusService.adviceFor("LOW", 30)).isEqualTo("SHRINK");
        assertThat(DailyStatusService.adviceFor("STEADY", 15)).isEqualTo("SHRINK");
        assertThat(DailyStatusService.adviceFor("OPEN", 10)).isEqualTo("SHRINK");
    }

    @Test
    void openEnergyWithEnoughTimeKeepsPlan() {
        assertThat(DailyStatusService.adviceFor("OPEN", 45)).isEqualTo("KEEP");
        assertThat(DailyStatusService.adviceFor("OPEN", 90)).isEqualTo("KEEP");
    }

    @Test
    void everythingElseLightsUp() {
        assertThat(DailyStatusService.adviceFor("STEADY", 30)).isEqualTo("LIGHT");
        assertThat(DailyStatusService.adviceFor("STEADY", 60)).isEqualTo("LIGHT");
        assertThat(DailyStatusService.adviceFor("OPEN", 30)).isEqualTo("LIGHT");
        assertThat(DailyStatusService.adviceFor("LOW", 60)).isEqualTo("SHRINK");
    }
}
