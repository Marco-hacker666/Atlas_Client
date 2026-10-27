package atlas.lb.combat;

/**
 * When Silent Aura's Rhythm needs the sword up (2026-10-27, owner: "not stiff
 * all the time -- hold it when it is needed"): only while an enemy can land a
 * hit now. Its look, from its eyes and reaching as far as its hit can, meets
 * our body (grown by a tolerance for aim and the delay of what we see), and --
 * for a player -- it has been clicking: its arm swung within the last few
 * ticks. A player walking toward us, looking past us, or not clicking is not
 * a hit about to land.
 *
 * Boxes are {minX, minY, minZ, maxX, maxY, maxZ}. Plain arithmetic, tested on
 * its own.
 */
public final class RhythmDanger {
    /** No swing seen yet. */
    public static final int NEVER = Integer.MIN_VALUE;

    private RhythmDanger() {
    }

    /** {@code box} grown by {@code by} on every side. */
    public static double[] expand(double[] box, double by) {
        return new double[]{box[0] - by, box[1] - by, box[2] - by, box[3] + by, box[4] + by, box[5] + by};
    }

    /**
     * Whether the ray from {@code eyes} along the unit vector {@code look},
     * {@code reach} long, meets {@code box} (or starts inside it).
     */
    public static boolean lookHits(double[] eyes, double[] look, double reach, double[] box) {
        double near = 0.0;
        double far = reach;
        for (int axis = 0; axis < 3; axis++) {
            double min = box[axis];
            double max = box[axis + 3];
            if (Math.abs(look[axis]) < 1.0E-9) {
                if (eyes[axis] < min || eyes[axis] > max) {
                    return false;
                }
                continue;
            }
            double a = (min - eyes[axis]) / look[axis];
            double b = (max - eyes[axis]) / look[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) {
                return false;
            }
        }
        return true;
    }

    /** The unit look vector of a yaw and pitch in degrees (Minecraft's: yaw 0 looks along +Z). */
    public static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(-yaw) - Math.PI;
        double p = Math.toRadians(-pitch);
        double cosPitch = -Math.cos(p);
        return new double[]{Math.sin(y) * cosPitch, Math.sin(p), Math.cos(y) * cosPitch};
    }

    /** It swung within the last {@code memory} ticks. */
    public static boolean clicking(int lastSwingTick, int now, int memory) {
        return lastSwingTick != NEVER && now - lastSwingTick <= memory;
    }
}
