package atlas.util;

import java.util.Random;

public final class HumanClicks {
    private static final double SIGMA = 0.22;
    private static final double REVERT_PER_SECOND = 1.6;
    private static final double WANDER_PER_SQRT_SECOND = 1.1;
    private static final long FIGHT_GAP_MS = 1500L;
    private static final long BURST_MS = 1500L;

    private final Random random;
    private double cps = -1.0;
    private double target;
    private long targetUntil;
    private long lastAt = Long.MIN_VALUE;
    private long fightStart;
    private long lastGap = -1L;

    public HumanClicks() {
        this(new Random());
    }

    public HumanClicks(Random random) {
        this.random = random;
    }

    public void reset() {
        this.cps = -1.0;
        this.lastAt = Long.MIN_VALUE;
        this.lastGap = -1L;
    }

    public double cps() {
        return this.cps;
    }

    public long next(int minCps, int maxCps, long now) {
        double min = Math.max(1, Math.min(minCps, maxCps));
        double max = Math.max(min, Math.max(minCps, maxCps));
        boolean newFight = this.lastAt == Long.MIN_VALUE || now - this.lastAt > FIGHT_GAP_MS;
        if (newFight || this.cps < 0.0) {
            this.fightStart = now;
            this.cps = max - this.random.nextDouble() * 0.25 * (max - min);
            this.targetUntil = 0L;
            this.lastGap = -1L;
        }
        if (now >= this.targetUntil) {
            this.target = min + this.random.nextDouble() * (max - min);
            this.targetUntil = now + 2000L + (long) (this.random.nextDouble() * 4000L);
        }
        double goal = now - this.fightStart < BURST_MS ? max - 0.15 * (max - min) : this.target;
        double dt = this.lastAt == Long.MIN_VALUE || newFight ? 0.05 : Math.min(1.0, (now - this.lastAt) / 1000.0);
        this.cps += REVERT_PER_SECOND * (goal - this.cps) * dt
                + WANDER_PER_SQRT_SECOND * Math.sqrt(dt) * this.random.nextGaussian();
        this.cps = Math.max(min, Math.min(max + 0.5, this.cps));
        this.lastAt = now;

        long floor = (long) Math.ceil(1000.0 / (max + 2.0));
        long gap;
        if (this.lastGap > 0L && this.random.nextDouble() < 0.15) {
            gap = this.lastGap + (long) Math.round((this.random.nextDouble() - 0.5) * 8.0);
        } else {
            double mu = Math.log(1000.0 / this.cps) - SIGMA * SIGMA / 2.0;
            gap = Math.round(Math.exp(mu + SIGMA * this.random.nextGaussian()));
        }
        if (this.random.nextDouble() < 0.015) {
            gap += 50L + (long) (this.random.nextDouble() * 100.0);
        }
        gap = Math.max(floor, Math.min(1000L, gap));
        this.lastGap = gap;
        return gap;
    }
}
