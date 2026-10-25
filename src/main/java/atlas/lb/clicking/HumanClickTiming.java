package atlas.lb.clicking;

import java.util.List;
import java.util.Random;

/**
 * Log-normal intervals around a CPS drawn once per combo (LiquidBounce NextGen
 * HumanClickTiming): mu is shifted by -sigma^2/2 so the mean interval, not the
 * median, lands on the combo's CPS.
 */
public final class HumanClickTiming implements ClickTiming {
    private static final double SIGMA = 0.45;
    private static final long MIN_INTERVAL_MS = 10L;
    private static final long MAX_INTERVAL_MS = 1000L;

    private double comboCps;
    private int rangeMin = -1;
    private int rangeMax = -1;

    @Override
    public long nextInterval(List<Long> recent, long comboMs, int minCps, int maxCps, Random random) {
        if (comboMs == 0L || minCps != this.rangeMin || maxCps != this.rangeMax) {
            this.comboCps = minCps + random.nextDouble() * (maxCps - minCps);
            this.rangeMin = minCps;
            this.rangeMax = maxCps;
        }
        double mu = Math.log(1000.0 / this.comboCps) - SIGMA * SIGMA / 2.0;
        long interval = Math.round(Math.exp(mu + SIGMA * random.nextGaussian()));
        return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, interval));
    }
}
