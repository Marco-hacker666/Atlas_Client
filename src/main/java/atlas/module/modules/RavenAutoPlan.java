package atlas.module.modules;

import atlas.humanize.HumanProfile;
import atlas.humanize.Reaction;

import java.util.Random;

final class RavenAutoPlan {
    static final double FACING_DEG = 75.0;
    static final int REACT_MIN_MS = 110;
    static final int REACT_MAX_MS = 260;
    static final int LINGER_MIN_MS = 60;
    static final int LINGER_MAX_MS = 180;

    static final double LAPSE_CHANCE = 0.06;

    static final double HOLD_SIGMA = 0.22;

    private final Random random;
    private boolean threatWas;
    private long readyAt = -1L;
    private long lingerUntil = -1L;

    private int raiseAtHurt = -1;
    private int holdLeft;
    private int downLeft;

    private boolean up;

    RavenAutoPlan(Random random) {
        this.random = random;
    }

    void reset() {
        this.threatWas = false;
        this.readyAt = -1L;
        this.lingerUntil = -1L;
        this.raiseAtHurt = -1;
        this.holdLeft = 0;
        this.downLeft = 0;
        this.up = false;
    }

    static double facingDeg(float enemyYaw, double dx, double dz) {
        double wanted = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double diff = (wanted - enemyYaw) % 360.0;
        if (diff >= 180.0) {
            diff -= 360.0;
        } else if (diff < -180.0) {
            diff += 360.0;
        }
        return Math.abs(diff);
    }

    static int hurtRaiseTick(int pingMs, double gaussian) {
        double ticks = Math.max(0, pingMs) / 50.0 + 0.5 + 0.7 * Math.max(-1.5, Math.min(1.5, gaussian));
        return Math.max(1, Math.min(9, (int) Math.round(ticks)));
    }

    static int holdTicks(int baseTicks, int maxExtraTicks, double gaussian, double randomA, double randomB) {
        double g = Math.max(-2.5, Math.min(2.5, gaussian));
        int hold = (int) Math.round(Math.max(1, baseTicks) * Math.exp(HOLD_SIGMA * g));
        return Math.max(1, hold) + AutoBlockRules.extraHoldTicks(maxExtraTicks, randomA, randomB);
    }

    static int downTicks(double roll, double lapseRoll, double lapseLength) {
        int down = roll < 0.70 ? 1 : roll < 0.92 ? 2 : 3;
        if (lapseRoll < LAPSE_CHANCE) {
            down += 2 + (int) (Math.max(0.0, Math.min(0.999, lapseLength)) * 3);
        }
        return down;
    }

    boolean wantUp(long now, boolean blocking, boolean threat, int hurt, boolean hurtAgain, int pingMs,
                   int baseHoldTicks, int maxExtraTicks) {
        if (blocking && !this.up) {
            this.holdLeft = drawHold(baseHoldTicks, maxExtraTicks);
        }
        boolean result = decide(now, blocking, threat, hurt, hurtAgain, pingMs, baseHoldTicks, maxExtraTicks);
        this.up = result;
        return result;
    }

    private boolean decide(long now, boolean blocking, boolean threat, int hurt, boolean hurtAgain, int pingMs,
                           int baseHoldTicks, int maxExtraTicks) {
        if (!threat) {
            if (this.threatWas) {
                this.threatWas = false;
                this.readyAt = -1L;
                this.lingerUntil = blocking ? now + Reaction.draw(LINGER_MIN_MS, LINGER_MAX_MS,
                        HumanProfile.SHAPE_TRIANGULAR, this.random) : -1L;
            }
            this.raiseAtHurt = -1;
            return blocking && now < this.lingerUntil;
        }
        if (!this.threatWas) {
            this.threatWas = true;
            this.lingerUntil = -1L;

            this.readyAt = blocking ? now : now + Reaction.draw(REACT_MIN_MS, REACT_MAX_MS,
                    HumanProfile.SHAPE_TRIANGULAR, this.random);
        }
        if (now < this.readyAt) {
            return false;
        }
        if (hurtAgain) {
            this.raiseAtHurt = hurtRaiseTick(pingMs, this.random.nextGaussian());
            this.downLeft = 0;
            return false;
        }
        if (this.raiseAtHurt >= 0) {
            if (hurt > this.raiseAtHurt) {
                return false;
            }
            this.raiseAtHurt = -1;
            this.downLeft = 0;
            this.holdLeft = drawHold(baseHoldTicks, maxExtraTicks);
            return true;
        }
        if (this.downLeft > 0) {
            this.downLeft--;
            return false;
        }
        if (!blocking) {
            this.holdLeft = drawHold(baseHoldTicks, maxExtraTicks);
            return true;
        }
        if (--this.holdLeft <= 0) {
            this.downLeft = downTicks(this.random.nextDouble(), this.random.nextDouble(), this.random.nextDouble()) - 1;
            return false;
        }
        return true;
    }

    private int drawHold(int baseHoldTicks, int maxExtraTicks) {
        return holdTicks(baseHoldTicks, maxExtraTicks, this.random.nextGaussian(), this.random.nextDouble(),
                this.random.nextDouble());
    }
}
