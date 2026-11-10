package atlas.module.modules;

import atlas.util.HumanClicks;

import java.util.Random;

/**
 * ZEST's clicks (2026-11-10, owner: "once a combo starts, control the clicks"; the guides burst click so that hit is
 * the one that lands). While a combo runs and the attack button is held: no clicks while the target cannot be hurt,
 * then a burst of 2-3 the moment it can, gaps from HumanClicks at 14-18 CPS. A burst that has not landed after a
 * while is followed by another. No game code: SprintReset presses the key and drops the other clicks.
 */
final class ZestClickPlan {

    static final int BURST_MIN = 2;
    static final int BURST_MAX = 3;
    static final int BURST_MIN_CPS = 14;
    static final int BURST_MAX_CPS = 18;
    /** Open this long after a burst with no hit landed: another burst. */
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

    /** Whether this plan owns the clicks now: other clicks are dropped while it does. */
    static boolean owns(boolean combo, boolean wanting) {
        return combo && wanting;
    }

    /**
     * One tick. open: an attack sent now lands after the target's hurt has run out. Returns the clicks to make this
     * tick (0 or 1).
     */
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
        /* One attack a tick at most, as a hand makes them; a gap under a tick just lands on the next one. */
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
