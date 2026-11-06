package atlas.module.modules;

import net.minecraft.util.MathHelper;

/**
 * The look of a GodBridge run (2026-11-07, owner: "look at the other clients and redo it"). A godbridge is a look held
 * still: behind the walk, 45 degrees to one side, and the blocks come as the player's edge reaches the face it points at.
 * So the look is worked out once, when the run starts, and held. LiquidBounce worked the side out every tick on the ground,
 * which swapped between the two sides by 90 degrees mid run and broke the row; Rise holds one. Only a ledge the look
 * cannot place at changes side, at most once in MISS_TICKS. Behaviour read from those clients, no code of theirs.
 *
 * Pure, no world.
 */
final class GodBridgeLook {

    static final float STRAIGHT_PITCH = 75.7F;
    static final float DIAGONAL_PITCH = 75.6F;
    /** Away from a multiple of 45: Intave AngleSnap notes a look that lands on the grid after a turn. */
    static final float OFF_GRID = 0.3F;
    /** Ticks between two changes of side, so a bad spot does not swing the look to and fro. */
    static final int MISS_TICKS = 20;

    private boolean running;
    private float backYaw;
    private int side = 1;
    private int lastSwap = Integer.MIN_VALUE / 2;

    /** The look for this tick, {yaw, pitch}, from the way the keys go and where the player stands. */
    float[] look(float moveYaw, double x, double z) {
        float back = Math.round((moveYaw + 180.0F) / 45.0F) * 45.0F;
        if (!this.running || Math.abs(MathHelper.wrapAngleTo180_float(back - this.backYaw)) >= 45.0F) {
            this.running = true;
            this.backYaw = back;
            this.side = sideAt(back, x, z);
        }
        if (diagonal(this.backYaw)) {
            return new float[]{this.backYaw + OFF_GRID, DIAGONAL_PITCH};
        }
        return new float[]{this.backYaw + this.side * (45.0F + OFF_GRID), STRAIGHT_PITCH};
    }

    /** The keys let go: the next run works its look out again. */
    void release() {
        this.running = false;
    }

    /** A ledge this look could not place at. */
    void missed(int tick) {
        if (this.running && !diagonal(this.backYaw) && tick - this.lastSwap >= MISS_TICKS) {
            this.side = -this.side;
            this.lastSwap = tick;
        }
    }

    private static boolean diagonal(float yaw) {
        return yaw % 90.0F != 0.0F;
    }

    /** The side of the row the player is on: half a block toward that side stays in the column, or crosses it. */
    static int sideAt(float backYaw, double x, double z) {
        double radians = Math.toRadians(backYaw);
        boolean crosses = Math.floor(x + Math.cos(radians) * 0.5) != Math.floor(x)
                || Math.floor(z + Math.sin(radians) * 0.5) != Math.floor(z);
        return crosses ? 1 : -1;
    }
}
