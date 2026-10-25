package atlas.lb.aiming;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Which point of a target's box to aim at (2026-10-24, LiquidBounce NextGen
 * port, GPL-3.0; aiming/point/PointTracker.kt, its exempts and processors,
 * and projectPointsOnBox / PlaneSection.castPointsOnUniformly).
 *
 * Points are cast onto the box uniformly as seen from the eyes; the nearest
 * one not exempted (head / body / feet parts, the area around the nearest
 * point) is taken, then the Delay, Lazy and Gaussian processors may hold it
 * or offset it. Boxes are {minX, minY, minZ, maxX, maxY, maxZ}; vectors
 * {x, y, z}. No game state: the module passes the predicted, inflated box.
 */
public final class PointTracker {
    public static final int PART_HEAD = 1;
    public static final int PART_BODY = 2;
    public static final int PART_FEET = 4;
    private static final int MAX_POINTS = 128;

    /** ExemptBoxParts, as PART_* flags. */
    public int exemptParts;
    /** ExemptBestHitVector. */
    public boolean exemptBest;
    public float exemptBestVertical = 0.2F;
    public float exemptBestHorizontal = 0.1F;

    public boolean delayOn;
    public int delayMin = 2;
    public int delayMax = 4;
    public boolean lazyOn;
    public float lazyMin = 0.1F;
    public float lazyMax = 0.2F;
    public boolean gaussianOn;
    public float gaussYawMin;
    public float gaussYawMax;
    public float gaussPitchMin;
    public float gaussPitchMax;
    public float gaussChance = 100.0F;
    public float gaussSpeedMin = 0.1F;
    public float gaussSpeedMax = 0.2F;
    public float gaussTolerance = 0.05F;

    private final Random random;
    private final SecureRandom secure = new SecureRandom();
    private double[] delayPoint;
    private int currentDelay = -1;
    private double[] lazyPoint;
    private float currentThreshold = -1.0F;
    private double[] currentOffset = {0, 0, 0};
    private double[] targetOffset = {0, 0, 0};

    public PointTracker() {
        this(new Random());
    }

    PointTracker(Random random) {
        this.random = random;
    }

    /** The point to aim at and the box it belongs to. */
    public static final class Point {
        public final double[] pos;
        public final double[] box;

        public Point(double[] pos, double[] box) {
            this.pos = pos;
            this.box = box;
        }
    }

    public void reset() {
        this.delayPoint = null;
        this.lazyPoint = null;
        this.currentOffset = new double[]{0, 0, 0};
        this.targetOffset = new double[]{0, 0, 0};
    }

    public Point findPoint(double[] eyes, double[] box) {
        List<double[]> points = projectPointsOnBox(eyes, box, MAX_POINTS);
        double[] best = null;
        double[] worst = null;
        if (points != null) {
            for (double[] p : points) {
                if (best == null || dist2(p, eyes) < dist2(best, eyes)) {
                    best = p;
                }
                if (worst == null || dist2(p, eyes) > dist2(worst, eyes)) {
                    worst = p;
                }
            }
        }
        if (best == null) {
            best = nearestPoint(box, eyes);
        }
        double[] pos = null;
        if (points != null) {
            for (double[] p : points) {
                if (exempted(box, best, p)) {
                    continue;
                }
                if (pos == null || dist2(p, eyes) < dist2(pos, eyes)) {
                    pos = p;
                }
            }
        }
        if (pos == null) {
            pos = best;
        }
        double[] point = nearestPoint(box, pos);
        if (this.delayOn) {
            point = delay(point);
        }
        if (this.lazyOn) {
            point = lazy(point);
        }
        if (this.gaussianOn) {
            return gaussian(point, box);
        }
        return new Point(point, box);
    }

    private boolean exempted(double[] box, double[] best, double[] point) {
        double length = (box[4] - box[1]) / 3.0;
        if ((this.exemptParts & PART_HEAD) != 0 && point[1] <= box[4] && point[1] > box[4] - length) {
            return true;
        }
        if ((this.exemptParts & PART_BODY) != 0 && point[1] <= box[4] - length && point[1] >= box[1] + length) {
            return true;
        }
        if ((this.exemptParts & PART_FEET) != 0 && point[1] >= box[1] && point[1] < box[1] + length) {
            return true;
        }
        if (this.exemptBest) {
            double dx = point[0] - best[0];
            double dz = point[2] - best[2];
            return dx * dx + dz * dz < this.exemptBestHorizontal * this.exemptBestHorizontal
                    && Math.abs(point[1] - best[1]) < this.exemptBestVertical;
        }
        return false;
    }

