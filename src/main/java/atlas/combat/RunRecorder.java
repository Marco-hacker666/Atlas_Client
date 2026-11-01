package atlas.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * How good SpeedBlocker's prediction of a run is, measured on real players (2026-11-01, owner: "add the recorder",
 * before deciding whether a learned model is worth it).
 *
 * Every SAMPLE_EVERY ticks each moving player gets a prediction from SpeedblockPlan.simulate (the model) and one
 * from a straight line at its current speed (the baseline). When each horizon in HORIZONS has passed, both are
 * compared with where the server then had the player, and a row goes to the sink:
 *
 *   tick,player,horizon,dist,speed,approach,keyOffset,chasing,ground,sprint,modelErr,lineErr
 *
 * dist, speed, approach (blocks, blocks a tick) and keyOffset (degrees between where it ran and where it looked),
 * chasing (looking within the chase cone of us), ground and sprint are the features at the moment of the prediction;
 * the errors are the distance in blocks between predicted and actual feet. The sums give a summary: the model's mean
 * error against the straight line's, per horizon -- the number that says whether the model helps and where it
 * misses. No game types: the module passes plain numbers.
 */
public final class RunRecorder {
    public static final int[] HORIZONS = {5, 10, 15, 20};
    static final int SAMPLE_EVERY = 5;
    static final int MAX_ROWS = 50000;
    public static final String HEADER =
            "tick,player,horizon,dist,speed,approach,keyOffset,chasing,ground,sprint,modelErr,lineErr";

    private static final class Sample {
        final int id;
        final String name;
        final int tick;
        final double[][] model;
        final double x0;
        final double z0;
        final double vx;
        final double vz;
        final String features;
        int next;

        Sample(int id, String name, int tick, double[][] model, double x0, double z0, double vx, double vz,
               String features) {
            this.id = id;
            this.name = name;
            this.tick = tick;
            this.model = model;
            this.x0 = x0;
            this.z0 = z0;
            this.vx = vx;
            this.vz = vz;
            this.features = features;
        }
    }

    private final Consumer<String> sink;
    private final List<Sample> open = new ArrayList<Sample>();
    /** Per horizon: model error sum, line error sum, count. */
    private final double[][] sums = new double[HORIZONS.length][3];
    private int rows;

    public RunRecorder(Consumer<String> sink) {
        this.sink = sink;
    }

    /**
     * One tick of one player: settles its predictions whose horizon is now, and every SAMPLE_EVERY ticks makes a new
     * one when it moves.
     *
     * @param x,z        where the server has it now
     * @param moveX,moveZ its velocity, blocks a tick; NaN when unknown
     */
    public void observe(int tick, int id, String name, double x, double z, double moveX, double moveZ,
                        double lookYaw, double playerX, double playerZ, boolean onGround, boolean sprinting) {
        for (Iterator<Sample> it = this.open.iterator(); it.hasNext(); ) {
            Sample s = it.next();
            if (s.id != id) {
                continue;
            }
            while (s.next < HORIZONS.length && tick - s.tick >= HORIZONS[s.next]) {
                int h = HORIZONS[s.next];
                if (tick - s.tick == h && h <= s.model.length) {
                    double[] predicted = s.model[h - 1];
                    double modelErr = Math.hypot(predicted[0] - x, predicted[1] - z);
                    double lineErr = Math.hypot(s.x0 + s.vx * h - x, s.z0 + s.vz * h - z);
                    this.sums[s.next][0] += modelErr;
                    this.sums[s.next][1] += lineErr;
                    this.sums[s.next][2]++;
                    if (this.rows < MAX_ROWS) {
                        this.rows++;
                        this.sink.accept(String.format(Locale.ROOT, "%d,%s,%d,%s,%.3f,%.3f", s.tick, s.name, h,
                                s.features, modelErr, lineErr));
                    }
                }
                s.next++;
            }
            if (s.next >= HORIZONS.length) {
                it.remove();
            }
        }
        if (tick % SAMPLE_EVERY != 0 || Double.isNaN(moveX) || Double.isNaN(moveZ)) {
            return;
        }
        double speed = SpeedblockPlan.speed(moveX, moveZ);
        if (speed < 0.05) {
            return;
        }
        int last = HORIZONS[HORIZONS.length - 1];
        double[][] model = SpeedblockPlan.simulate(x, z, moveX, moveZ, lookYaw, playerX, playerZ, onGround, sprinting,
                last);
        double dx = playerX - x;
        double dz = playerZ - z;
        double toUs = SpeedblockPlan.yawOf(dx, dz);
        boolean chasing = !Double.isNaN(lookYaw)
                && Math.abs(SpeedblockPlan.wrap(toUs - lookYaw)) < SpeedblockPlan.CHASE_CONE;
        String features = String.format(Locale.ROOT, "%.2f,%.3f,%.3f,%.0f,%d,%d,%d", Math.hypot(dx, dz), speed,
                SpeedblockPlan.approach(moveX, moveZ, dx, dz),
                SpeedblockPlan.keyOffset(SpeedblockPlan.yawOf(moveX, moveZ), lookYaw),
                chasing ? 1 : 0, onGround ? 1 : 0, sprinting ? 1 : 0);
        this.open.add(new Sample(id, name.replace(',', '_'), tick, model, x, z, moveX, moveZ, features));
    }

    /** A player gone from the world: its open predictions can never be settled. */
    public void forget(int id) {
        this.open.removeIf(s -> s.id == id);
    }

    public void clearOpen() {
        this.open.clear();
    }

    /** Drops predictions past their last horizon: a player that left (or stopped being seen) never settles them. */
    public void prune(int tick) {
        int last = HORIZONS[HORIZONS.length - 1];
        this.open.removeIf(s -> tick - s.tick > last + 2);
    }

    /** Settled predictions so far, all horizons. */
    public int settled() {
        int n = 0;
        for (double[] sum : this.sums) {
            n += (int) sum[2];
        }
        return n;
    }

    /** Mean errors per horizon, model against straight line, e.g. "10t: 0.42 vs 0.61 (n 120)". */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HORIZONS.length; i++) {
            double n = this.sums[i][2];
            if (n == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(String.format(Locale.ROOT, "%dt: %.2f vs %.2f (n %d)", HORIZONS[i], this.sums[i][0] / n,
                    this.sums[i][1] / n, (int) n));
        }
        return sb.length() == 0 ? "nothing settled yet" : sb.toString();
    }
}
