package atlas.lb.combat;

import java.util.function.BiPredicate;

// Where the LiquidBounce aura's FightBot walks (LiquidBounce NextGen port, GPL-3.0)

public final class FightBotPlanner {
    private FightBotPlanner() {
    }

    public static double[] direction(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        return (float) Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90.0F;
    }

    static float wrap(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    public static boolean outOfDanger(float targetYaw, float requiredYaw, float dangerousYaw) {
        return Math.abs(wrap(requiredYaw - targetYaw)) > dangerousYaw;
    }

    public static double[] attackPosition(double targetX, double targetZ, float targetYaw, double range,
                                          float dangerousYaw, double playerX, double playerZ,
                                          BiPredicate<Double, Double> collides) {
        double[] look = direction(targetYaw);
        double[] fallback = {targetX + look[0] * range, targetZ + look[1] * range};
        double[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int yaw = -180; yaw <= 180; yaw += 10) {
            double[] d = direction(yaw);
            double x = targetX + d[0] * range;
            double z = targetZ + d[1] * range;
            if (collides.test(x, z)) {
                continue;
            }
            if (Math.abs(wrap(yaw - targetYaw)) <= dangerousYaw) {
                continue;
            }
            double dist = (x - playerX) * (x - playerX) + (z - playerZ) * (z - playerZ);
            if (dist < bestDist) {
                bestDist = dist;
                best = new double[]{x, z};
            }
        }
        return best != null ? best : fallback;
    }

    public static double[] runawayPosition(double playerX, double playerZ, float requiredYaw, double range) {
        double[] d = direction(requiredYaw);
        return new double[]{playerX + d[0] * range, playerZ + d[1] * range};
    }

    public static double[] leaderPosition(double leaderX, double leaderZ, double radius, double playerX, double playerZ) {
        double[] best = {leaderX, leaderZ};
        double bestDist = Double.MAX_VALUE;
        for (int yaw = -180; yaw <= 180; yaw += 45) {
            double[] d = direction(yaw);
            double x = leaderX + d[0] * radius;
            double z = leaderZ + d[1] * radius;
            double dist = (x - playerX) * (x - playerX) + (z - playerZ) * (z - playerZ);
            if (dist < bestDist) {
                bestDist = dist;
                best = new double[]{x, z};
            }
        }
        return best;
    }

    public static int[] directionalInput(double dx, double dz, float viewYaw, float deadAngle) {
        if (dx * dx + dz * dz < 1.0E-6) {
            return new int[]{0, 0};
        }
        float relative = wrap(yawTo(0, 0, dx, dz) - viewYaw);
        float abs = Math.abs(relative);
        int forward = abs < 90.0F - deadAngle ? 1 : (abs > 90.0F + deadAngle ? -1 : 0);
        int strafe = 0;
        if (abs > deadAngle && abs < 180.0F - deadAngle) {
            strafe = relative > 0 ? -1 : 1;
        }
        return new int[]{forward, strafe};
    }

    public static float movementYaw(float viewYaw, float forward, float strafe) {
        if (forward == 0.0F && strafe == 0.0F) {
            return viewYaw;
        }
        return wrap(viewYaw + (float) Math.toDegrees(Math.atan2(-strafe, forward)));
    }
}
