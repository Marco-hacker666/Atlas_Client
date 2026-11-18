package atlas.util;

// The look that throws a projectile onto a point (ported from LiquidBounce's RotationUtils.faceTrajectory, GPL-3).

public final class TrajectoryAim {
    public static final float ARROW_GRAVITY = 0.05F;
    public static final float THROWN_GRAVITY = 0.03F;
    public static final float THROWN_VELOCITY = 0.5F;

    private TrajectoryAim() {
    }

    public static float bowVelocity(int useTicks) {
        float f = Math.max(0, useTicks) / 20.0F;
        f = (f * f + f * 2.0F) / 3.0F;
        return Math.min(f, 1.0F);
    }

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
