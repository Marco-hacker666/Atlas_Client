package atlas.combat;

/**
 * SpeedBlocker's Press trigger (2026-11-02, owner: "press the key and a block goes out at once, and it really stops
 * them; it may try again and again, until the enemy is within 2 blocks").
 *
 * A press starts a burst, at once (the tick the key goes down). The burst runs while the key is held and for its
 * length after the key was last down. It follows one target, locked when it is first chosen and dropped by a new
 * press, so a runner that was stopped is still watched and blocked again if it goes round. end() stops a burst
 * early (the target came within stop-distance); while the key is still held the next tick starts it again, with
 * the target chosen afresh.
 */
public final class PressBurst {
    private boolean wasDown;
    private int until = Integer.MIN_VALUE;
    private int target = -1;

    /** One tick: whether the burst runs. */
    public boolean update(int tick, boolean down, int lengthTicks) {
        if (down && !this.wasDown) {
            /* A fresh press picks its target afresh. */
            this.target = -1;
        }
        this.wasDown = down;
        if (down) {
            this.until = tick + Math.max(0, lengthTicks);
        }
        return tick <= this.until;
    }

    /** The burst is over until the key is down again. */
    public void end() {
        this.until = Integer.MIN_VALUE;
        this.target = -1;
    }

    public void reset() {
        this.wasDown = false;
        this.end();
    }

    /** The locked target's entity id, -1 for none. */
    public int target() {
        return this.target;
    }

    public void lock(int entityId) {
        this.target = entityId;
    }
}
