package atlas.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Other players' velocity from the positions the server sends (2026-11-01). A server sends a player's position
 * every second tick (the entity tracker's interval for players), so a one-tick difference is 0, then twice the
 * speed. Velocity is the change between two updates over the ticks between them, smoothed (0.65 new, 0.35 old),
 * kept between updates, and dropped after 3 ticks without one (standing) or on a jump over 1.5 blocks a tick (a
 * teleport). The same rule as SpeedBlocker's own tracker, without game types, for RunCollector.
 */
public final class RunTracker {
    /** id -> {x, z, tick of that update, last tick seen}. */
    private final Map<Integer, double[]> last = new HashMap<Integer, double[]>();
    private final Map<Integer, double[]> velocity = new HashMap<Integer, double[]>();

    /** One tick of one player at the server position {@code x, z}. */
    public void observe(int tick, int id, double x, double z) {
        double[] seen = this.last.get(id);
        if (seen == null || tick - (int) seen[3] > 1) {
            this.velocity.remove(id);
            this.last.put(id, new double[]{x, z, tick, tick});
            return;
        }
        seen[3] = tick;
        if (x == seen[0] && z == seen[1]) {
            if (tick - (int) seen[2] > 3) {
                this.velocity.remove(id);
            }
            return;
        }
        int dt = Math.max(1, tick - (int) seen[2]);
        double vx = (x - seen[0]) / dt;
        double vz = (z - seen[1]) / dt;
        if (vx * vx + vz * vz > 1.5 * 1.5) {
            this.velocity.remove(id);
        } else {
            double[] previous = this.velocity.get(id);
            if (previous != null) {
                vx = vx * 0.65 + previous[0] * 0.35;
                vz = vz * 0.65 + previous[1] * 0.35;
            }
            this.velocity.put(id, new double[]{vx, vz});
        }
        seen[0] = x;
        seen[1] = z;
        seen[2] = tick;
    }

    /** {vx, vz} blocks a tick, or null when not known (just seen, standing, teleported). */
    public double[] velocity(int id) {
        return this.velocity.get(id);
    }

    /** A new position from the server arrived on this tick: the raw position is exact now, not a tick old. */
    public boolean fresh(int tick, int id) {
        double[] seen = this.last.get(id);
        return seen != null && (int) seen[2] == tick;
    }

    /**
     * Where the server has it now: the last update moved on by the velocity for the ticks since (at most 3) -- the
     * position SpeedBlocker plans from. {rawX, rawZ} when the velocity is not known.
     */
    public double[] now(int tick, int id, double rawX, double rawZ) {
        double[] seen = this.last.get(id);
        double[] v = this.velocity.get(id);
        if (seen == null || v == null) {
            return new double[]{rawX, rawZ};
        }
        int since = Math.max(0, Math.min(3, tick - (int) seen[2]));
        return new double[]{rawX + v[0] * since, rawZ + v[1] * since};
    }

    /** Forgets every player not in {@code alive}. */
    public void retain(Set<Integer> alive) {
        this.last.keySet().retainAll(alive);
        this.velocity.keySet().retainAll(alive);
    }

    public void clear() {
        this.last.clear();
        this.velocity.clear();
    }
}
