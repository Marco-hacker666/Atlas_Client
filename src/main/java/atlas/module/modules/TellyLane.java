package atlas.module.modules;

import net.minecraft.util.MathHelper;

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

    private boolean diagonal() {
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

    /** The cell level with this position along the lane, whatever its distance beside it. */
    int indexAt(double x, double z) {
        return (int) Math.round((x - this.originX - 0.5) * this.stepX + (z - this.originZ - 0.5) * this.stepZ);
    }

    /** The point on the lane's line this far past level with the position, as {x, z}. */
    double[] ahead(double x, double z, double distance) {
        double along = (x - this.lineX) * this.dirX + (z - this.lineZ) * this.dirZ + distance;
        return new double[]{this.lineX + this.dirX * along, this.lineZ + this.dirZ * along};
    }
}
