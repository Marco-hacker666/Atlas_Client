package atlas.lb.combat;

/**
 * Where a target is on the server when our hit arrives (2026-10-26, owner:
 * "no compromise on high-ping mitigation -- design for it").
 *
 * What the client shows of another player is old: the server sent that
 * position half a round trip ago, and the client still eases toward it. Our
 * attack reaches the server half a round trip from now. A hit that is valid
 * only against the old position -- the target has since walked out of reach
 * on the server -- is exactly the borderline hit a lag-compensating anticheat
 * may cut. So the aura attacks only when the hit holds against BOTH boxes:
 * the one the client shows (what vanilla's ray and a client-view reach check
 * use) and the one the server has by then, predicted from the server's own
 * latest position (Entity.serverPosX) moved on by the target's speed for the
 * round trip; and it aims into the part of the box the two share.
 *
 * Boxes are {minX, minY, minZ, maxX, maxY, maxZ}. Plain arithmetic, tested on
 * its own.
 */
public final class LagCompensation {

    private LagCompensation() {
    }

    /**
     * Ticks to move the server's latest position on: the round trip in ticks
     * plus one for the client's easing, at most {@code maxLeadTicks}.
     */
    public static double leadTicks(int roundTripMs, int maxLeadTicks) {
        if (roundTripMs <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(maxLeadTicks, roundTripMs / 50.0 + 1.0));
    }

    /**
     * The target's position when the hit lands: the server's latest one moved
     * on by its horizontal speed (blocks a tick) for {@code leadTicks}.
     * Height is left at the server's latest (a jump's arc is not a straight
     * line; guessing it costs more than it gains).
     */
    public static double[] predict(double[] serverPos, double[] velocityPerTick, double leadTicks) {
        return new double[]{
                serverPos[0] + velocityPerTick[0] * leadTicks,
                serverPos[1],
                serverPos[2] + velocityPerTick[2] * leadTicks};
    }

    /** {@code box} moved by (dx, dy, dz). */
    public static double[] offset(double[] box, double dx, double dy, double dz) {
        return new double[]{box[0] + dx, box[1] + dy, box[2] + dz, box[3] + dx, box[4] + dy, box[5] + dz};
    }

    /** The box the two share, or null when they do not meet. */
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

    /** Distance from a point to the nearest point of a box (0 inside). */
    public static double distanceToBox(double[] p, double[] box) {
        double dx = Math.max(0.0, Math.max(box[0] - p[0], p[0] - box[3]));
        double dy = Math.max(0.0, Math.max(box[1] - p[1], p[1] - box[4]));
        double dz = Math.max(0.0, Math.max(box[2] - p[2], p[2] - box[5]));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Reach given up for latency: {@code perHundredMs} blocks for every 100 ms of round trip. */
    public static double margin(int roundTripMs, double perHundredMs) {
        return roundTripMs <= 0 ? 0.0 : Math.max(0.0, roundTripMs / 100.0 * perHundredMs);
    }

    /**
     * The hit holds on both sides: the shown box within {@code range} and the
     * predicted one within {@code range - margin}.
     */
    public static boolean bothInReach(double[] eyes, double[] shown, double[] predicted, double range, double margin) {
        return distanceToBox(eyes, shown) <= range && distanceToBox(eyes, predicted) <= range - margin;
    }

    /**
     * A target's speed from its last server positions: {tick, x, y, z}
     * samples, oldest first. The change over the span divided by its ticks;
     * {0,0,0} with fewer than two samples or no time between them.
     */
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
