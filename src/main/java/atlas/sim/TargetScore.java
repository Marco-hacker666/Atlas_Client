package atlas.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class TargetScore {
    public static final int AHEAD = 5;

    private static final class Made {
        final int id;
        final int tick;
        final double[] x;
        final double[] z;
        final double fromX;
        final double fromZ;
        final double perTickX;
        final double perTickZ;

        Made(int id, int tick, TargetForecast forecast, double perTickX, double perTickZ) {
            this.id = id;
            this.tick = tick;
            this.x = forecast.x.clone();
            this.z = forecast.z.clone();
            this.fromX = forecast.x[0];
            this.fromZ = forecast.z[0];
            this.perTickX = perTickX;
            this.perTickZ = perTickZ;
        }
    }

    private final List<Made> open = new ArrayList<Made>();

    private final double[][] sums = new double[AHEAD + 1][3];
    private int forecasts;
    private int simulated;

    public void position(int id, int tick, double x, double z, TargetForecast forecast, double perTickX,
                         double perTickZ) {
        for (Iterator<Made> it = this.open.iterator(); it.hasNext(); ) {
            Made made = it.next();
            int ahead = tick - made.tick;
            if (ahead > AHEAD) {
                it.remove();
                continue;
            }
            if (made.id != id || ahead < 1) {
                continue;
            }
            this.sums[ahead][0] += Math.hypot(made.x[ahead] - x, made.z[ahead] - z);
            this.sums[ahead][1] += Math.hypot(made.fromX + made.perTickX * (double) ahead - x,
                    made.fromZ + made.perTickZ * (double) ahead - z);
            this.sums[ahead][2]++;
            if (ahead == AHEAD) {
                it.remove();
            }
        }
        if (forecast == null || Double.isNaN(perTickX) || Double.isNaN(perTickZ)) {
            return;
        }
        this.forecasts++;
        if (!forecast.simulated || forecast.moves() < AHEAD) {
            return;
        }
        this.simulated++;
        this.open.add(new Made(id, tick, forecast, perTickX, perTickZ));
    }

    public void forget(int id) {
        for (Iterator<Made> it = this.open.iterator(); it.hasNext(); ) {
            if (it.next().id == id) {
                it.remove();
            }
        }
    }

    public void clearOpen() {
        this.open.clear();
    }

    public void reset() {
        this.open.clear();
        for (double[] sum : this.sums) {
            sum[0] = sum[1] = sum[2] = 0.0;
        }
        this.forecasts = 0;
        this.simulated = 0;
    }

    public int count(int ahead) {
        return (int) this.sums[ahead][2];
    }

    public double engine(int ahead) {
        return this.sums[ahead][2] == 0.0 ? Double.NaN : this.sums[ahead][0] / this.sums[ahead][2];
    }

    public double line(int ahead) {
        return this.sums[ahead][2] == 0.0 ? Double.NaN : this.sums[ahead][1] / this.sums[ahead][2];
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int ahead = 1; ahead <= AHEAD; ahead++) {
            if (count(ahead) == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(String.format(Locale.ROOT, "%dt: %.3f vs line %.3f (n %d)", ahead, engine(ahead), line(ahead),
                    count(ahead)));
        }
        if (this.forecasts == 0) {
            return "nothing measured yet";
        }
        if (sb.length() == 0) {
            sb.append("nothing compared yet");
        }
        sb.append(String.format(Locale.ROOT, "; simulated %d%% of %d forecasts",
                Math.round(100.0 * (double) this.simulated / (double) this.forecasts), this.forecasts));
        return sb.toString();
    }
}
