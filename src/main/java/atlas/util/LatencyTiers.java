package atlas.util;

public final class LatencyTiers {
    public static final int UNKNOWN = -1;

    private int tier = UNKNOWN;

    private int pingTier = UNKNOWN;
    private boolean jittery;
    private int pending = UNKNOWN;
    private int pendingCount;

    public int tier() {
        return this.tier;
    }

    public boolean jittery() {
        return this.jittery;
    }

    public void reset() {
        this.tier = UNKNOWN;
        this.pingTier = UNKNOWN;
        this.jittery = false;
        this.pending = UNKNOWN;
        this.pendingCount = 0;
    }

    public int update(int average, int jitter, int mid, int high, int jitterLimit, int margin, int settle) {
        this.pingTier = byPing(average, this.pingTier, mid, high, margin);
        if (jitter > jitterLimit) {
            this.jittery = true;
        } else if (jitter < jitterLimit * 4 / 5) {
            this.jittery = false;
        }

        int wanted = this.jittery ? Math.max(this.pingTier, 1) : this.pingTier;

        if (this.tier == UNKNOWN) {
            this.tier = Math.min(1, wanted);
            this.pending = UNKNOWN;
            this.pendingCount = 0;
            if (wanted == this.tier) {
                return this.tier;
            }
        } else if (wanted == this.tier) {
            this.pending = UNKNOWN;
            this.pendingCount = 0;
            return this.tier;
        }
        if (wanted != this.pending) {
            this.pending = wanted;
            this.pendingCount = 1;
        } else {
            this.pendingCount++;
        }
        int needed = Math.max(1, settle) * (wanted == 2 ? 3 : 1);
        if (this.pendingCount >= needed) {
            this.tier = wanted;
            this.pending = UNKNOWN;
            this.pendingCount = 0;
        }
        return this.tier;
    }

    static int byPing(int average, int current, int mid, int high, int margin) {
        int enter = average >= high ? 2 : (average >= mid ? 1 : 0);
        if (current == UNKNOWN || enter >= current) {
            return enter;
        }
        int stay = average >= high - margin ? 2 : (average >= mid - margin ? 1 : 0);
        return Math.max(enter, Math.min(current, stay));
    }
}
