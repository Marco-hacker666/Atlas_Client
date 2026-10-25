package atlas.humanize;

import java.util.Random;

/**
 * What a hand does to an aim, tick by tick (2026-10-25):
 *
 *  - tremor: a smooth (Ornstein-Uhlenbeck) wander on yaw and pitch, larger
 *    while the target moves fast across the view;
 *  - overshoot: a large new turn may go past the target and come back
 *    (an offset that decays by 0.55 a tick), or fall short (0.6 a tick);
 *  - lapses: now and then the aim stops following for a few ticks;
 *  - turn speed: a log-normal factor, re-drawn every tick.
 *
 * {@link #tick} gives this tick's adjustment; the caller adds the offsets to
 * the rotation it aims at, multiplies its turn by the speed and, while
 * lapsing, does not turn toward the target at all.
 */
public final class AimError {
    /** This tick's adjustment. */
    public static final class Adjust {
        public float yaw;
        public float pitch;
        public float speed = 1.0F;
        public boolean lapsing;
        /** An overshoot or undershoot started this tick (for stats). */
        public boolean overshot;
        public boolean undershot;
    }

    private final Random random;
    private double noiseYaw;
    private double noisePitch;
    private float offsetYaw;
    private float offsetPitch;
    private float decay = 0.55F;
    private boolean wasTurning;
    private int lapseLeft;

    public AimError(Random random) {
        this.random = random;
    }

    public void reset() {
        this.noiseYaw = 0.0;
        this.noisePitch = 0.0;
        this.offsetYaw = 0.0F;
        this.offsetPitch = 0.0F;
        this.wasTurning = false;
        this.lapseLeft = 0;
    }

    public boolean lapsing() {
        return this.lapseLeft > 0;
    }

    /**
     * @param turnYaw   degrees still to turn to the target, yaw
     * @param turnPitch the same, pitch
     * @param angularSpeed degrees a tick the target moves across the view
     */
    public Adjust tick(HumanProfile p, float turnYaw, float turnPitch, float angularSpeed) {
        Adjust out = new Adjust();
        // tremor
        double a = Math.exp(-0.05 * Math.max(0.01, p.noiseSpeed) * 2.0 * Math.PI);
        double amp = Math.max(0.0, p.trackingNoise) * (1.0 + Math.max(0.0, p.noiseMotionScale) * Math.max(0.0, angularSpeed) / 10.0);
        double shake = Math.sqrt(1.0 - a * a);
        this.noiseYaw = this.noiseYaw * a + shake * amp * this.random.nextGaussian();
        this.noisePitch = this.noisePitch * a + shake * amp * 0.6 * this.random.nextGaussian();
        // overshoot / undershoot on the start of a large turn
        float turn = (float) Math.hypot(turnYaw, turnPitch);
        boolean turning = turn > p.overshootMinTurn;
        if (turning && !this.wasTurning) {
            double roll = this.random.nextDouble() * 100.0;
            if (roll < p.overshootChance) {
                float share = between(p.overshootMin, p.overshootMax) / 100.0F;
                this.offsetYaw = turnYaw * share;
                this.offsetPitch = turnPitch * share;
                this.decay = 0.55F;
                out.overshot = true;
            } else if (roll < p.overshootChance + p.undershootChance) {
                float share = between(p.undershootMin, p.undershootMax) / 100.0F;
                this.offsetYaw = -turnYaw * share;
                this.offsetPitch = -turnPitch * share;
                this.decay = 0.6F;
                out.undershot = true;
            }
        }
        this.wasTurning = turning;
        out.yaw = (float) this.noiseYaw + this.offsetYaw;
        out.pitch = (float) this.noisePitch + this.offsetPitch;
        this.offsetYaw *= this.decay;
        this.offsetPitch *= this.decay;
        // lapses: chance a second, so a twentieth a tick
        if (this.lapseLeft > 0) {
            this.lapseLeft--;
        } else if (this.random.nextDouble() * 100.0 < p.lapseChance / 20.0) {
            this.lapseLeft = Math.max(1, between(p.lapseMin, p.lapseMax));
        }
        out.lapsing = this.lapseLeft > 0;
        // speed
        double factor = Math.exp(Math.max(0.0, p.speedSigma) * this.random.nextGaussian());
        out.speed = (float) Math.max(Math.min(p.speedMin, p.speedMax), Math.min(Math.max(p.speedMin, p.speedMax), factor));
        return out;
    }

    private float between(float min, float max) {
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        return low + this.random.nextFloat() * (high - low);
    }

    private int between(int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return low + this.random.nextInt(high - low + 1);
    }
}
