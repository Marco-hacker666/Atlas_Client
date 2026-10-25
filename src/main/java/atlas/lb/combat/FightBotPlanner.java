package atlas.lb.combat;

import java.util.function.BiPredicate;

/**
 * Where the LiquidBounce aura's FightBot walks (2026-10-25, LiquidBounce
 * NextGen port, GPL-3.0; KillAuraFightBot and NavigationBaseValueGroup).
 *
 * With the next click coming it goes to the nearest point at attack range
 * around the target that is outside the target's "dangerous" angle (where it
 * is looking); while no click is due (RunawayOnCooldown) it backs away along
 * the line to the target; with a leader set it keeps within a radius of him.
 * The walk keys are the 8-way direction of the goal relative to the view.
 * Positions are {x, z}; yaw in Minecraft degrees (0 = +z, 90 = -x).
 */
public final class FightBotPlanner {
    private FightBotPlanner() {
    }

    /** The horizontal look vector of a yaw: {x, z}. */
    public static double[] direction(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** The yaw from {@code from} looking at {@code to}. */
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

    /** Whether the target's look is within dangerousYaw of facing us: "in danger" is the opposite of LiquidBounce's outOfDanger. */
    public static boolean outOfDanger(float targetYaw, float requiredYaw, float dangerousYaw) {
        return Math.abs(wrap(requiredYaw - targetYaw)) > dangerousYaw;
    }

    /**
     * The attack spot: every 10 degrees around the target at {@code range},
     * not inside a block and not within dangerousYaw of where the target
     * looks, nearest to the player; the spot along the target's look when none.
     */
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

    /** Backing away: {@code range} along the yaw at which the player would look at the target. */
    public static double[] runawayPosition(double playerX, double playerZ, float requiredYaw, double range) {
        double[] d = direction(requiredYaw);
        return new double[]{playerX + d[0] * range, playerZ + d[1] * range};
    }

    /** The point every 45 degrees around the leader at {@code radius} nearest to the player. */
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

    /**
     * The walk keys toward a goal: {forward, strafe} each -1, 0 or 1 (vanilla:
     * strafe positive = left). An axis is pressed only when the goal is more
     * than {@code deadAngle} degrees off its perpendicular.
     */
    public static int[] directionalInput(double dx, double dz, float viewYaw, float deadAngle) {
        if (dx * dx + dz * dz < 1.0E-6) {
            return new int[]{0, 0};
        }
        float relative = wrap(yawTo(0, 0, dx, dz) - viewYaw);
        float abs = Math.abs(relative);
        int forward = abs < 90.0F - deadAngle ? 1 : (abs > 90.0F + deadAngle ? -1 : 0);
        int strafe = 0;
        if (abs > deadAngle && abs < 180.0F - deadAngle) {
            /* Turning right raises the yaw: a goal at a positive relative yaw is to the right. */
            strafe = relative > 0 ? -1 : 1;
        }
        return new int[]{forward, strafe};
    }

    /** The movement yaw of walk keys under a view yaw (the direction the keys move in). */
    public static float movementYaw(float viewYaw, float forward, float strafe) {
        if (forward == 0.0F && strafe == 0.0F) {
            return viewYaw;
        }
        return wrap(viewYaw + (float) Math.toDegrees(Math.atan2(-strafe, forward)));
    }
}
