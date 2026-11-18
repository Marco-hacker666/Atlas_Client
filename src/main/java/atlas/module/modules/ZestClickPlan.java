package atlas.module.modules;

import atlas.util.HumanClicks;

import java.util.Random;

final class ZestClickPlan {
    static final int BURST_MIN = 2;
    static final int BURST_MAX = 3;
    static final int BURST_MIN_CPS = 14;
    static final int BURST_MAX_CPS = 18;

    static final long REBURST_MS = 300L;

    private final Random random;
    private final HumanClicks gaps;
    private boolean armed = true;
    private int burstLeft;
    private long nextClickMs;
    private long burstEndMs = Long.MIN_VALUE;

    ZestClickPlan(Random random) {
        this.random = random;
        this.gaps = new HumanClicks(random);
    }

    void reset() {
        this.armed = true;
        this.burstLeft = 0;
        this.burstEndMs = Long.MIN_VALUE;
    }

    static boolean owns(boolean combo, boolean wanting) {
        return combo && wanting;
    }

    int tick(long now, boolean combo, boolean wanting, boolean open) {
        if (!owns(combo, wanting)) {
            reset();
            return 0;
        }
        if (!open) {
            this.armed = true;
            this.burstLeft = 0;
            return 0;
        }
        if (this.armed) {
            this.armed = false;
            this.burstLeft = BURST_MIN + this.random.nextInt(BURST_MAX - BURST_MIN + 1);
            this.nextClickMs = now;
        } else if (this.burstLeft == 0 && now - this.burstEndMs >= REBURST_MS) {
            this.armed = true;
            return tick(now, combo, wanting, open);
        }

        if (this.burstLeft > 0 && this.nextClickMs <= now + 49L) {
            this.burstLeft--;
            this.nextClickMs = Math.max(this.nextClickMs + this.gaps.next(BURST_MIN_CPS, BURST_MAX_CPS, now), now + 50L);
            if (this.burstLeft == 0) {
                this.burstEndMs = now;
            }
            return 1;
        }
        return 0;
    }
}
