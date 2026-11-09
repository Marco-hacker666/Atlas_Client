package atlas.module.modules;

import atlas.util.DriftNoise;

import java.util.Random;

/**
 * When SprintReset's CRAB mode stops and restarts sprinting (2026-11-09, rebuilt from what Crab client's Sprint Reset
 * does; no code of its is used). Crab's turned out to be Slinky's Sprint Reset step for step; this matches
 * OpenSlinky's readable reconstruction (logic/combat/sprint_reset.cpp) too. One call a tick; times are in ticks and
 * may be fractions, which carry over.
 *
 * - A hit arms it, at most one every 0.5 seconds.
 * - Idle and armed (and, with damaged, hurt this very tick; with the weapon check, a weapon in hand): wait delay.
 * - Then the action (let go of forward, crouch, or drop sprint) for stop-duration, renewed every tick.
 * - Then the keys go back to what the player is really holding.
 * randomize adds 0 to 2 ticks to the delay and takes 0 to 1 off the stop, both drifting (DriftNoise: 5 s for the
 * delay, 30 s for the stop), and 5 to 10 % of the time makes the stop 1 to 2 ticks longer.
 */
final class CrabSprintPlan {
    static final int NONE = 0;
    /** Hold the action this tick. */
    static final int APPLY = 1;
    /** Give the keys back this tick. */
    static final int RELEASE = 2;

    static final int IDLE = 0;
    static final int PENDING = 1;
    static final int ACTIVE = 2;

    /** At most one reset armed in this many seconds. */
    static final double ARM_GAP_SECONDS = 0.5;

    int state = IDLE;
    float timer;
    boolean armed;
    int ticksSinceHurt;
    private double lastArm = Double.NEGATIVE_INFINITY;
    private final DriftNoise delayNoise = new DriftNoise(5.0);
    private final DriftNoise stopNoise = new DriftNoise(30.0);
    private final DriftNoise longStopNoise = new DriftNoise(30.0);

    /**
     * @param attacked     the player hit something since the last tick
     * @param hurtTime     the player's hurt time (10 the tick a hit lands on them)
     * @param weaponOk     a weapon in hand, or no weapon check
     */
    int tick(double now, boolean attacked, int hurtTime, float delayTicks, float stopTicks, boolean randomize,
             boolean damaged, boolean weaponOk, Random random) {
        if (attacked && now - this.lastArm >= ARM_GAP_SECONDS) {
            this.lastArm = now;
            this.armed = true;
        }
        this.ticksSinceHurt++;
        if (hurtTime == 10) {
            this.ticksSinceHurt = 0;
        }
        if (this.timer > 0.0F) {
            this.timer -= 1.0F;
            if (this.timer > 0.0F) {
                return this.state == ACTIVE ? APPLY : NONE;
            }
        }
        if (this.state == ACTIVE) {
            this.state = IDLE;
            return RELEASE;
        }
        if (this.state == PENDING) {
            this.state = ACTIVE;
            this.armed = false;
            float stop = stopTicks;
            if (randomize) {
                stop -= this.stopNoise.value(now, random);
                float roll = random.nextFloat();
                float chance = this.longStopNoise.value(now, random) * 0.05F + 0.05F;
                if (stop <= 0.0F) {
                    stop = 0.0F;
                }
                if (roll < chance) {
                    stop += random.nextFloat() + 1.0F;
                }
            }
            this.timer += stop;
            return APPLY;
        }
        float delay = delayTicks;
        if (randomize) {
            delay += 2.0F * this.delayNoise.value(now, random);
        }
        if (this.armed && (!damaged || this.ticksSinceHurt == 0) && weaponOk) {
            this.timer += delay;
            this.state = PENDING;
        }
        return NONE;
    }

    /** Out of the game or switched off: RELEASE when the action was being held. */
    int stop() {
        boolean wasActive = this.state == ACTIVE;
        this.state = IDLE;
        this.timer = 0.0F;
        this.armed = false;
        return wasActive ? RELEASE : NONE;
    }
}
