package atlas.combat;

public final class HitFeedback {
    public static final int IGNORED_TO_PAUSE = 3;
    public static final int PAUSE_TICKS = 30;

    private int pendingSince = -1;
    private int waitTicks;
    private int ignored;
    private int pausedUntil = Integer.MIN_VALUE;

    public void reset() {
        this.pendingSince = -1;
        this.ignored = 0;
        this.pausedUntil = Integer.MIN_VALUE;
    }

    public void attacked(int tick, int hurtTime, int waitTicks) {
        if (hurtTime == 0 && this.pendingSince < 0) {
            this.pendingSince = tick;
            this.waitTicks = waitTicks;
        }
    }

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

    public static int waitTicksFor(int pingMs) {
        int ping = pingMs > 0 ? pingMs : 300;
        return 4 + (int) Math.ceil((ping + 100) / 50.0);
    }

    public boolean paused(int tick) {
        return tick < this.pausedUntil;
    }
}
