package atlas.module.modules;

final class SnapBridge {
    static final int JUMP = 0;
    static final int GROUND = 1;
    static final int TAKEOFF_FORWARD = 0;
    static final int TAKEOFF_DIAGONAL = 1;

    static final float SPRINT_CONE = 45.0F;

    static final float BIG_TURN = 90.0F;

    static final float BIG_TURN_STEP = 90.0F;

    static final float HOLD_PITCH = 80.0F;

    static final float HOLD_SLACK = 60.0F;

    static final int PRE_TURN_TICKS = 3;

    static final int WINDOW_TICKS = 100;

    static final float PRE_TURN_STEP = 70.0F;

    private SnapBridge() {
    }

    static boolean autoJump(int style, boolean walking) {
        return style == JUMP && walking;
    }

    static boolean forward(int style, boolean onGround, boolean walking) {
        return onGround && autoJump(style, walking);
    }

    static float takeOffYaw(int takeOff, float walkYaw, int side) {
        if (takeOff != TAKEOFF_DIAGONAL) {
            return wrap(walkYaw);
        }
        return wrap(walkYaw + (side < 0 ? -SPRINT_CONE : SPRINT_CONE));
    }

    static float backDiagonal(float walkYaw, int side) {
        return wrap(walkYaw + 180.0F + (side < 0 ? -SPRINT_CONE : SPRINT_CONE));
    }

    static boolean landingSoon(double posY, double motionY, double layerY, int ticks) {
        if (motionY > 0.0) {
            return false;
        }
        double y = posY;
        double m = motionY;
        for (int i = 0; i < ticks; i++) {
            y += m;
            if (y <= layerY) {
                return true;
            }
            m = (m - 0.08) * 0.98;
        }
        return false;
    }

    static final class YawWindow {
        private final float[] steps = new float[WINDOW_TICKS];
        private int next;
        private float sum;

        void add(float step) {
            this.sum += step - this.steps[this.next];
            this.steps[this.next] = step;
            this.next = (this.next + 1) % this.steps.length;
            if (this.sum < 0.0F) {
                this.sum = 0.0F;
            }
        }

        float sum() {
            return this.sum;
        }

        void clear() {
            java.util.Arrays.fill(this.steps, 0.0F);
            this.next = 0;
            this.sum = 0.0F;
        }
    }

    static boolean aligned(float yaw, float walkYaw) {
        return Math.abs(wrap(yaw - walkYaw)) <= SPRINT_CONE + 0.5F;
    }

    static float smoothCap(float turn, float smooth) {
        if (turn > BIG_TURN) {
            return Math.max(BIG_TURN_STEP, smooth);
        }
        return smooth;
    }

    static float wrap(float a) {
        a %= 360.0F;
        if (a >= 180.0F) {
            a -= 360.0F;
        }
        if (a < -180.0F) {
            a += 360.0F;
        }
        return a;
    }
}
