package atlas.util;

import java.util.Random;

/**
 * A random number from 0 to 1 that drifts instead of jumping (2026-11-09, from Crab client's Sprint Reset).
 *
 * It slides in a straight line from one random value to the next over {@code period} seconds, then picks the next
 * target, always at least {@link #MIN_STEP} away from the last. Two hits a second apart get almost the same value,
 * two hits a minute apart quite different ones. That is how a person's timing wanders, where a fresh roll on every
 * hit is a flat distribution an anticheat can measure.
 */
public final class DriftNoise {
    /** How far apart two targets are at least. */
    public static final float MIN_STEP = 0.2F;

    private final double period;
    private double start = Double.NEGATIVE_INFINITY;
    private float from;
    private float to;

    public DriftNoise(double periodSeconds) {
        this.period = periodSeconds;
    }

    /** The value at {@code nowSeconds}, any steadily rising clock. */
    public float value(double nowSeconds, Random random) {
        if (this.start + this.period < nowSeconds) {
            this.start = nowSeconds;
            next(random);
        }
        return (float) ((this.to - this.from) * ((nowSeconds - this.start) / this.period) + this.from);
    }

    private void next(Random random) {
        if (this.from == 0.0F && this.to == 0.0F) {
            float a;
            float b;
            do {
                a = random.nextFloat();
                b = random.nextFloat();
            } while (Math.abs(a - b) < MIN_STEP);
            this.from = a;
            this.to = b;
            return;
        }
        this.from = this.to;
        float b;
        do {
            b = random.nextFloat();
        } while (Math.abs(this.from - b) < MIN_STEP);
        this.to = b;
    }
}
