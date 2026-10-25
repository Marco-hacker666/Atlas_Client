package atlas.module.modules;

/**
 * AutoBlock's Yuri mode (2026-10-24, owner: "port Yuri's auto block").
 * Rebuilt from what Yuri Client's Block Hit module does (Yuri, AGPL-3.0,
 * github.com/unleg1t/Yuri), not copied.
 *
 * A hit of yours on a target within range, with a sword, passes the chance
 * roll: combo-delay ms later the use key goes down -- only if forward is held
 * then -- and block-delay ms after that it comes up. Neither happens on a
 * tick that has already sent an action (slot, attack, swing, use, dig,
 * inventory: Yuri's BadPackets); it waits for the next. While the key is
 * down, further hits start nothing.
 *
 * Times in ms from a clock the caller passes; no game state, so tested on
 * its own.
 */
final class BlockHitYuri {
    enum Act { NONE, PRESS, RELEASE }

    /** The use key is down because of this mode. */
    boolean blocking;
    private boolean pending;
    private long hitAt;
    private long pressedAt;

    void reset() {
        this.blocking = false;
        this.pending = false;
    }

    /**
     * A hit of yours. True when it starts a combo.
     *
     * @param roll a random number in [0, 1)
     */
    boolean hit(boolean sword, double distance, double range, double roll, double chancePercent, long now) {
        if (this.blocking || !sword || distance > range || !(roll <= chancePercent / 100.0)) {
            return false;
        }
        this.pending = true;
        this.hitAt = now;
        return true;
    }

    /** One tick, after this tick's input. */
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
