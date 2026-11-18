package atlas.module.modules;

import atlas.util.DriftNoise;

import java.util.Random;

final class CrabSprintPlan {
    static final int NONE = 0;

    static final int APPLY = 1;

    static final int RELEASE = 2;

    static final int IDLE = 0;
    static final int PENDING = 1;
    static final int ACTIVE = 2;

    static final double ARM_GAP_SECONDS = 0.5;

    int state = IDLE;
    float timer;
    boolean armed;
    int ticksSinceHurt;
    private double lastArm = Double.NEGATIVE_INFINITY;
    private final DriftNoise delayNoise = new DriftNoise(5.0);
    private final DriftNoise stopNoise = new DriftNoise(30.0);
    private final DriftNoise longStopNoise = new DriftNoise(30.0);

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

    int stop() {
        boolean wasActive = this.state == ACTIVE;
        this.state = IDLE;
        this.timer = 0.0F;
        this.armed = false;
        return wasActive ? RELEASE : NONE;
    }
}
