package atlas.lb.aiming;

import java.util.Random;

/**
 * A smooth random signal in [-1, 1] (2026-11-10): random values at whole
 * steps, eased between with a smoothstep curve, so it drifts instead of
 * jumping. A hand's error and speed wander like this; a fresh uniform random
 * number every tick does not, and is what a classifier spots first.
 */
public final class SmoothNoise {
    private final Random random;
    private float from;
    private float to;
    /** Position between {@code from} (0) and {@code to} (1). */
    private float at;

    public SmoothNoise(long seed) {
        this.random = new Random(seed);
        this.from = next();
        this.to = next();
    }

    private float next() {
        return this.random.nextFloat() * 2.0F - 1.0F;
    }

    /** Moves on by {@code step} (fractions of one random value to the next) and returns the value there. */
    public float advance(float step) {
        this.at += Math.max(0.0F, step);
        while (this.at >= 1.0F) {
            this.at -= 1.0F;
            this.from = this.to;
            this.to = next();
        }
        return value();
    }

    /** The value now, without moving. */
    public float value() {
        float t = this.at * this.at * (3.0F - 2.0F * this.at);
        return this.from + (this.to - this.from) * t;
    }
}
