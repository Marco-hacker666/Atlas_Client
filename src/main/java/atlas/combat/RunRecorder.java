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
 *
 * 2026-11-02 (review): a prediction starts from the position SpeedBlocker plans from -- the last server update
 * moved on by the velocity (RunTracker.now) -- not the raw one, and is checked only on a tick a fresh update came
 * (the raw position is exact then, not up to a tick old), at the first such tick at or past each horizon; the row's
 * horizon is the ticks that really passed. Sampled every SAMPLE_EVERY = 10 ticks (was 5): a busy fight filled the
 * collector's buffer in under a minute.
 *
 * 2026-11-13: a third prediction beside the two, the movement simulation's (atlas.sim.TargetTrack through
 * SimTargets), in the last column, engineErr. Empty when the simulation had no answer of its own for the player.
 */
public final class RunRecorder {
    public static final int[] HORIZONS = {5, 10, 15, 20};
    static final int SAMPLE_EVERY = 10;
    static final int MAX_ROWS = 50000;
    public static final String HEADER =
            "tick,player,horizon,dist,speed,approach,keyOffset,chasing,ground,sprint,modelErr,lineErr,engineErr";
    /** How many ticks ahead a prediction has to reach: a few past the last horizon. */
    public static final int LENGTH = HORIZONS[HORIZONS.length - 1] + 3;

    /**
     * A row as the header has it now: one written before a column was added (kept across a launch) gets the empty
     * cells it lacks, so every row sent under one header has its columns (review, PR #41).
     */
    public static String padded(String row) {
        int want = HEADER.split(",").length;
        int have = row.split(",", -1).length;
        StringBuilder sb = new StringBuilder(row);
        for (int i = have; i < want; i++) {
            sb.append(',');
        }
        return sb.toString();
    }

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
        /** The simulation's positions {x, z}, tick by tick from the sample on; null for none. */
        final double[][] engine;
        int next;

