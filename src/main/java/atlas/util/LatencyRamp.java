package atlas.util;

import java.util.Random;

public final class LatencyRamp {
    private final Random random;
    private double current;
    private double wander;

    public LatencyRamp(long seed) {
        this.random = new Random(seed);
    }

    public LatencyRamp() {
        this(System.nanoTime());
    }

    public double tick(double target, double rampPerTick, double jitter) {
        if (jitter > 0.0) {
            this.wander += -0.08 * this.wander + this.random.nextGaussian() * jitter * 0.15;
            this.wander = Math.max(-jitter, Math.min(jitter, this.wander));
        } else {
            this.wander = 0.0;
        }
        double goal = target > 0.0 ? Math.max(0.0, target + this.wander) : 0.0;
        double step = Math.max(-rampPerTick, Math.min(rampPerTick, goal - this.current));
        this.current = Math.max(0.0, this.current + step);
        return this.current;
    }

    public double current() {
        return this.current;
    }

    public void reset() {
        this.current = 0.0;
        this.wander = 0.0;
    }
}
