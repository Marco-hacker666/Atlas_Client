package atlas.module.modules;

/**
 * Displace's decisions that need no game (2026-11-05), so they can be tested: which hits are worth the tick a turn
 * costs, and which way the turn goes.
 */
final class DisplacePlan {

    static final int DIRECTION_LEFT = 0;
    static final int DIRECTION_RIGHT = 1;
    static final int DIRECTION_RANDOM = 2;
    static final int DIRECTION_WITH_STRAFE = 3;
    static final int DIRECTION_AGAINST_STRAFE = 4;

    /** A target hit again within this many ticks is the same fight: the locked direction holds. */
    static final int LOCK_TICKS = 20;
    /** Off the target's own yaw by less than this, it is looking the way we are: its back is to us. */
    private static final float FACING_AWAY = 100.0F;

    private DisplacePlan() {
    }

    /**
     * Effective mode: the turn costs the hit a tick, so it is kept for a hit that can do something. Only the sprint or
     * Knockback part of the knockback follows the look, and a target inside its hurt time takes no knockback at all.
     */
    static boolean worthTurning(int targetHurtTime, int maxHurtTime, boolean sprinting, boolean knockbackEnchant,
                                long sinceLastMs, long cooldownMs) {
        return targetHurtTime <= maxHurtTime && (sprinting || knockbackEnchant) && sinceLastMs >= cooldownMs;
    }

    /** Close enough for the turn to count. A range of 0 is off. */
    static boolean inTurnRange(float distance, float range) {
        return range <= 0.0F || distance <= range;
    }

    static boolean facingAway(float yawDifference) {
        return Math.abs(yawDifference) < FACING_AWAY;
    }

    /**
     * The side of the turn with no void to aim for. {@code strafe} is this tick's strafe key, or the last one pressed:
     * above zero is left. With no strafe yet the turn goes left, so these two modes are never dead.
     */
    static boolean left(int direction, float strafe, boolean randomLeft) {
        switch (direction) {
            case DIRECTION_RIGHT:
                return false;
            case DIRECTION_RANDOM:
                return randomLeft;
            case DIRECTION_WITH_STRAFE:
                return strafe >= 0.0F;
            case DIRECTION_AGAINST_STRAFE:
                return strafe <= 0.0F;
            default:
                return true;
        }
    }

    /** The last turn on this target is recent enough for its direction to hold. */
    static boolean lockHolds(int lockedTarget, int target, int lastTurnTick, int tick) {
        return lockedTarget == target && tick - lastTurnTick < LOCK_TICKS;
    }

    /** VOID: the nearer drop first; 90 degrees of turn weighs as a block of distance. */
    static double edgeCost(double dropDistance, float turn) {
        return dropDistance + turn / 90.0;
    }

    /** A random offset in [-range, range], from a random number in [0, 1). */
    static float jitter(float range, double random) {
        return (float) ((random * 2.0 - 1.0) * range);
    }
}