        Sample(int id, String name, int tick, double[][] model, double x0, double z0, double vx, double vz,
               String features, double[][] engine) {
            this.id = id;
            this.name = name;
            this.tick = tick;
            this.model = model;
            this.x0 = x0;
            this.z0 = z0;
            this.vx = vx;
            this.vz = vz;
            this.features = features;
            this.engine = engine;
        }
    }

    private final Consumer<String> sink;
    private final List<Sample> open = new ArrayList<Sample>();
    /** Per horizon: model error sum, line error sum, count; the simulation's error sum and its own count. */
    private final double[][] sums = new double[HORIZONS.length][5];
    private int rows;

    public RunRecorder(Consumer<String> sink) {
        this.sink = sink;
    }

    /**
     * One tick of one player: settles its predictions on a fresh update, and every SAMPLE_EVERY ticks makes a new one
     * when it moves.
     *
     * @param startX,startZ   where the server has it now as SpeedBlocker reckons it (last update + velocity)
     * @param actualX,actualZ the last position the server sent
     * @param fresh           that position arrived this tick
     * @param moveX,moveZ     its velocity, blocks a tick; NaN when unknown
     */
    public void observe(int tick, int id, String name, double startX, double startZ, double actualX, double actualZ,
                        boolean fresh, double moveX, double moveZ, double lookYaw, double playerX, double playerZ,
                        boolean onGround, boolean sprinting) {
        observe(tick, id, name, startX, startZ, actualX, actualZ, fresh, moveX, moveZ, lookYaw, playerX, playerZ,
                onGround, sprinting, null);
    }

    /** True on the ticks a new prediction is made: the only ticks the simulation's path is asked for. */
    public boolean samples(int tick) {
        return tick % SAMPLE_EVERY == 0;
    }

    /**
     * As above, with the simulation's answer.
     *
     * @param engine where the movement simulation has the player {x, z} one tick from now, two, ... (at least
     *               {@link #LENGTH}), or null when it has no answer; read only on the ticks {@link #samples} says
     */
    public void observe(int tick, int id, String name, double startX, double startZ, double actualX, double actualZ,
                        boolean fresh, double moveX, double moveZ, double lookYaw, double playerX, double playerZ,
                        boolean onGround, boolean sprinting, double[][] engine) {
        if (fresh) {
            for (Iterator<Sample> it = this.open.iterator(); it.hasNext(); ) {
                Sample s = it.next();
                if (s.id != id) {
                    continue;
                }
                int elapsed = tick - s.tick;
                boolean written = false;
                while (s.next < HORIZONS.length && elapsed >= HORIZONS[s.next]) {
                    if (!written && elapsed <= s.model.length) {
                        written = true;
                        double[] predicted = s.model[elapsed - 1];
                        double modelErr = Math.hypot(predicted[0] - actualX, predicted[1] - actualZ);
                        double lineErr = Math.hypot(s.x0 + s.vx * elapsed - actualX, s.z0 + s.vz * elapsed - actualZ);
                        this.sums[s.next][0] += modelErr;
                        this.sums[s.next][1] += lineErr;
                        this.sums[s.next][2]++;
                        String engineCell = "";
                        if (s.engine != null && elapsed <= s.engine.length) {
                            double engineErr = Math.hypot(s.engine[elapsed - 1][0] - actualX,
                                    s.engine[elapsed - 1][1] - actualZ);
                            this.sums[s.next][3] += engineErr;
                            this.sums[s.next][4]++;
                            engineCell = String.format(Locale.ROOT, "%.3f", engineErr);
                        }
                        if (this.rows < MAX_ROWS) {
                            this.rows++;
                            this.sink.accept(String.format(Locale.ROOT, "%d,%s,%d,%s,%.3f,%.3f,%s", s.tick, s.name,
                                    elapsed, s.features, modelErr, lineErr, engineCell));
                        }
                    }
                    s.next++;
                }
                if (s.next >= HORIZONS.length) {
                    it.remove();
                }
            }
        }
        if (tick % SAMPLE_EVERY != 0 || Double.isNaN(moveX) || Double.isNaN(moveZ)) {
            return;
        }
        double speed = SpeedblockPlan.speed(moveX, moveZ);
        if (speed < 0.05) {
            return;
        }
        /* A few ticks past the last horizon: the fresh update that settles it may come a tick or two after. */
        int length = LENGTH;
        double[][] model = SpeedblockPlan.simulate(startX, startZ, moveX, moveZ, lookYaw, playerX, playerZ, onGround,
                sprinting, length);
        double dx = playerX - startX;
        double dz = playerZ - startZ;
        double toUs = SpeedblockPlan.yawOf(dx, dz);
        boolean chasing = !Double.isNaN(lookYaw)
                && Math.abs(SpeedblockPlan.wrap(toUs - lookYaw)) < SpeedblockPlan.CHASE_CONE;
        String features = String.format(Locale.ROOT, "%.2f,%.3f,%.3f,%.0f,%d,%d,%d", Math.hypot(dx, dz), speed,
                SpeedblockPlan.approach(moveX, moveZ, dx, dz),
                SpeedblockPlan.keyOffset(SpeedblockPlan.yawOf(moveX, moveZ), lookYaw),
                chasing ? 1 : 0, onGround ? 1 : 0, sprinting ? 1 : 0);
        this.open.add(new Sample(id, name.replace(',', '_'), tick, model, startX, startZ, moveX, moveZ, features,
                engine));
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
        this.open.removeIf(s -> tick - s.tick > last + 3);
    }

    /** Settled predictions so far, all horizons. */
    public int settled() {
        int n = 0;
        for (double[] sum : this.sums) {
            n += (int) sum[2];
        }
        return n;
    }

    /**
     * Mean errors per horizon, model against straight line, e.g. "10t: 0.42 vs 0.61 (n 120)"; where the simulation
     * answered too, ", engine 0.38 (n 90)" after it (its mean is over its own rows only).
     */
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
            if (this.sums[i][4] > 0) {
                sb.append(String.format(Locale.ROOT, ", engine %.2f (n %d)", this.sums[i][3] / this.sums[i][4],
                        (int) this.sums[i][4]));
            }
        }
        return sb.length() == 0 ? "nothing settled yet" : sb.toString();
    }
}
