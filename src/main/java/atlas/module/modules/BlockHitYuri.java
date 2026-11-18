package atlas.module.modules;

// Rebuilt from what Yuri Client's Block Hit module does (Yuri, AGPL-3.0, github.com/unleg1t/Yuri), not copied.

final class BlockHitYuri {
    enum Act { NONE, PRESS, RELEASE }

    boolean blocking;
    private boolean pending;
    private long hitAt;
    private long pressedAt;

    static boolean standsAside(boolean drivenByAura, boolean auraBlocksByItself) {
        return !drivenByAura && auraBlocksByItself;
    }

    void reset() {
        this.blocking = false;
        this.pending = false;
    }

    boolean hit(boolean sword, double distance, double range, double roll, double chancePercent, long now) {
        if (this.blocking || !sword || distance > range || !(roll <= chancePercent / 100.0)) {
            return false;
        }
        this.pending = true;
        this.hitAt = now;
        return true;
    }

    Act tick(long now, boolean actionSentThisTick, boolean forwardHeld, long comboMs, long blockMs) {
        Act act = Act.NONE;
        if (this.pending && now - this.hitAt >= comboMs && !actionSentThisTick) {
            this.pending = false;
            if (forwardHeld) {
                this.blocking = true;
                this.pressedAt = now;
                act = Act.PRESS;
            }
        }
        if (this.blocking && act != Act.PRESS && now - this.pressedAt >= blockMs && !actionSentThisTick) {
            this.blocking = false;
            act = Act.RELEASE;
        }
        return act;
    }
}
