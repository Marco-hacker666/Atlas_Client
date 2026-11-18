package atlas.lb.aiming;

import java.util.Random;

public final class SmoothNoise {
    private final Random random;
    private float from;
    private float to;

    private float at;

    public SmoothNoise(long seed) {
        this.random = new Random(seed);
        this.from = next();
        this.to = next();
    }

    private float next() {
        return this.random.nextFloat() * 2.0F - 1.0F;
    }

    public float advance(float step) {
        this.at += Math.max(0.0F, step);
        while (this.at >= 1.0F) {
            this.at -= 1.0F;
            this.from = this.to;
            this.to = next();
        }
        return value();
    }

    public float value() {
        float t = this.at * this.at * (3.0F - 2.0F * this.at);
        return this.from + (this.to - this.from) * t;
    }
}
