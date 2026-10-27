package atlas.humanize;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Whether this click lands, so that over a fight the clicks that land come to
 * the profile's hit rate, the way a person's do (2026-10-25).
 *
 * The aim is not a fixed coin: it drifts slowly around hitRate
 * (Ornstein-Uhlenbeck, within +-spread), goes hot or cold for a few clicks,
 * and is moved by the situation -- distance, a strafing or airborne target,
 * being in the air, having just been hit, the first click of a combo, a target
 * nearly dead. The measured rate over the last clicks pulls the chance back
 * toward the aim (correction), so the long-run rate is accurate while short
 * runs still vary. A rate guard keeps hits a second within min..max.
 *
 * The caller asks {@link #decide} on a click and reports what really happened
 * with {@link #record}: a planned hit can still miss (the ray did not land),
 * and that is what the rate is measured on.
 */
public final class Accuracy {
    /** What the situation of this click is. */
    public static final class Situation {
        public double distance;
        /** Blocks a tick the target moves across the look. */
        public double targetLateralSpeed;
        public boolean targetAirborne;
        public boolean selfAirborne;
        /** Ticks since this player was hit (-1: not recently). */
        public int ticksSinceHurt = -1;
        public float targetHealth = 20.0F;
    }

    private static final int STATE_NORMAL = 0;
    private static final int STATE_HOT = 1;
    private static final int STATE_COLD = 2;
    private static final long COMBO_GAP_MS = 1000L;

    private final Random random;
    private final boolean[] window = new boolean[200];
    private int count;
    private int pos;
    private double drift;
    private long lastDriftAt = Long.MIN_VALUE;
    private int state = STATE_NORMAL;
    private int stateLeft;
    private long lastHitAt = Long.MIN_VALUE;
    private final ArrayDeque<Long> hitTimes = new ArrayDeque<Long>();
    private double lastAim;

    public Accuracy(Random random) {
        this.random = random;
    }

    public void reset() {
        this.count = 0;
        this.pos = 0;
        this.state = STATE_NORMAL;
        this.stateLeft = 0;
        this.hitTimes.clear();
        /* 2026-10-26 (review): the drift, its clock, the last hit and the last aim also start over. */
        this.drift = 0.0;
        this.lastDriftAt = Long.MIN_VALUE;
        this.lastHitAt = Long.MIN_VALUE;
        this.lastAim = 0.0;
    }

    /** The measured rate over the window (0..1), or -1 before 5 clicks. */
    public double measured(int windowSize) {
        int n = Math.min(this.count, Math.max(1, Math.min(windowSize, this.window.length)));
        if (n < 5) {
            return -1.0;
        }
        int hits = 0;
        for (int i = 0; i < n; i++) {
            int index = (this.pos - 1 - i + this.window.length) % this.window.length;
            if (this.window[index]) {
                hits++;
            }
        }
        return hits / (double) n;
    }

    /** The rate aimed at for the last decision (0..1), for stats. */
    public double lastAim() {
        return this.lastAim;
    }

    public boolean hot() {
        return this.state == STATE_HOT;
    }

    public boolean cold() {
        return this.state == STATE_COLD;
    }

    /** The rate aimed at for this click, 0.02..0.98. */
    public double aim(HumanProfile p, Situation s, long now) {
        double spread = Math.max(0.0, p.hitRateSpread) / 100.0;
        if (this.lastDriftAt == Long.MIN_VALUE) {
            this.lastDriftAt = now;
        }
        double dt = Math.min(5.0, Math.max(0.0, (now - this.lastDriftAt) / 1000.0));
        this.lastDriftAt = now;
        double tau = Math.max(1.0, p.driftSeconds);
        if (spread > 0.0 && dt > 0.0) {
            this.drift += -this.drift * dt / tau + spread * Math.sqrt(2.0 * dt / tau) * this.random.nextGaussian();
            this.drift = Math.max(-spread, Math.min(spread, this.drift));
        } else if (spread == 0.0) {
            this.drift = 0.0;
        }
        double rate = p.hitRate / 100.0 + this.drift;
        if (this.state == STATE_HOT) {
            rate += p.hotBonus / 100.0;
        } else if (this.state == STATE_COLD) {
            rate -= p.coldPenalty / 100.0;
        }
        rate -= situationPenalty(p, s, now) / 100.0;
        this.lastAim = Math.max(0.02, Math.min(0.98, rate));
        return this.lastAim;
    }

    /** Points off the hit rate for the situation (negative: a bonus). */
    public double situationPenalty(HumanProfile p, Situation s, long now) {
        if (s == null) {
            return 0.0;
        }
        double points = 0.0;
        points += Math.max(0.0, s.distance - p.distanceStart) * p.distancePenalty;
        points += Math.max(0.0, s.targetLateralSpeed) / 0.1 * p.strafePenalty;
        if (s.targetAirborne) {
            points += p.targetAirPenalty;
        }
        if (s.selfAirborne) {
            points += p.selfAirPenalty;
        }
        if (s.ticksSinceHurt >= 0 && s.ticksSinceHurt < p.hurtTicks && p.hurtTicks > 0) {
            points += p.hurtPenalty * (1.0 - s.ticksSinceHurt / (double) p.hurtTicks);
        }
        if (this.lastHitAt == Long.MIN_VALUE || now - this.lastHitAt > COMBO_GAP_MS) {
            points -= p.firstHitBonus;
        }
        if (s.targetHealth > 0.0F && s.targetHealth <= p.lowHealthThreshold) {
            points -= p.lowHealthBonus;
        }
        return points;
    }

    /** Whether this click should land. */
    public boolean decide(HumanProfile p, Situation s, long now) {
        stepState(p);
        while (!this.hitTimes.isEmpty() && now - this.hitTimes.peekFirst() > 1000L) {
            this.hitTimes.pollFirst();
        }
        if (p.maxHitsPerSecond > 0.0F && this.hitTimes.size() >= p.maxHitsPerSecond) {
            return false;
        }
        if (p.minHitsPerSecond > 0.0F && this.hitTimes.size() < p.minHitsPerSecond
                && (this.lastHitAt == Long.MIN_VALUE || now - this.lastHitAt >= 1000.0 / p.minHitsPerSecond)) {
            this.aim(p, s, now);
            return true;
        }
        double aim = aim(p, s, now);
        double measured = measured(p.window);
        double chance = measured < 0.0 ? aim : aim + Math.max(0.0, p.correction) * (aim - measured);
        chance = Math.max(0.01, Math.min(0.99, chance));
        return this.random.nextDouble() < chance;
    }

    private void stepState(HumanProfile p) {
        if (!p.streaks) {
            this.state = STATE_NORMAL;
            return;
        }
        if (this.state != STATE_NORMAL) {
            if (--this.stateLeft <= 0) {
                this.state = STATE_NORMAL;
            }
            return;
        }
        double roll = this.random.nextDouble() * 100.0;
        if (roll < p.hotChance) {
            this.state = STATE_HOT;
            this.stateLeft = between(p.hotMin, p.hotMax);
        } else if (roll < p.hotChance + p.coldChance) {
            this.state = STATE_COLD;
            this.stateLeft = between(p.coldMin, p.coldMax);
        }
    }

    /** What really happened to this click. */
    public void record(boolean hit, long now) {
        this.window[this.pos] = hit;
        this.pos = (this.pos + 1) % this.window.length;
        if (this.count < this.window.length) {
            this.count++;
        }
        if (hit) {
            this.lastHitAt = now;
            this.hitTimes.addLast(now);
        }
    }

    private int between(int min, int max) {
        int low = Math.max(1, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        return low + this.random.nextInt(high - low + 1);
    }
}
