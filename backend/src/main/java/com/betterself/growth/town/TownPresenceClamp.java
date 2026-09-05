package com.betterself.growth.town;

/**
 * Keeps a reported position within a plausible walking distance of the last one on record.
 * Pure math, no DB — network jitter or someone reconnecting after being offline for hours
 * both produce a big jump that isn't cheating, so this clamps toward it instead of rejecting
 * the report outright.
 */
final class TownPresenceClamp {

    static final double RUN_SPEED_PX_PER_SEC = 132.0;
    static final double SPEED_MARGIN = 1.5;

    private TownPresenceClamp() {
    }

    record Point(double x, double y) {
    }

    /** {@code elapsedSeconds} must be the real gap since the last accepted report, same scene. */
    static Point clamp(double fromX, double fromY, double toX, double toY, double elapsedSeconds) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double distance = Math.hypot(dx, dy);
        if (distance == 0) {
            return new Point(toX, toY);
        }
        double allowed = Math.max(0, RUN_SPEED_PX_PER_SEC * elapsedSeconds * SPEED_MARGIN);
        if (distance <= allowed) {
            return new Point(toX, toY);
        }
        double ratio = allowed / distance;
        return new Point(fromX + dx * ratio, fromY + dy * ratio);
    }
}
