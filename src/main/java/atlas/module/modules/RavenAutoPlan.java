package atlas.module.modules;

import atlas.humanize.HumanProfile;
import atlas.humanize.Reaction;

import java.util.Random;

/**
 * raven-auto's block timing (2026-11-08, owner: "make the auto mode work on Pika"). No game, so it can be tested.
 *
 * The old auto mode ran the left+right path with nothing held: up the tick an enemy came in range, down after exactly
 * max-hold, up again the very next tick. Seen from the server that is a fixed up-2/down-1 square wave that starts on
 * the tick the enemy arrives, keeps going while the enemy looks the other way, and blocks through the ticks after a
 * hit when this player cannot be hurt anyway. Each of those is something a hand does not do.
 *
 * Here, once per tick, the sword is wanted up or down:
 * - threat: an enemy in range that faces this player (within {@link #FACING_DEG}); a player looking away cannot hit,
 *   and nobody blocks at a back;
 * - reaction: a fresh threat raises the sword only after a triangular 110-260 ms draw; when the threat ends the block
 *   lingers 60-180 ms before it drops, as a hand lets go late;
 * - after a hit: this player is invulnerable on the server for 10 ticks from that hit, and the client hears of the hit
 *   one way late, and the block reaches the server one way late again. So the sword drops on the hit (free to swing
 *   back) and comes back up when hurt time falls to about the round trip in ticks, ±1, so the block is in place on the
 *   server as the invulnerability runs out. At Pika's 220-235 ms that is hurt time 4-6; a blocked hit there is halved
 *   after armour (3.6 to 1.8), so that one timing is worth the most;
 * - holds: a log-normal draw around max-hold (median max-hold) plus random-hold's extra, never the same length twice
 *   in a row by design; drops: 1 tick most of the time, 2 or 3 now and then, and a rare lapse of a few ticks more.
 */
final class RavenAutoPlan {
    /** An enemy whose look is within this many degrees of this player faces it. */
    static final double FACING_DEG = 75.0;
    static final int REACT_MIN_MS = 110;
    static final int REACT_MAX_MS = 260;
    static final int LINGER_MIN_MS = 60;
    static final int LINGER_MAX_MS = 180;
    /** Chance a drop runs a few ticks longer: a hand that forgets for a moment. */
    static final double LAPSE_CHANCE = 0.06;
    /** Log-normal spread of a hold around max-hold. */
    static final double HOLD_SIGMA = 0.22;

    private final Random random;
    private boolean threatWas;
    private long readyAt = -1L;
    private long lingerUntil = -1L;
    /** After a hit: down until hurt time falls to this; -1 for none. */
    private int raiseAtHurt = -1;
    private int holdLeft;
    private int downLeft;
    /** What the last tick asked for. */
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

    /** How far an enemy's yaw is from pointing at this player, in degrees; dx, dz from the enemy to this player. */
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

    /** The hurt time the block goes back up at after a hit: about the round trip in ticks, ±1, 1 to 9. */
    static int hurtRaiseTick(int pingMs, double gaussian) {
        double ticks = Math.max(0, pingMs) / 50.0 + 0.5 + 0.7 * Math.max(-1.5, Math.min(1.5, gaussian));
        return Math.max(1, Math.min(9, (int) Math.round(ticks)));
    }

    /** A hold, in ticks: log-normal around {@code baseTicks}, plus random-hold's extra. At least 1. */
    static int holdTicks(int baseTicks, int maxExtraTicks, double gaussian, double randomA, double randomB) {
        double g = Math.max(-2.5, Math.min(2.5, gaussian));
        int hold = (int) Math.round(Math.max(1, baseTicks) * Math.exp(HOLD_SIGMA * g));
        return Math.max(1, hold) + AutoBlockRules.extraHoldTicks(maxExtraTicks, randomA, randomB);
    }

    /** A drop, in ticks: 1 (70%), 2 (22%), 3 (8%); a lapse adds 2 to 4. */
    static int downTicks(double roll, double lapseRoll, double lapseLength) {
        int down = roll < 0.70 ? 1 : roll < 0.92 ? 2 : 3;
        if (lapseRoll < LAPSE_CHANCE) {
            down += 2 + (int) (Math.max(0.0, Math.min(0.999, lapseLength)) * 3);
        }
        return down;
    }

    /**
     * One tick: whether the sword should be up.
     *
     * @param blocking  the sword is up now (by this mode)
     * @param threat    an enemy in range faces this player
     * @param hurt      this player's hurt time
     * @param hurtAgain hurt time rose this tick: a fresh hit
     */
    boolean wantUp(long now, boolean blocking, boolean threat, int hurt, boolean hurtAgain, int pingMs,
                   int baseHoldTicks, int maxExtraTicks) {
        if (blocking && !this.up) {
            /* Raised by someone else's path (block-again, a manual press): it gets a hold of its own. */
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
            /* Still up from a moment ago: already reacting. */
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
            /* This tick is the first one down. */
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
