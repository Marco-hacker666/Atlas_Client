package atlas.management;

/**
 * Decides, once a second, two things a weak computer needs: turning Atlas's blur and glow off when the
 * game stays slow while they are drawn, and telling the player once when Minecraft spends a large share of
 * its time collecting garbage (too little memory). Pure, so it can be tested.
 */
public final class PerfGuard {
    static final int LOW_FPS = 30;
    static final int LOW_FPS_SECONDS = 15;
    static final int MIN_FRAME_LIMIT = 45;
    static final double GC_SHARE = 0.15;
    static final int GC_SECONDS = 20;

    public static final class Second {
        public int fps;
        public boolean inWorld;
        public boolean screenOpen;
        public boolean windowActive = true;
        /** The frame limit in the video settings; 0 or anything above 250 means unlimited. */
        public int frameLimit;
        /** Atlas drew blur or glow during this second. */
        public boolean effectsDrawn;
        /** Lite effects are already on (by the setting or by an earlier decision). */
        public boolean liteAlready;
        public long gcMillis;
        public long wallMillis = 1000L;
    }

    public enum Decision {
        NONE,
        GO_LITE,
        WARN_MEMORY
    }

    private int slowSeconds;
    private int gcSeconds;
    private long gcMillis;
    private long wallMillis;
    private boolean wentLite;
    private boolean warned;

    public Decision second(Second s) {
        if (!this.wentLite) {
            if (countsAsSlow(s)) {
                this.slowSeconds++;
            } else {
                this.slowSeconds = 0;
            }
            if (this.slowSeconds >= LOW_FPS_SECONDS) {
                this.wentLite = true;
                this.slowSeconds = 0;
                return Decision.GO_LITE;
            }
        }
        if (!this.warned) {
            this.gcSeconds++;
            this.gcMillis += Math.max(0L, s.gcMillis);
            this.wallMillis += Math.max(1L, s.wallMillis);
            if (this.gcSeconds >= GC_SECONDS) {
                boolean starved = this.gcMillis >= GC_SHARE * this.wallMillis;
                this.gcSeconds = 0;
                this.gcMillis = 0L;
                this.wallMillis = 0L;
                if (starved && s.inWorld) {
                    this.warned = true;
                    return Decision.WARN_MEMORY;
                }
            }
        }
        return Decision.NONE;
    }

    private static boolean countsAsSlow(Second s) {
        if (!s.inWorld || s.screenOpen || !s.windowActive || s.liteAlready || !s.effectsDrawn) {
            return false;
        }
        boolean limited = s.frameLimit > 0 && s.frameLimit <= 250;
        if (limited && s.frameLimit < MIN_FRAME_LIMIT) {
            return false;
        }
        return s.fps > 0 && s.fps < LOW_FPS;
    }

    public void reset() {
        this.slowSeconds = 0;
        this.gcSeconds = 0;
        this.gcMillis = 0L;
        this.wallMillis = 0L;
        this.wentLite = false;
        this.warned = false;
    }

    public boolean wentLite() {
        return this.wentLite;
    }
}
