package atlas.module.modules;

/**
 * AutoBlock decisions that need no game (2026-11-05, behaviour seen in OpenB4 and Raven bS), so they can be tested.
 */
final class AutoBlockRules {

    /** Below this many blocks a tick the target is standing, whatever way it is drifting. */
    private static final double MOVING = 0.03;
    /** cos 60 degrees: moving away is within 60 degrees of straight away from this player. */
    private static final double AWAY = 0.5;

    private AutoBlockRules() {
    }

    /** Ticks added to a hold: the shorter of two draws, so short holds come more often than long ones. */
    static int extraHoldTicks(int maxExtra, double randomA, double randomB) {
        if (maxExtra <= 0) {
            return 0;
        }
        return Math.min((int) (randomA * (maxExtra + 1)), (int) (randomB * (maxExtra + 1)));
    }

    /** The sword may not go up yet: {@code sinceLastMs} after the last block ended, negative for no block yet. */
    static boolean coolingDown(int cooldownMs, long sinceLastMs) {
        return cooldownMs > 0 && sinceLastMs >= 0L && sinceLastMs < cooldownMs;
    }

    /** The target's last move, against the line from this player to it, is running away. */
    static boolean movingAway(double moveX, double moveZ, double toTargetX, double toTargetZ) {
        double speed = Math.hypot(moveX, moveZ);
        double distance = Math.hypot(toTargetX, toTargetZ);
        if (speed < MOVING || distance < 1.0E-4) {
            return false;
        }
        return (moveX * toTargetX + moveZ * toTargetZ) / (speed * distance) >= AWAY;
    }
}
