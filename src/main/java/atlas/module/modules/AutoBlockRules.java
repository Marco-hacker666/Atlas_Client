package atlas.module.modules;

final class AutoBlockRules {
    private static final double MOVING = 0.03;

    private static final double AWAY = 0.5;

    private AutoBlockRules() {
    }

    static int extraHoldTicks(int maxExtra, double randomA, double randomB) {
        if (maxExtra <= 0) {
            return 0;
        }
        return Math.min((int) (randomA * (maxExtra + 1)), (int) (randomB * (maxExtra + 1)));
    }

    static boolean coolingDown(int cooldownMs, long sinceLastMs) {
        return cooldownMs > 0 && sinceLastMs >= 0L && sinceLastMs < cooldownMs;
    }

    static boolean movingAway(double moveX, double moveZ, double toTargetX, double toTargetZ) {
        double speed = Math.hypot(moveX, moveZ);
        double distance = Math.hypot(toTargetX, toTargetZ);
        if (speed < MOVING || distance < 1.0E-4) {
            return false;
        }
        return (moveX * toTargetX + moveZ * toTargetZ) / (speed * distance) >= AWAY;
    }
}
