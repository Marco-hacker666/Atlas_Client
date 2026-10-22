package atlas.combat;

/**
 * Notices hits the server ignores and backs off. A hit on a target that was
 * not hurt should show hurt within the round trip; when three in a row do
 * not, the server is not taking hits on it (a pre-game lobby, spawn
 * protection, PvP off, or an anticheat throwing them away) and attacking it
 * further only piles up swings nobody else would make. Attacks on that target
 * pause for a while; the aim goes on.
 *
 * Measured 2026-10-14: 43 of 50 dropped hits came in two bursts right after a
 * round start, with the eyes inside the target's box (players stacked at
 * spawn), 27 and 16 hits in a row with no reaction.
 */
public final class HitFeedback {
    public static final int IGNORED_TO_PAUSE = 3;
    public static final int PAUSE_TICKS = 30;

    private int pendingSince = -1;
    private int waitTicks;
    private int ignored;
    private int pausedUntil = Integer.MIN_VALUE;

    /** A new target: nothing known about it. */
    public void reset() {
        this.pendingSince = -1;
        this.ignored = 0;
        this.pausedUntil = Integer.MIN_VALUE;
    }

    /**
     * A hit went out at {@code tick} on a target whose hurt time was
     * {@code hurtTime}; a reaction is expected within {@code waitTicks}.
     * Hits on a target that is still hurt prove nothing and are not watched.
     */
    public void attacked(int tick, int hurtTime, int waitTicks) {
        if (hurtTime == 0 && this.pendingSince < 0) {
            this.pendingSince = tick;
            this.waitTicks = waitTicks;
        }
    }

    /**
     * The target's hurt time as seen at {@code tick}. True when this tick
     * decided that the watched hit was ignored (no hurt in time).
     */
    public boolean observe(int tick, int hurtTime) {
        if (this.pendingSince < 0) {
            return false;
        }
        if (hurtTime > 0) {
            this.pendingSince = -1;
            this.ignored = 0;
            return false;
        }
        if (tick - this.pendingSince > this.waitTicks) {
            this.pendingSince = -1;
            if (++this.ignored >= IGNORED_TO_PAUSE) {
                this.ignored = 0;
                this.pausedUntil = tick + PAUSE_TICKS;
            }
            return true;
        }
        return false;
    }

    /**
     * Ticks to wait for the hurt of a hit, from the round trip in ms. An
     * unknown ping (-1, as at a round start) is taken as 300 ms: a window
     * shorter than the real round trip calls every hit ignored and pauses a
     * fight that is going fine (2026-10-15: ping -1 gave 3 ticks, at 230 ms).
     */
    public static int waitTicksFor(int pingMs) {
        int ping = pingMs > 0 ? pingMs : 300;
        return 4 + (int) Math.ceil((ping + 100) / 50.0);
    }

    public boolean paused(int tick) {
        return tick < this.pausedUntil;
    }
}
