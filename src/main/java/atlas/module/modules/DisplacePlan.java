package atlas.module.modules;

final class DisplacePlan {
    static final int DIRECTION_LEFT = 0;
    static final int DIRECTION_RIGHT = 1;
    static final int DIRECTION_RANDOM = 2;
    static final int DIRECTION_WITH_STRAFE = 3;
    static final int DIRECTION_AGAINST_STRAFE = 4;

    static final int LOCK_TICKS = 20;

    private static final float FACING_AWAY = 100.0F;

    private DisplacePlan() {
    }

    static boolean worthTurning(int targetHurtTime, int maxHurtTime, boolean sprinting, boolean knockbackEnchant,
                                long sinceLastMs, long cooldownMs) {
        return targetHurtTime <= maxHurtTime && (sprinting || knockbackEnchant) && sinceLastMs >= cooldownMs;
    }

    static boolean inTurnRange(float distance, float range) {
        return range <= 0.0F || distance <= range;
    }

    static boolean facingAway(float yawDifference) {
        return Math.abs(yawDifference) < FACING_AWAY;
    }

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

    static boolean lockHolds(int lockedTarget, int target, int lastTurnTick, int tick) {
        return lockedTarget == target && tick - lastTurnTick < LOCK_TICKS;
    }

    static double edgeCost(double dropDistance, float turn) {
        return dropDistance + turn / 90.0;
    }

    static float jitter(float range, double random) {
        return (float) ((random * 2.0 - 1.0) * range);
    }
}
