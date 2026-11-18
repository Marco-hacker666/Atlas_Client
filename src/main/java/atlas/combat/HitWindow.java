package atlas.combat;

public final class HitWindow {
    public static final int WINDOW_TICKS = 10;
    private static final int NONE = Integer.MIN_VALUE;

    private int openedAt = NONE;
    private int lastHurtTime;
    private int flinchAt = NONE;

    public void reset() {
        this.openedAt = NONE;
        this.lastHurtTime = 0;
        this.flinchAt = NONE;
    }

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

    public void sent(int tick, boolean countsNow) {
        if (countsNow) {
            this.openedAt = tick;
        }
    }

    public void ignored() {
        this.openedAt = NONE;
    }

    public boolean ours(int tick, int roundTripTicks) {
        return this.openedAt != NONE && tick - this.openedAt < WINDOW_TICKS + Math.max(0, roundTripTicks) + 2;
    }

    public int opensIn(int tick, float observed, int roundTripTicks) {
        if (this.ours(tick, roundTripTicks)) {
            return Math.max(0, this.openedAt + WINDOW_TICKS - tick);
        }
        return observed <= 0.0F ? 0 : (int) Math.ceil(observed);
    }
}
