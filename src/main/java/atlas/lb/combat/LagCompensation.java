package atlas.lb.combat;

public final class LagCompensation {
    private LagCompensation() {
    }

    public static double leadTicks(int roundTripMs, int maxLeadTicks) {
        if (roundTripMs <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(maxLeadTicks, roundTripMs / 50.0 + 1.0));
    }

    public static double[] predict(double[] serverPos, double[] velocityPerTick, double leadTicks) {
        return new double[]{
                serverPos[0] + velocityPerTick[0] * leadTicks,
                serverPos[1],
                serverPos[2] + velocityPerTick[2] * leadTicks};
    }

    public static double[] offset(double[] box, double dx, double dy, double dz) {
        return new double[]{box[0] + dx, box[1] + dy, box[2] + dz, box[3] + dx, box[4] + dy, box[5] + dz};
    }

    public static double[] overlap(double[] a, double[] b) {
        double minX = Math.max(a[0], b[0]);
        double minY = Math.max(a[1], b[1]);
        double minZ = Math.max(a[2], b[2]);
        double maxX = Math.min(a[3], b[3]);
        double maxY = Math.min(a[4], b[4]);
        double maxZ = Math.min(a[5], b[5]);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    public static double distanceToBox(double[] p, double[] box) {
        double dx = Math.max(0.0, Math.max(box[0] - p[0], p[0] - box[3]));
        double dy = Math.max(0.0, Math.max(box[1] - p[1], p[1] - box[4]));
        double dz = Math.max(0.0, Math.max(box[2] - p[2], p[2] - box[5]));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double margin(int roundTripMs, double perHundredMs) {
        return roundTripMs <= 0 ? 0.0 : Math.max(0.0, roundTripMs / 100.0 * perHundredMs);
    }

    public static boolean bothInReach(double[] eyes, double[] shown, double[] predicted, double range, double margin) {
        return distanceToBox(eyes, shown) <= range && distanceToBox(eyes, predicted) <= range - margin;
    }

    public static double[] velocity(double[][] samples) {
        if (samples == null || samples.length < 2) {
            return new double[]{0.0, 0.0, 0.0};
        }
        double[] first = samples[0];
        double[] last = samples[samples.length - 1];
        double ticks = last[0] - first[0];
        if (ticks <= 0.0) {
            return new double[]{0.0, 0.0, 0.0};
        }
        return new double[]{(last[1] - first[1]) / ticks, (last[2] - first[2]) / ticks, (last[3] - first[3]) / ticks};
    }
}
