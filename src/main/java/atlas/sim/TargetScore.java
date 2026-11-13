package atlas.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * How good the forecasts of other players are on real players (2026-11-13): each forecast {@link TargetTrack} makes
 * is kept for a few ticks and held against the positions the server then sends, next to the straight line through
 * the last two positions, which is what the modules use. The sums say whether the simulation is the better of the
 * two a tick or five ahead -- the question to answer before any module is switched over.
 *
 * Only forecasts the simulation made are compared (one that fell back to the line is the line); how many of all
 * forecasts those were is counted beside. The truth is a cell of 1/32, so an error under 0.02 is as good as none.
 * No game types.
 */
public final class TargetScore {
    /** How many ticks ahead a forecast is held against what came. */
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
    /** Per ticks ahead: the forecast's error sum, the line's, the count. */
    private final double[][] sums = new double[AHEAD + 1][3];
    private int forecasts;
    private int simulated;

    /**
     * A position of player {@code id} arrived.
     *
     * @param tick     this client's tick count
     * @param x,z      the position, level (the middle of the cell the server named)
     * @param forecast the forecast made from this position, index 0 being the position; null for none
     * @param perTickX,perTickZ the straight line's speed from the position before to this one, blocks a tick; NaN
     *                 when there was none before
     */
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

    /** A player gone: its open forecasts can never be held against anything. */
    public void forget(int id) {
        for (Iterator<Made> it = this.open.iterator(); it.hasNext(); ) {
            if (it.next().id == id) {
                it.remove();
            }
        }
    }

    /** Forgets the open forecasts (a new world); the sums stay. */
    public void clearOpen() {
        this.open.clear();
    }

    /** Starts the sums again. */
    public void reset() {
        this.open.clear();
        for (double[] sum : this.sums) {
            sum[0] = sum[1] = sum[2] = 0.0;
        }
        this.forecasts = 0;
        this.simulated = 0;
    }

    /** How many comparisons were made, that many ticks ahead. */
    public int count(int ahead) {
        return (int) this.sums[ahead][2];
    }

    /** The simulation's mean error that many ticks ahead, blocks; NaN with nothing measured. */
    public double engine(int ahead) {
        return this.sums[ahead][2] == 0.0 ? Double.NaN : this.sums[ahead][0] / this.sums[ahead][2];
    }

    /** The straight line's, on the same forecasts. */
    public double line(int ahead) {
        return this.sums[ahead][2] == 0.0 ? Double.NaN : this.sums[ahead][1] / this.sums[ahead][2];
    }

    /** E.g. "2t: 0.031 vs line 0.052 (n 40) | 4t: ... ; simulated 85% of 200 forecasts". */
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
