package atlas.humanize;

import java.util.Random;

/**
 * How a click that should not land misses (2026-10-25).
 *
 *   AIM_OFF     the look itself goes past the hitbox for that tick -- the way
 *               a person misses, and what a server sees: a swing along a look
 *               that does not meet the box.
 *   SWING_ONLY  the look stays on the target; only the swing goes out.
 *   SKIP        no click at all (the CPS drops).
 */
public final class Misses {
    public enum Style { AIM_OFF, SWING_ONLY, SKIP }

    private Misses() {
    }

    /** A style by the profile's weights; AIM_OFF when every weight is 0. */
    public static Style pick(HumanProfile p, Random random) {
        int a = Math.max(0, p.missAimOff);
        int b = Math.max(0, p.missSwingOnly);
        int c = Math.max(0, p.missSkip);
        int total = a + b + c;
        if (total <= 0) {
            return Style.AIM_OFF;
        }
        int roll = random.nextInt(total);
        return roll < a ? Style.AIM_OFF : (roll < a + b ? Style.SWING_ONLY : Style.SKIP);
    }

    /**
     * The turn, from the rotation at the box's middle, that puts the look
     * {@code aimOffMin..aimOffMax} blocks past the box's edge at
     * {@code distance}: {yaw, pitch} in degrees. Sideways with
     * aimOffHorizontal % of the time (either side), otherwise high or low.
     */
    public static float[] aimOffOffset(HumanProfile p, double distance, double halfWidth, double halfHeight, Random random) {
        double d = Math.max(0.5, distance);
        double extra = p.aimOffMin + random.nextDouble() * Math.max(0.0, p.aimOffMax - p.aimOffMin);
        boolean sideways = random.nextDouble() * 100.0 < p.aimOffHorizontal;
        double sign = random.nextBoolean() ? 1.0 : -1.0;
        if (sideways) {
            return new float[]{(float) (sign * Math.toDegrees(Math.atan2(halfWidth + extra, d))), 0.0F};
        }
        return new float[]{0.0F, (float) (sign * Math.toDegrees(Math.atan2(halfHeight + extra, d)))};
    }
}
