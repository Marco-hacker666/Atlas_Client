package atlas.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class RunTracker {
    private final Map<Integer, double[]> last = new HashMap<Integer, double[]>();
    private final Map<Integer, double[]> velocity = new HashMap<Integer, double[]>();

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

    public double[] velocity(int id) {
        return this.velocity.get(id);
    }

    public boolean fresh(int tick, int id) {
        double[] seen = this.last.get(id);
        return seen != null && (int) seen[2] == tick;
    }

    public double[] now(int tick, int id, double rawX, double rawZ) {
        double[] seen = this.last.get(id);
        double[] v = this.velocity.get(id);
        if (seen == null || v == null) {
            return new double[]{rawX, rawZ};
        }
        int since = Math.max(0, Math.min(3, tick - (int) seen[2]));
        return new double[]{rawX + v[0] * since, rawZ + v[1] * since};
    }

    public void retain(Set<Integer> alive) {
        this.last.keySet().retainAll(alive);
        this.velocity.keySet().retainAll(alive);
    }

    public void clear() {
        this.last.clear();
        this.velocity.clear();
    }
}
