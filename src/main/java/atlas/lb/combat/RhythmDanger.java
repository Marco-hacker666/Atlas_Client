package atlas.lb.combat;

public final class RhythmDanger {
    public static final int NEVER = Integer.MIN_VALUE;

    private RhythmDanger() {
    }

    public static double[] expand(double[] box, double by) {
        return new double[]{box[0] - by, box[1] - by, box[2] - by, box[3] + by, box[4] + by, box[5] + by};
    }

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

    public static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(-yaw) - Math.PI;
        double p = Math.toRadians(-pitch);
        double cosPitch = -Math.cos(p);
        return new double[]{Math.sin(y) * cosPitch, Math.sin(p), Math.cos(y) * cosPitch};
    }

    public static boolean clicking(int lastSwingTick, int now, int memory) {
        return lastSwingTick != NEVER && now - lastSwingTick <= memory;
    }
}
