package atlas.util;

import java.util.Random;

/**
 * A delay that moves the way a connection's latency moves (2026-10-27,
 * KnockbackDelay Steady): toward its target by at most {@code rampPerTick} a
 * tick -- so it never appears or vanishes in a step, which a timing check
 * would see as a spike -- and with a slow wander of up to {@code jitter}
 * around the target, mean-reverting, as a real round trip wanders.
 *
 * Plain arithmetic and a seeded random, tested on its own.
 */
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

    /**
     * One tick: the wander moves, then the delay steps toward
     * {@code target} (plus the wander while the target is above zero) by at
     * most {@code rampPerTick}. Returns the delay, never below zero.
     */
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
