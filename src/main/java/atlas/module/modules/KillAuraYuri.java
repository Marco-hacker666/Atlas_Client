package atlas.module.modules;

/**
 * KillAura's Yuri auto-block modes (2026-10-24, owner: "port Yuri's aura and
 * auto block"). Rebuilt from what Yuri Client's AuraModule does (Yuri,
 * AGPL-3.0, github.com/unleg1t/Yuri), not copied:
 *
 *   HYPIXEL  within 3 blocks: any block of ours is let go at the start of the
 *            tick; when the next click is due within 50 ms the tick is marked,
 *            and the tick after the click the sword goes up (use packet plus
 *            the client's own use). So it is up one tick between clicks, never
 *            on a click tick. Beyond 3 blocks: no block. 8 clicks a second.
 *   LEGIT    the use key is held while this player's hurt time is 6-10 and the
 *            target within 3 blocks; a click needs a tick with the key up
 *            first. 5 clicks a second.
 *   NCP      let go at the start of each tick (release packet), block again
 *            after the movement packet (use packet only).
 *   VANILLA  a use packet every tick in block range, never let go.
 *
 * With only-when-hurt, nothing is blocked (and any block is let go) until
 * this player's hurt time reaches hurt-ticks. Out of block range, without a
 * sword or without a target, any block is let go.
 *
 * One step a tick from what the client sees; no game state, so tested on its
 * own. KillAura sends the packets the step asks for.
 */
final class KillAuraYuri {
    static final int HYPIXEL = 0;
    static final int LEGIT = 1;
    static final int NCP = 2;
    static final int VANILLA = 3;
    /** Yuri's fixed click rates for the two timed modes; 0: the aura's own CPS. */
    static final long[] CLICK_MS = {125L, 200L, 0L, 0L};

    /** What one step asks KillAura to do this tick. */
    static final class Plan {
        /** Let go now, before any click (release packet; HYPIXEL also stops the client's use). */
        boolean release;
        /** Raise the sword now (HYPIXEL: packet + client use; VANILLA: packet). */
        boolean block;
        /** NCP: raise it after the movement packet. */
        boolean blockAfterMove;
        /** LEGIT: the use key's state, or null to leave it. */
        Boolean useKey;
        /** Whether a due click may go out this tick. */
        boolean mayClick = true;
    }

    /** Whether a block of ours is up on the server. */
    boolean blocking;
    private int hypixelTick;
    private int legitTicks = -1;

    void reset() {
        this.blocking = false;
        this.hypixelTick = 0;
        this.legitTicks = -1;
    }

    /**
     * @param mode       HYPIXEL, LEGIT, NCP or VANILLA
     * @param inRange    a target within block range and a sword in hand
     * @param distance   to the target (feet to feet, as Yuri measures), any value when not in range
     * @param hurtTime   this player's hurt time
     * @param msToClick  until the aura's next click is due (0 or less: due now)
     * @param usingItem  the client is using an item
     */
    Plan step(int mode, boolean inRange, double distance, int hurtTime, boolean onlyWhenHurt, int hurtTicks,
              long msToClick, boolean usingItem) {
        Plan plan = new Plan();
        if (!inRange || (onlyWhenHurt && hurtTime < hurtTicks)) {
            letGo(mode, plan);
            return plan;
        }
        switch (mode) {
            case HYPIXEL:
                if (distance <= 3.0) {
                    if (this.blocking) {
                        plan.release = true;
                        this.blocking = false;
                    }
                    if (msToClick <= 50L) {
                        this.hypixelTick = 1;
                    } else if (this.hypixelTick == 1) {
                        plan.block = true;
                        this.blocking = true;
                        this.hypixelTick = 0;
                    }
                } else {
                    if (this.blocking) {
                        plan.release = true;
                        this.blocking = false;
                    }
                    this.hypixelTick = 0;
                }
                break;
            case LEGIT: {
                boolean press = hurtTime <= 10 && hurtTime >= 6 && distance <= 3.0;
                plan.useKey = press;
                this.blocking = press;
                this.legitTicks++;
                if (press || usingItem) {
                    this.legitTicks = 0;
                }
                plan.mayClick = this.legitTicks >= 1;
                break;
            }
            case NCP:
                if (this.blocking) {
                    plan.release = true;
                    this.blocking = false;
                }
                plan.blockAfterMove = true;
                break;
            case VANILLA:
                plan.block = true;
                this.blocking = true;
                break;
            default:
                break;
        }
        return plan;
    }

    /** NCP: the block after the movement packet went out. */
    void blockedAfterMove() {
        this.blocking = true;
    }

    private void letGo(int mode, Plan plan) {
        if (mode == LEGIT) {
            if (this.blocking) {
                plan.useKey = false;
            }
        } else if (this.blocking) {
            plan.release = true;
        }
        this.blocking = false;
        this.hypixelTick = 0;
        this.legitTicks = -1;
    }
}
