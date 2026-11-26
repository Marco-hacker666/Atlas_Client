package atlas.module.modules;

import net.minecraft.util.MathHelper;

import java.util.Random;

final class MoonWalk {
    static final float STRAIGHT = 12.5F;

    static final double SLIDE = 1.2;

    static final double MIDDLE_LIMIT = 0.2;
    static final double WIDTH_CAP = 0.2;

    static final double ACTIVE = 0.3;

    static final double STOP_MARGIN = 0.12;

    static final class Settings {
        double widthMin = 0.05;
        double widthMax = 0.1;
        double middleMin = 0.5;
        double middleMax = 0.5;
        int redrawMin = 15;
        int redrawMax = 30;
    }

    private final Random random;
    private int dir = 1;
    private double width = -1.0;
    private double middle = 0.5;
    private int redrawIn;

    MoonWalk() {
        this(new Random());
    }

    MoonWalk(Random random) {
        this.random = random;
    }

    static boolean straight(float moveYaw) {
        float axis = Math.round(moveYaw / 90.0F) * 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(moveYaw - axis)) <= STRAIGHT;
    }

    float strafe(float moveYaw, double x, double z, double motionX, double motionZ, Settings s) {
        if (!straight(moveYaw)) {
            return 0.0F;
        }
        if (this.width < 0.0 || --this.redrawIn <= 0) {
            this.middle = clamp(between(s.middleMin, s.middleMax), 0.5 - MIDDLE_LIMIT, 0.5 + MIDDLE_LIMIT);
            this.redrawIn = s.redrawMin + this.random.nextInt(Math.max(1, Math.abs(s.redrawMax - s.redrawMin) + 1));
            if (this.width < 0.0) {
                this.width = drawWidth(s);
            }
        }
        double[] at = sway(moveYaw, x, z, motionX, motionZ);
        if (at == null) {
            return 0.0F;
        }
        double rest = at[0];
        double goal = goal(this.dir, this.middle, this.width);
        if (this.dir > 0 ? rest >= goal : rest <= goal) {
            this.dir = -this.dir;
            this.width = drawWidth(s);
            goal = goal(this.dir, this.middle, this.width);
            if (this.dir > 0 ? rest >= goal : rest <= goal) {
                return 0.0F;
            }
        }
        return (float) (this.dir * Math.signum(at[1]));
    }

    /**
     * The key {@link #strafe} would press for this position, without changing any state (no random draw, no
     * turn). Where strafe would draw at random (a first or due redraw of the middle, a new width after a turn),
     * this uses the middle of the settings' range and the current width instead, so it can differ from strafe
     * near a turn.
     */
    float peek(float moveYaw, double x, double z, double motionX, double motionZ, Settings s) {
        if (!straight(moveYaw)) {
            return 0.0F;
        }
        double middle = this.middle;
        double width = this.width;
        if (width < 0.0 || this.redrawIn - 1 <= 0) {
            middle = clamp((s.middleMin + s.middleMax) / 2.0, 0.5 - MIDDLE_LIMIT, 0.5 + MIDDLE_LIMIT);
            if (width < 0.0) {
                width = clamp((s.widthMin + s.widthMax) / 2.0, 0.0, WIDTH_CAP);
            }
        }
        double[] at = sway(moveYaw, x, z, motionX, motionZ);
        if (at == null) {
            return 0.0F;
        }
        int dir = this.dir;
        double rest = at[0];
        double goal = goal(dir, middle, width);
        if (dir > 0 ? rest >= goal : rest <= goal) {
            dir = -dir;
            goal = goal(dir, middle, width);
            if (dir > 0 ? rest >= goal : rest <= goal) {
                return 0.0F;
            }
        }
        return (float) (dir * Math.signum(at[1]));
    }

    /** {where the slide ends across the row, the side that is left}, or null outside the band the sway works in. */
    private static double[] sway(float moveYaw, double x, double z, double motionX, double motionZ) {
        float axis = Math.round(moveYaw / 90.0F) * 90.0F;
        double radians = Math.toRadians(axis);

        boolean alongZ = Math.abs(Math.cos(radians)) > 0.5;
        double lateral = alongZ ? x : z;
        double motion = alongZ ? motionX : motionZ;
        double left = alongZ ? Math.cos(radians) : Math.sin(radians);
        double frac = lateral - Math.floor(lateral);
        if (Math.abs(frac - 0.5) > ACTIVE) {
            return null;
        }
        return new double[]{frac + motion * SLIDE, left};
    }

    private static double goal(int dir, double middle, double width) {
        double reach = ACTIVE - STOP_MARGIN;
        return clamp(middle + dir * width, 0.5 - reach, 0.5 + reach);
    }

    void reset() {
        this.width = -1.0;
    }

    double middle() {
        return this.middle;
    }

    double width() {
        return this.width;
    }

    private double drawWidth(Settings s) {
        return clamp(between(s.widthMin, s.widthMax), 0.0, WIDTH_CAP);
    }

    private double between(double a, double b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        return lo + this.random.nextDouble() * (hi - lo);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
