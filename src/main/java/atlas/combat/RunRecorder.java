package atlas.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class RunRecorder {
    public static final int[] HORIZONS = {5, 10, 15, 20};
    static final int SAMPLE_EVERY = 10;
    static final int MAX_ROWS = 50000;
    public static final String HEADER =
            "tick,player,horizon,dist,speed,approach,keyOffset,chasing,ground,sprint,modelErr,lineErr,engineErr";

    public static final int LENGTH = HORIZONS[HORIZONS.length - 1] + 3;

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

    private final double[][] sums = new double[HORIZONS.length][5];
    private int rows;

    public RunRecorder(Consumer<String> sink) {
        this.sink = sink;
    }

    public void observe(int tick, int id, String name, double startX, double startZ, double actualX, double actualZ,
                        boolean fresh, double moveX, double moveZ, double lookYaw, double playerX, double playerZ,
                        boolean onGround, boolean sprinting) {
        observe(tick, id, name, startX, startZ, actualX, actualZ, fresh, moveX, moveZ, lookYaw, playerX, playerZ,
                onGround, sprinting, null);
    }

    public boolean samples(int tick) {
        return tick % SAMPLE_EVERY == 0;
    }

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

    public void forget(int id) {
        this.open.removeIf(s -> s.id == id);
    }

    public void clearOpen() {
        this.open.clear();
    }

    public void prune(int tick) {
        int last = HORIZONS[HORIZONS.length - 1];
        this.open.removeIf(s -> tick - s.tick > last + 3);
    }

    public int settled() {
        int n = 0;
        for (double[] sum : this.sums) {
            n += (int) sum[2];
        }
        return n;
    }

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
