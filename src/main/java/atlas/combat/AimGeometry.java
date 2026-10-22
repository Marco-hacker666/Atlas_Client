package atlas.combat;

/**
 * Geometry for aiming at a hitbox, on plain doubles so it can be tested
 * without the game. A box is {minX, minY, minZ, maxX, maxY, maxZ}; a
 * direction is a unit vector; rotations use Minecraft's convention
 * (yaw 0 = +Z, yaw 90 = -X, pitch 90 = straight down).
 */
public final class AimGeometry {
    private AimGeometry() {
    }

    /** The unit look vector for a rotation, as Entity.getVectorForRotation. */
    public static double[] direction(float yaw, float pitch) {
        double f = Math.cos(-yaw * 0.017453292F - Math.PI);
        double f1 = Math.sin(-yaw * 0.017453292F - Math.PI);
        double f2 = -Math.cos(-pitch * 0.017453292F);
        double f3 = Math.sin(-pitch * 0.017453292F);
        return new double[]{f1 * f2, f3, f * f2};
    }

    /** {yaw, pitch} looking from {@code eyes} at {@code point}. */
    public static float[] rotationTo(double[] eyes, double[] point) {
        double dx = point[0] - eyes[0];
        double dy = point[1] - eyes[1];
        double dz = point[2] - eyes[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(dy, flat) * 180.0 / Math.PI);
        return new float[]{yaw, Math.max(-90.0F, Math.min(90.0F, pitch))};
    }

    public static boolean contains(double[] box, double[] p) {
        return p[0] >= box[0] && p[0] <= box[3] && p[1] >= box[1] && p[1] <= box[4] && p[2] >= box[2] && p[2] <= box[5];
    }

    /**
     * Distance along the ray at which it enters the box, 0 if it starts
     * inside, or -1 when it misses the box within {@code range}.
     */
    public static double hitDistance(double[] eyes, double[] dir, double[] box, double range) {
        if (contains(box, eyes)) {
            return 0.0;
        }
        double tMin = 0.0;
        double tMax = range;
        for (int axis = 0; axis < 3; axis++) {
            double o = eyes[axis];
            double d = dir[axis];
            double lo = box[axis];
            double hi = box[axis + 3];
            if (Math.abs(d) < 1.0E-9) {
                if (o < lo || o > hi) {
                    return -1.0;
                }
                continue;
            }
            double t1 = (lo - o) / d;
            double t2 = (hi - o) / d;
            if (t1 > t2) {
                double s = t1;
                t1 = t2;
                t2 = s;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }
        return tMin;
    }

    public static boolean hits(double[] eyes, double[] dir, double[] box, double range) {
        return hitDistance(eyes, dir, box, range) >= 0.0;
    }

    /** The point of the box nearest to {@code p}. */
    public static double[] clamp(double[] box, double[] p) {
        return new double[]{
                Math.max(box[0], Math.min(box[3], p[0])),
                Math.max(box[1], Math.min(box[4], p[1])),
                Math.max(box[2], Math.min(box[5], p[2]))};
    }

    /** Distance from the eyes to the nearest point of the box (0 inside it). */
    public static double distance(double[] eyes, double[] box) {
        double[] c = clamp(box, eyes);
        double dx = c[0] - eyes[0];
        double dy = c[1] - eyes[1];
        double dz = c[2] - eyes[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The point of the box closest to the ray: the spot the current look
     * reaches with the least turn. The distance from a point moving along a
     * line to a convex box is convex, so a ternary search finds it.
     */
    public static double[] nearestToRay(double[] eyes, double[] dir, double[] box, double range) {
        double lo = 0.0;
        double hi = Math.max(range, 0.01);
        for (int i = 0; i < 60; i++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;
            if (gap(eyes, dir, box, m1) <= gap(eyes, dir, box, m2)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double t = (lo + hi) / 2.0;
        return clamp(box, along(eyes, dir, t));
    }

    private static double gap(double[] eyes, double[] dir, double[] box, double t) {
        double[] p = along(eyes, dir, t);
        double[] c = clamp(box, p);
        double dx = c[0] - p[0];
        double dy = c[1] - p[1];
        double dz = c[2] - p[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static double[] along(double[] eyes, double[] dir, double t) {
        return new double[]{eyes[0] + dir[0] * t, eyes[1] + dir[1] * t, eyes[2] + dir[2] * t};
    }

    /** The box grown by {@code amount} on every side (negative shrinks; never past its centre). */
    public static double[] grow(double[] box, double amount) {
        double[] out = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            double lo = box[axis] - amount;
            double hi = box[axis + 3] + amount;
            if (lo > hi) {
                double mid = (box[axis] + box[axis + 3]) / 2.0;
                lo = mid;
                hi = mid;
            }
            out[axis] = lo;
            out[axis + 3] = hi;
        }
        return out;
    }

    /** The box with each side moved in by {@code fraction} of its size on that axis. */
    public static double[] trim(double[] box, double fraction) {
        double[] out = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            double size = box[axis + 3] - box[axis];
            out[axis] = box[axis] + size * fraction;
            out[axis + 3] = box[axis + 3] - size * fraction;
        }
        return out;
    }

    /** Smallest signed difference from {@code from} to {@code to}, in (-180, 180]. */
    public static float wrap(float from, float to) {
        float d = (to - from) % 360.0F;
        if (d > 180.0F) {
            d -= 360.0F;
        } else if (d <= -180.0F) {
            d += 360.0F;
        }
        return d;
    }
}
