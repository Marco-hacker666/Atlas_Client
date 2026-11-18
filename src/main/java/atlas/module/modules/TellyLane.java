package atlas.module.modules;

import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;

final class TellyLane {
    static final int OFF = Integer.MIN_VALUE;

    static final float HALF_TURN = 168.0F;

    static final float GRID_MARGIN = 0.12F;
    private static final float GRID = 45.0F;

    static final float SNAP = 40.0F;

    static final int TURN_TICKS = 4;

    static final int SPARE_TICKS = 0;

    final float yaw;
    final int stepX;
    final int stepZ;
    private final int originX;
    private final int originZ;
    private final double dirX;
    private final double dirZ;

    private final double lineX;
    private final double lineZ;

    int layer;

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

        this.lineX = x + 0.5 + (diagonal() ? this.stepX * 0.5 : 0.0);
        this.lineZ = z + 0.5;
    }

    boolean diagonal() {
        return this.stepX != 0 && this.stepZ != 0;
    }

    int[] cell(int index) {
        if (!diagonal()) {
            return new int[]{this.originX + this.stepX * index, this.originZ + this.stepZ * index};
        }
        return new int[]{this.originX + this.stepX * Math.floorDiv(index + 1, 2),
                this.originZ + this.stepZ * Math.floorDiv(index, 2)};
    }

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

    int indexAt(double x, double z) {
        return (int) Math.round((x - this.originX - 0.5) * this.stepX + (z - this.originZ - 0.5) * this.stepZ);
    }

    double[] ahead(double x, double z, double distance) {
        double along = (x - this.lineX) * this.dirX + (z - this.lineZ) * this.dirZ + distance;
        return new double[]{this.lineX + this.dirX * along, this.lineZ + this.dirZ * along};
    }

    static float awayFromHalfTurn(float from, float to, float room) {
        float delta = MathHelper.wrapAngleTo180_float(to - from);
        return Math.abs(delta) < HALF_TURN ? to : from + Math.signum(delta) * room;
    }

    static boolean onGrid(float yaw) {
        float left = (yaw % GRID + GRID) % GRID;
        return Math.min(left, GRID - left) < GRID_MARGIN;
    }

    static float offGrid(float yaw, float from, float amount) {
        if (!onGrid(yaw)) {
            return yaw;
        }
        return yaw + (MathHelper.wrapAngleTo180_float(from - yaw) < 0.0F ? -amount : amount);
    }

    int stepsLeft(int headX, int headZ, int landX, int landZ) {
        return Math.max(0, (landX - headX) * this.stepX) + Math.max(0, (landZ - headZ) * this.stepZ);
    }

    static boolean waits(int crossing, int blocksLeft) {
        return crossing > blocksLeft + TURN_TICKS + SPARE_TICKS;
    }

    static float shortOfHome(float from, float to, float rest) {
        float delta = MathHelper.wrapAngleTo180_float(to - from);
        return Math.abs(delta) <= SNAP ? to : to - Math.signum(delta) * rest;
    }

    static float jumpYaw(float view, int side, float angle) {
        return view + side * angle;
    }
}
