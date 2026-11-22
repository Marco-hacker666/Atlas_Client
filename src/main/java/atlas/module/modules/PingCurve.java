package atlas.module.modules;

import java.util.Random;

final class PingCurve {
    static final int RECALC_MIN = 400;
    static final int RECALC_MAX = 600;

    static final class Config {
        int min = 100;
        int max = 200;
        boolean smooth;

        int rampSpeed = 110;

        int dashMin = 35;
        int dashMax = 45;
    }

    private final Random random;
    private double current;
    private boolean climbing;
    private boolean resetting;
    private double fallFrom;
    private long fallStart;
    private int fallMs;
    private long nextRedraw;
    private long lastStep = -1L;

    PingCurve() {
        this(new Random());
    }

    PingCurve(Random random) {
        this.random = random;
    }

    private int draw(int lo, int hi) {
        if (hi <= lo) {
            return lo;
        }
        return lo + this.random.nextInt(hi - lo + 1);
    }

    double hold(long now, Config c) {
        if (this.resetting) {
            this.resetting = false;
            this.climbing = c.smooth;
        }
        long step = this.lastStep < 0L ? 0L : Math.max(0L, now - this.lastStep);
        this.lastStep = now;
        if (this.climbing) {
            this.current += c.rampSpeed * step / 1000.0;
            double target = Math.max(c.min, Math.min(c.max, this.target));
            if (this.current >= target) {
                this.current = target;
                this.climbing = false;
                this.nextRedraw = now + draw(RECALC_MIN, RECALC_MAX);
            }
        } else if (now >= this.nextRedraw) {
            this.target = draw(c.min, c.max);
            this.current = this.target;
            this.nextRedraw = now + draw(RECALC_MIN, RECALC_MAX);
        }
        return this.current;
    }

    private double target = -1.0;

    double reset(long now, Config c) {
        this.lastStep = now;
        if (!this.resetting) {
            this.resetting = true;
            this.fallFrom = this.current;
            this.fallStart = now;
            this.fallMs = Math.max(1, draw(c.dashMin, c.dashMax));
            if (this.target < 0.0) {
                this.target = draw(c.min, c.max);
            }
        }
        double left = Math.max(0.0, Math.min(this.fallMs, this.fallMs - (now - this.fallStart))) / this.fallMs;
        this.current = this.fallFrom * left;
        if (this.current <= 0.0) {
            this.current = 0.0;
            return -1.0;
        }
        return this.current;
    }

    boolean resetting() {
        return this.resetting;
    }

    double current() {
        return this.current;
    }

    void clear() {
        this.current = 0.0;
        this.climbing = false;
        this.resetting = false;
        this.nextRedraw = 0L;
        this.lastStep = -1L;
        this.target = -1.0;
    }
}
