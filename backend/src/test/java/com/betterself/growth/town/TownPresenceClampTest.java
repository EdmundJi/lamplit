package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class TownPresenceClampTest {

    @Test
    void passesThroughAMoveWithinTheAllowedBudget() {
        // 100px in 1s is well under 132 * 1 * 1.5 = 198px.
        TownPresenceClamp.Point point = TownPresenceClamp.clamp(0, 0, 100, 0, 1.0);

        assertThat(point.x()).isEqualTo(100);
        assertThat(point.y()).isEqualTo(0);
    }

    @Test
    void clampsATeleportToTheMaxAllowedDistanceAlongTheSameDirection() {
        // Straight line on the x axis, requested jump of 1000px in 1s — way past the 198px budget.
        TownPresenceClamp.Point point = TownPresenceClamp.clamp(0, 0, 1000, 0, 1.0);

        double allowed = TownPresenceClamp.RUN_SPEED_PX_PER_SEC * 1.0 * TownPresenceClamp.SPEED_MARGIN;
        assertThat(point.x()).isEqualTo(allowed, offset(0.0001));
        assertThat(point.y()).isEqualTo(0);
    }

    @Test
    void clampsDiagonalMovementProportionallyOnBothAxes() {
        TownPresenceClamp.Point point = TownPresenceClamp.clamp(0, 0, 3000, 4000, 1.0);

        double allowed = TownPresenceClamp.RUN_SPEED_PX_PER_SEC * 1.0 * TownPresenceClamp.SPEED_MARGIN;
        assertThat(Math.hypot(point.x(), point.y())).isEqualTo(allowed, offset(0.0001));
        // 3-4-5 triangle: the clamped point should keep the same 3:4 ratio between axes.
        assertThat(point.x() / point.y()).isEqualTo(3.0 / 4.0, offset(0.0001));
    }

    @Test
    void allowsExactlyTheBudgetWithoutClamping() {
        double allowed = TownPresenceClamp.RUN_SPEED_PX_PER_SEC * 2.0 * TownPresenceClamp.SPEED_MARGIN;
        TownPresenceClamp.Point point = TownPresenceClamp.clamp(0, 0, allowed, 0, 2.0);

        assertThat(point.x()).isEqualTo(allowed, offset(0.0001));
    }

    @Test
    void treatsALongerElapsedGapAsMoreAllowedDistance() {
        // The same 1000px jump is plausible after a long time away (e.g. reconnecting).
        TownPresenceClamp.Point point = TownPresenceClamp.clamp(0, 0, 1000, 0, 60.0);

        assertThat(point.x()).isEqualTo(1000);
    }

    @Test
    void noMovementIsNeverClamped() {
        TownPresenceClamp.Point point = TownPresenceClamp.clamp(50, 50, 50, 50, 0.0);

        assertThat(point.x()).isEqualTo(50);
        assertThat(point.y()).isEqualTo(50);
    }
}
