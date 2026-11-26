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
     * Every key {@link #strafe} could press for this position, without changing any state. Where strafe draws at
     * random (a first or due redraw of the middle, the first width, a new width after a turn), every key a value
     * in the settings' range could lead to is included, so the set always holds the key strafe will press. With
     * nothing drawn it is that one key.
     *
     * @return 1 to 3 distinct keys out of -1, 0 and 1
     */
    float[] keys(float moveYaw, double x, double z, double motionX, double motionZ, Settings s) {
        if (!straight(moveYaw)) {
            return new float[]{0.0F};
        }
        double[] at = sway(moveYaw, x, z, motionX, motionZ);
        if (at == null) {
            return new float[]{0.0F};
        }
        double rest = at[0];
        float side = (float) Math.signum(at[1]);
        double[] drawnMiddle = {clamp(Math.min(s.middleMin, s.middleMax), 0.5 - MIDDLE_LIMIT, 0.5 + MIDDLE_LIMIT),
                clamp(Math.max(s.middleMin, s.middleMax), 0.5 - MIDDLE_LIMIT, 0.5 + MIDDLE_LIMIT)};
        double[] drawnWidth = {clamp(Math.min(s.widthMin, s.widthMax), 0.0, WIDTH_CAP),
                clamp(Math.max(s.widthMin, s.widthMax), 0.0, WIDTH_CAP)};
        double[] middle = this.width < 0.0 || this.redrawIn - 1 <= 0 ? drawnMiddle : new double[]{this.middle, this.middle};
        double[] width = this.width < 0.0 ? drawnWidth : new double[]{this.width, this.width};
        boolean[] press = new boolean[3];
        int dir = this.dir;
        double[] goal = goals(dir, middle, width);
        if (!passed(dir, rest, goal[1]) || !passed(dir, rest, goal[0])) {
            press[dir + 1] = true;
        }
        if (passed(dir, rest, goal[0]) || passed(dir, rest, goal[1])) {
            // a turn: the width is drawn again
            int back = -dir;
            double[] turned = goals(back, middle, drawnWidth);
            if (!passed(back, rest, turned[1]) || !passed(back, rest, turned[0])) {
                press[back + 1] = true;
            }
            if (passed(back, rest, turned[0]) || passed(back, rest, turned[1])) {
                press[1] = true;
            }
        }
        float[] out = new float[3];
        int n = 0;
        for (int i = 0; i < 3; i++) {
            if (press[i]) {
                out[n++] = i == 1 ? 0.0F : (i - 1) * side;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** {lowest, highest} goal over the ranges; the goal grows with the middle and, going +, with the width. */
    private static double[] goals(int dir, double[] middle, double[] width) {
        double a = goal(dir, middle[0], dir > 0 ? width[0] : width[1]);
        double b = goal(dir, middle[1], dir > 0 ? width[1] : width[0]);
        return new double[]{a, b};
    }

    private static boolean passed(int dir, double rest, double goal) {
        return dir > 0 ? rest >= goal : rest <= goal;
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
