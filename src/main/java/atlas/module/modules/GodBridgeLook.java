package atlas.module.modules;

import net.minecraft.util.MathHelper;

final class GodBridgeLook {
    static final float STRAIGHT_PITCH = 75.7F;
    static final float DIAGONAL_PITCH = 75.6F;

    static final float OFF_GRID = 0.3F;

    static final int MISS_TICKS = 20;

    private boolean running;
    private float backYaw;
    private int side = 1;
    private int lastSwap = Integer.MIN_VALUE / 2;

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

    void release() {
        this.running = false;
    }

    void missed(int tick) {
        if (this.running && !diagonal(this.backYaw) && tick - this.lastSwap >= MISS_TICKS) {
            this.side = -this.side;
            this.lastSwap = tick;
        }
    }

    private static boolean diagonal(float yaw) {
        return yaw % 90.0F != 0.0F;
    }

    static int sideAt(float backYaw, double x, double z) {
        double radians = Math.toRadians(backYaw);
        boolean crosses = Math.floor(x + Math.cos(radians) * 0.5) != Math.floor(x)
                || Math.floor(z + Math.sin(radians) * 0.5) != Math.floor(z);
        return crosses ? 1 : -1;
    }
}
