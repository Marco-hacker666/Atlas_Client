package atlas.util;

/**
 * The look that throws a projectile onto a point (2026-10-31, ported from
 * LiquidBounce's RotationUtils.faceTrajectory, GPL-3).
 *
 * Projectile speed is given as a share of an arrow at full draw (1.0 = 3
 * blocks a tick), the same scale LiquidBounce uses: a bow at {@code charge}
 * is {@link #bowVelocity}, a thrown egg, snowball or pearl 0.5 (1.5 blocks a
 * tick). Gravity is the projectile's own per tick (arrow 0.05, thrown 0.03);
 * the 0.12 factor folds the speed scale into it. Drag is left out, as in the
 * original: close enough inside a bow's useful range.
 *
 * Pure arithmetic, so it is tested without the game.
 */
public final class TrajectoryAim {
    public static final float ARROW_GRAVITY = 0.05F;
    public static final float THROWN_GRAVITY = 0.03F;
    public static final float THROWN_VELOCITY = 0.5F;

    private TrajectoryAim() {
    }

    /** Vanilla's arrow speed share for a bow drawn {@code useTicks} ticks (ItemBow.onPlayerStoppedUsing), at most 1. */
    public static float bowVelocity(int useTicks) {
        float f = Math.max(0, useTicks) / 20.0F;
        f = (f * f + f * 2.0F) / 3.0F;
        return Math.min(f, 1.0F);
    }

    /**
     * {yaw, pitch} in degrees that land a projectile {@code dx, dy, dz} away
     * from the eyes (dy from eye height), or null when it cannot get there --
     * out of range, straight up or down, or no speed. Never NaN: the original
     * returned a NaN pitch out of range, which a server rejects.
     */
    public static float[] solve(double dx, double dy, double dz, float velocity, float gravity) {
        if (!(velocity > 0.0F) || !(gravity > 0.0F) || !finite(dx) || !finite(dy) || !finite(dz)) {
            return null;
        }
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return null;
        }
        double g = 0.12 * gravity;
        double v2 = (double) velocity * velocity;
        double root = v2 * v2 - g * (g * horizontal * horizontal + 2.0 * dy * v2);
        if (root < 0.0) {
            return null;
        }
        double pitch = -Math.toDegrees(Math.atan((v2 - Math.sqrt(root)) / (g * horizontal)));
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        if (!finite(pitch) || !finite(yaw) || pitch < -90.0 || pitch > 90.0) {
            return null;
        }
        return new float[]{(float) yaw, (float) pitch};
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
