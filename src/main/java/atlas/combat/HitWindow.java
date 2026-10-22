package atlas.combat;

/**
 * When a hit on the target counts again, by this client's own send clock.
 *
 * In 1.8 a hit that hurts opens a ten tick window (hurtResistantTime 20 down
 * to 10) in which equal or weaker blows do nothing. A hit sent at tick T on a
 * target that can be hurt reaches the server half a round trip later; a hit
 * sent at T + 10 reaches it half a round trip after that, which is exactly
 * when the window closes. So for a window this client opened, its own send
 * clock needs no ping at all.
 *
 * The hurt time the client sees runs a whole round trip behind (the flinch
 * comes back half a trip late, the next hit goes out half a trip early):
 * InvulnTiming corrects it by the ping. That estimate is used for windows
 * someone else opened (a teammate's hit, another player's), and after this
 * client's own window has long run out.
 *
 * Measured 2026-10-15 (flags/fights logs of 2026-10-13/14): with random click
 * timing KillAura2 landed 1.8 hits a combo and 0.55 a second; the old aura,
 * timed by InvulnTiming, 2.0-3.4 and 0.66-1.1.
 */
public final class HitWindow {
    /** Ticks a hit keeps the target from taking another (1.8). */
    public static final int WINDOW_TICKS = 10;
    private static final int NONE = Integer.MIN_VALUE;

    private int openedAt = NONE;
    private int lastHurtTime;
    private int flinchAt = NONE;

    /** A new target, or nothing known: no window of ours is running. */
    public void reset() {
        this.openedAt = NONE;
        this.lastHurtTime = 0;
        this.flinchAt = NONE;
    }

    /**
     * The target's hurt time as seen this tick. A window of ours that no
     * flinch has answered within the round trip and three ticks was never
     * opened (the server dropped the hit): without this a dropped hit in a
     * combo would hold fire for the rest of a window that does not exist.
     */
    public void observe(int tick, int hurtTime, int roundTripTicks) {
        if (hurtTime > this.lastHurtTime) {
            this.flinchAt = tick;
        }
        this.lastHurtTime = hurtTime;
        if (this.openedAt != NONE && (this.flinchAt == NONE || this.flinchAt <= this.openedAt)
                && tick - this.openedAt > Math.max(0, roundTripTicks) + 3) {
            this.openedAt = NONE;
        }
    }

    /**
     * A hit went out at {@code tick}. It opens a window only when it could
     * hurt ({@code countsNow}); a hit into a running window changes nothing.
     */
    public void sent(int tick, boolean countsNow) {
        if (countsNow) {
            this.openedAt = tick;
        }
    }

    /** The hit that opened our window showed no hurt: it never opened one. */
    public void ignored() {
        this.openedAt = NONE;
    }

    /** Whether a window this client opened is still what decides. */
    public boolean ours(int tick, int roundTripTicks) {
        return this.openedAt != NONE && tick - this.openedAt < WINDOW_TICKS + Math.max(0, roundTripTicks) + 2;
    }

    /**
     * Ticks until a hit sent then would count; 0 means now.
     *
     * @param observed       the estimate from the hurt time the client sees,
     *                       in ticks (InvulnTiming.predict); 0 or less = now
     * @param roundTripTicks the round trip in ticks, for how long our own
     *                       clock outranks the observed hurt time
     */
    public int opensIn(int tick, float observed, int roundTripTicks) {
        if (this.ours(tick, roundTripTicks)) {
            /* Our hit opened it: the observed hurt time is that same hit, seen
               late, and would hold one or two ticks too long. */
            return Math.max(0, this.openedAt + WINDOW_TICKS - tick);
        }
        return observed <= 0.0F ? 0 : (int) Math.ceil(observed);
    }
}
