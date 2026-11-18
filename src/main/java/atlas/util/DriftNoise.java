package atlas.util;

import java.util.Random;

public final class DriftNoise {
    public static final float MIN_STEP = 0.2F;

    private final double period;
    private double start = Double.NEGATIVE_INFINITY;
    private float from;
    private float to;

    public DriftNoise(double periodSeconds) {
        this.period = periodSeconds;
    }

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
