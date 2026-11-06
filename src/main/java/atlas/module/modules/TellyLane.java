package atlas.module.modules;

import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The one line of cells Scaffold's SPEED-TELLY builds along (2026-11-04, owner: "only stack in one direction, do not
 * look for the nearest place"). It starts at the block stood on and goes one of eight ways, the yaw rounded to 45
 * degrees. Cell 0 is that block; every later cell shares a face with the one before, so each block is the next one's
 * support. Straight lanes are a row; a diagonal one is a staircase of alternating steps, X first.
 *
 * Pure geometry, no world: Clutch asks it which cell comes next and where the player is against it.
 */
final class TellyLane {

    /** indexOf: not a cell of this lane. */
    static final int OFF = Integer.MIN_VALUE;
    /** A yaw step this big counts as a half turn; Intave SharpRotation starts at 170. */
    static final float HALF_TURN = 168.0F;
    /** Degrees from a multiple of 45 that count as on the grid; Intave AngleSnap uses 0.08. */
    static final float GRID_MARGIN = 0.12F;
    private static final float GRID = 45.0F;
    /** A yaw step above this between two still ticks reads as a snap (Intave RotationSnapHeuristic). */
    static final float SNAP = 40.0F;
    /** Ticks a turn from forward to the blocks takes: about 45 degrees a tick from rest. */
    static final int TURN_TICKS = 4;
    /** Ticks in hand on top of the turn and one tick for each block. */
    static final int SPARE_TICKS = 0;

    /** The way it goes, on the 45 degree grid. */
    final float yaw;
    final int stepX;
    final int stepZ;
    private final int originX;
    private final int originZ;
    private final double dirX;
    private final double dirZ;
    /** A point on the line the player should be on. */
    private final double lineX;
    private final double lineZ;
    /** The block layer being built. */
    int layer;
    /** The cell last stood on. */
    int anchor;

    TellyLane(int x, int z, int layer, float yaw) {
        this.originX = x;
        this.originZ = z;
        this.layer = layer;
        this.yaw = Math.round(MathHelper.wrapAngleTo180_float(yaw) / 45.0F) * 45.0F;
        double radians = Math.toRadians(this.yaw);
        this.stepX = (int) Math.round(-Math.sin(radians));
        this.stepZ = (int) Math.round(Math.cos(radians));
        double length = Math.hypot(this.stepX, this.stepZ);
        this.dirX = this.stepX / length;
        this.dirZ = this.stepZ / length;
        /* A staircase's cells sit either side of its middle line. */
        this.lineX = x + 0.5 + (diagonal() ? this.stepX * 0.5 : 0.0);
        this.lineZ = z + 0.5;
    }

    boolean diagonal() {
        return this.stepX != 0 && this.stepZ != 0;
    }

    /** The column {x, z} of a cell. */
    int[] cell(int index) {
        if (!diagonal()) {
            return new int[]{this.originX + this.stepX * index, this.originZ + this.stepZ * index};
        }
        return new int[]{this.originX + this.stepX * Math.floorDiv(index + 1, 2),
                this.originZ + this.stepZ * Math.floorDiv(index, 2)};
    }

    /** Which cell this column is, or OFF. */
    int indexOf(int x, int z) {
        if (diagonal()) {
            int u = (x - this.originX) * this.stepX;
            int v = (z - this.originZ) * this.stepZ;
            return u - v == 0 || u - v == 1 ? u + v : OFF;
        }
        if (this.stepZ == 0) {
            return z == this.originZ ? (x - this.originX) * this.stepX : OFF;
        }
        return x == this.originX ? (z - this.originZ) * this.stepZ : OFF;
    }

    /**
     * A diagonal lane's next columns from the head of its stairs toward where the jump lands, the longer way first.
     * Empty once the head is level with the landing on both axes. Either step keeps the stairs joined, and the
     * count is the same both ways, so the caller takes whichever it can click soonest. A sprint jump keeps the
     * momentum it started with, so the stairs follow the real flight instead of a fixed zigzag it may never be over.
     */
    List<int[]> stepsToward(int headX, int headZ, int landX, int landZ) {
        int leftX = (landX - headX) * this.stepX;
        int leftZ = (landZ - headZ) * this.stepZ;
        List<int[]> steps = new ArrayList<int[]>(2);
        int[] alongX = {headX + this.stepX, headZ};
        int[] alongZ = {headX, headZ + this.stepZ};
        if (leftX > 0 && leftX >= leftZ) {
            steps.add(alongX);
        }
        if (leftZ > 0) {
            steps.add(alongZ);
        }
        if (leftX > 0 && leftX < leftZ) {
            steps.add(alongX);
        }
        return steps;
    }

    /** The cell level with this position along the lane, whatever its distance beside it. */
    int indexAt(double x, double z) {
        return (int) Math.round((x - this.originX - 0.5) * this.stepX + (z - this.originZ - 0.5) * this.stepZ);
    }

    /** The point on the lane's line this far past level with the position, as {x, z}. */
    double[] ahead(double x, double z, double distance) {
        double along = (x - this.lineX) * this.dirX + (z - this.lineZ) * this.dirZ + distance;
        return new double[]{this.lineX + this.dirX * along, this.lineZ + this.dirZ * along};
    }

    /** The yaw to step to on the way from one look to another: only this far round when the whole step is a half turn. */
    static float awayFromHalfTurn(float from, float to, float room) {
        float delta = MathHelper.wrapAngleTo180_float(to - from);
        return Math.abs(delta) < HALF_TURN ? to : from + Math.signum(delta) * room;
    }

    static boolean onGrid(float yaw) {
        float left = (yaw % GRID + GRID) % GRID;
        return Math.min(left, GRID - left) < GRID_MARGIN;
    }

    /** This yaw, or a little short of it on the side of {@code from} when it is on the grid. */
    static float offGrid(float yaw, float from, float amount) {
        if (!onGrid(yaw)) {
            return yaw;
        }
        return yaw + (MathHelper.wrapAngleTo180_float(from - yaw) < 0.0F ? -amount : amount);
    }

    /** Blocks the stairs still need from this head to the landing column. */
    int stepsLeft(int headX, int headZ, int landX, int landZ) {
        return Math.max(0, (landX - headX) * this.stepX) + Math.max(0, (landZ - headZ) * this.stepZ);
    }

    /** Whether the turn can wait another tick: the feet reach the layer later than the turn and every block take. */
    static boolean waits(int crossing, int blocksLeft) {
        return crossing > blocksLeft + TURN_TICKS + SPARE_TICKS;
    }

    /** Where a big step home stops this tick: this far short, on the way. A step under SNAP goes all the way. */
    static float shortOfHome(float from, float to, float rest) {
        float delta = MathHelper.wrapAngleTo180_float(to - from);
        return Math.abs(delta) <= SNAP ? to : to - Math.signum(delta) * rest;
    }

    /** The look a jump goes from: this far to one side of the view. */
    static float jumpYaw(float view, int side, float angle) {
        return view + side * angle;
    }
}
