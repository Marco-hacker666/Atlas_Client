package atlas.module.modules;

/**
 * TELLY, redone (2026-11-18, owner: "a Telly like Augustus's, replacing ours"; read from how that client behaves, none
 * of its code). Telly bridging is a sprint jump forward with the look forward, as walking is, and the turn round to
 * place only on the way down: from the top of the jump (motion y below zero, in the air) the look goes back to the
 * blocks, they go down on the layer the jump left from, and on landing the look comes forward again for the next jump.
 * The bridge's layer is kept and W on the ground jumps by itself, as keep-y does.
 *
 * Standing still, or with no W, it is a plain backwards scaffold: the look goes back to place, as any mode's does.
 *
 * Pure, no world.
 */
final class TellyBridge {

    private TellyBridge() {
    }

    /** Whether this tick's look goes back to place; false: it stays forward with the walk. */
    static boolean placing(boolean onGround, double motionY, boolean walking) {
        if (!walking) {
            return true;
        }
        return !onGround && motionY < 0.0;
    }
}