    private double[] delay(double[] point) {
        if (this.currentDelay < 0) {
            this.currentDelay = between(this.delayMin, this.delayMax);
        }
        if (this.delayPoint != null && same(point, this.delayPoint)) {
            return point;
        }
        if (this.delayPoint == null) {
            this.delayPoint = point;
            return point;
        }
        this.currentDelay--;
        if (this.currentDelay > 0) {
            return this.delayPoint;
        }
        double[] held = this.delayPoint;
        this.delayPoint = point;
        this.currentDelay = between(this.delayMin, this.delayMax);
        return held;
    }

    private double[] lazy(double[] point) {
        if (this.currentThreshold < 0.0F) {
            this.currentThreshold = betweenF(this.lazyMin, this.lazyMax);
        }
        if (this.lazyPoint == null) {
            this.lazyPoint = point;
            return point;
        }
        if (dist2(point, this.lazyPoint) < this.currentThreshold * this.currentThreshold) {
            return this.lazyPoint;
        }
        this.lazyPoint = point;
        this.currentThreshold = betweenF(this.lazyMin, this.lazyMax);
        return point;
    }

    /*
     * LiquidBounce's Gaussian offset. Its "Dynamic" group never applies there
     * (it tests the point, not an entity, for a hurt time), so it is left out.
     */
    private static final double STDDEV_Z = 0.24453708645460387;
    private static final double MEAN_X = 0.00942273861037109;
    private static final double STDDEV_X = 0.23319837528201348;
    private static final double MEAN_Y = -0.30075078007595923;
    private static final double STDDEV_Y = 0.3492437109081718;
    private static final double MEAN_Z = 0.013282929419023442;

    private Point gaussian(double[] point, double[] box) {
        if (betweenF(this.gaussYawMin, this.gaussYawMax) > 0.0F && betweenF(this.gaussPitchMin, this.gaussPitchMax) > 0.0F
                && this.gaussChance > 0.0F) {
            double yawFactor = betweenF(this.gaussYawMin, this.gaussYawMax);
            double pitchFactor = betweenF(this.gaussPitchMin, this.gaussPitchMax);
            boolean arrived = Math.abs(this.currentOffset[0] - this.targetOffset[0]) <= this.gaussTolerance
                    && Math.abs(this.currentOffset[1] - this.targetOffset[1]) <= this.gaussTolerance
                    && Math.abs(this.currentOffset[2] - this.targetOffset[2]) <= this.gaussTolerance;
            if (arrived) {
                if (this.secure.nextFloat() * 100.0F <= this.gaussChance) {
                    this.targetOffset = new double[]{
                            (MEAN_X + this.secure.nextGaussian() * STDDEV_X) * yawFactor,
                            (MEAN_Y + this.secure.nextGaussian() * STDDEV_Y) * pitchFactor,
                            (MEAN_Z + this.secure.nextGaussian() * STDDEV_Z) * yawFactor};
                }
            } else {
                double[] next = new double[3];
                for (int i = 0; i < 3; i++) {
                    double speed = betweenF(this.gaussSpeedMin, this.gaussSpeedMax);
                    next[i] = this.currentOffset[i] + speed * (this.targetOffset[i] - this.currentOffset[i]);
                }
                this.currentOffset = next;
            }
        }
        double[] moved = {point[0] + this.currentOffset[0], point[1] + this.currentOffset[1], point[2] + this.currentOffset[2]};
        double[] movedBox = {box[0] + this.currentOffset[0], box[1] + this.currentOffset[1], box[2] + this.currentOffset[2],
                box[3] + this.currentOffset[0], box[4] + this.currentOffset[1], box[5] + this.currentOffset[2]};
        return new Point(nearestPoint(movedBox, moved), movedBox);
    }

    // ---- geometry -------------------------------------------------------

