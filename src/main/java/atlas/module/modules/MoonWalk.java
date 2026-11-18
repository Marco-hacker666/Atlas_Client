package atlas.module.modules;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * MOONWALK's side steps (2026-11-18, owner: "add MoonWalk", as Augustus has it; read from how that client behaves, none
 * of its code). Bridging backwards on a straight run, the player does not walk a ruled line: the side keys go on and off
 * so the path sways a little either side of a middle line across the block. The middle (0.5 = the centre of the row)
 * and how far either side (the width) are settings; a new width is drawn at every turn and a new middle every so many
 * ticks, so the sway does not repeat.
 *
 * Only the side key is chosen here, -1, 0 or 1 as a key gives it (never a fraction, which no keyboard makes), and only
 * on the ground, walking forward on a straight run: a diagonal run, the air, a crouch or no W leave the keys alone. The
 * turn is made on where the player would come to rest with no more side key (the slide on a block is about 1.2 times a
 * tick's motion), so the sway stays inside the band and the box stays over the row. A player whose centre is not near
 * the middle of its row (further than ACTIVE) is left alone: that row may not be the bridge.
 *
 * Pure, no world.
 */
final class MoonWalk {

    /** Degrees off an axis a walk still counts as straight. */
    static final float STRAIGHT = 12.5F;
    /** Ground slide after the last tick of input, as a multiple of that tick's motion (0.546 / (1 - 0.546)). */
    static final double SLIDE = 1.2;
    /** The middle is kept within this of the row's centre, the width under the cap, so the box stays over the row. */
    static final double MIDDLE_LIMIT = 0.2;
    static final double WIDTH_CAP = 0.2;
    /** Only a player this near the centre of its row sways: further out, the row may be the next one, so no key. */
    static final double ACTIVE = 0.3;
    /** A goal is kept this far inside ACTIVE: one tick of side key past a goal carries about 0.085 more. */
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

    /** Whether a walk this way is straight down an axis. */
    static boolean straight(float moveYaw) {
        float axis = Math.round(moveYaw / 90.0F) * 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(moveYaw - axis)) <= STRAIGHT;
    }

    /**
     * This tick's side key: 1 (left, A), -1 (right, D) or 0. {@code moveYaw} is the camera's (the keys are the camera's
     * before the move fix turns them); x, z and the motion are the player's.
     */
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
        float axis = Math.round(moveYaw / 90.0F) * 90.0F;
        double radians = Math.toRadians(axis);
        /* Along z (yaw 0 or 180) the sway is in x, along x in z. A side key of +1 moves (cos yaw, sin yaw). */
        boolean alongZ = Math.abs(Math.cos(radians)) > 0.5;
        double lateral = alongZ ? x : z;
        double motion = alongZ ? motionX : motionZ;
        double left = alongZ ? Math.cos(radians) : Math.sin(radians);
        double frac = lateral - Math.floor(lateral);
        if (Math.abs(frac - 0.5) > ACTIVE) {
            return 0.0F;
        }
        double rest = frac + motion * SLIDE;
        double goal = goal();
        if (this.dir > 0 ? rest >= goal : rest <= goal) {
            this.dir = -this.dir;
            this.width = drawWidth(s);
            goal = goal();
            if (this.dir > 0 ? rest >= goal : rest <= goal) {
                return 0.0F;
            }
        }
        return (float) (this.dir * Math.signum(left));
    }

    /** Where this side's turn is: the band's edge, kept inside ACTIVE by STOP_MARGIN so the sway never leaves it. */
    private double goal() {
        double reach = ACTIVE - STOP_MARGIN;
        return clamp(this.middle + this.dir * this.width, 0.5 - reach, 0.5 + reach);
    }

    /** The player stopped swaying (off the ground, no W, another mode): the next sway draws afresh. */
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