    /**
     * Points spread uniformly over the box as seen from {@code eye}: a frame
     * between the eye and the box, its corners the box's vertices projected
     * onto it, a grid cast through it and each ray clipped against the box.
     * Null when the eye is inside the box.
     */
    static List<double[]> projectPointsOnBox(double[] eye, double[] box, int maxPoints) {
        if (inside(box, eye)) {
            return null;
        }
        double[] center = {(box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2};
        double[] dir = sub(center, eye);
        double[][] vertices = vertices(box);
        double[] origin = null;
        for (double[] v : vertices) {
            double[] nearest = nearestOnLine(eye, dir, v);
            if (origin == null || dist2(nearest, eye) < dist2(origin, eye)) {
                origin = nearest;
            }
        }
        origin = lerp(origin, eye, 0.1);
        double[] normal = normalize(dir);
        double[] up = {0, 1, 0};
        double[] u = sub(up, scale(normal, dot(up, normal)));
        if (length(u) < 1.0E-6) {
            double[] x = {1, 0, 0};
            u = sub(x, scale(normal, dot(x, normal)));
        }
        u = normalize(u);
        double[] w = cross(normal, u);
        double minY = 0, maxY = 0, minZ = 0, maxZ = 0;
        for (double[] v : vertices) {
            double[] ray = sub(v, eye);
            double denominator = dot(ray, normal);
            if (Math.abs(denominator) < 1.0E-12) {
                continue;
            }
            double t = dot(sub(origin, eye), normal) / denominator;
            double[] onPlane = sub(add(eye, scale(ray, t)), origin);
            double y = dot(onPlane, u);
            double z = dot(onPlane, w);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        double[] pos = add(origin, add(scale(u, minY), scale(w, minZ)));
        double[] dir1 = scale(u, maxY - minY);
        double[] dir2 = scale(w, maxZ - minZ);
        double[] steps = fairSteps(dir1, dir2, maxPoints);
        List<double[]> out = new ArrayList<double[]>();
        for (double y = 0.0; y <= 1.0 + 1.0E-9; y += steps[1]) {
            for (double z = 0.0; z <= 1.0 + 1.0E-9; z += steps[0]) {
                double[] point = add(pos, add(scale(dir1, y), scale(dir2, z)));
                double[] extended = lerp(point, eye, -100.0);
                double[] hit = clip(box, eye, extended);
                if (hit != null) {
                    out.add(hit);
                }
            }
        }
        return out;
    }

    /** {step along dir2, step along dir1}, as PlaneSection.getFairStepSide. */
    private static double[] fairSteps(double[] dir1, double[] dir2, int n) {
        boolean zero1 = length(dir1) < 1.0E-7;
        boolean zero2 = length(dir2) < 1.0E-7;
        if (!zero1 && !zero2) {
            double aspect = length(dir2) / length(dir1);
            return new double[]{Math.sqrt(1.0 / (aspect * n)), Math.sqrt(aspect / n)};
        }
        if (zero1 && zero2) {
            return new double[]{1.0, 1.0};
        }
        return zero1 ? new double[]{1.0, 2.0 / n} : new double[]{2.0 / n, 1.0};
    }

    /** The first point where the segment from {@code a} to {@code b} enters the box, or null. */
    static double[] clip(double[] box, double[] a, double[] b) {
        double tMin = 0.0;
        double tMax = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            double d = b[axis] - a[axis];
            double lo = box[axis];
            double hi = box[axis + 3];
            if (Math.abs(d) < 1.0E-12) {
                if (a[axis] < lo || a[axis] > hi) {
                    return null;
                }
                continue;
            }
            double t1 = (lo - a[axis]) / d;
            double t2 = (hi - a[axis]) / d;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) {
                return null;
            }
        }
        return add(a, scale(sub(b, a), tMin));
    }

    static double[] nearestPoint(double[] box, double[] p) {
        return new double[]{clampD(p[0], box[0], box[3]), clampD(p[1], box[1], box[4]), clampD(p[2], box[2], box[5])};
    }

    static boolean inside(double[] box, double[] p) {
        return p[0] >= box[0] && p[0] < box[3] && p[1] >= box[1] && p[1] < box[4] && p[2] >= box[2] && p[2] < box[5];
    }

    private static double[][] vertices(double[] b) {
        return new double[][]{
                {b[0], b[1], b[2]}, {b[3], b[1], b[2]}, {b[0], b[4], b[2]}, {b[3], b[4], b[2]},
                {b[0], b[1], b[5]}, {b[3], b[1], b[5]}, {b[0], b[4], b[5]}, {b[3], b[4], b[5]}};
    }

    private static double[] nearestOnLine(double[] origin, double[] dir, double[] p) {
        double t = dot(sub(p, origin), dir) / dot(dir, dir);
        return add(origin, scale(dir, t));
    }

    static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean same(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] normalize(double[] a) {
        double l = length(a);
        return l < 1.0E-12 ? new double[]{0, 0, 0} : scale(a, 1.0 / l);
    }

    /** a + (b - a) * t, as Vec3.lerp. */
    private static double[] lerp(double[] a, double[] b, double t) {
        return add(a, scale(sub(b, a), t));
    }

    private static double clampD(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private int between(int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return low + this.random.nextInt(high - low + 1);
    }

    private float betweenF(float min, float max) {
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        return low + this.random.nextFloat() * (high - low);
    }
}
