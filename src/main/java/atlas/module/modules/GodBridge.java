package atlas.module.modules;

import atlas.util.HumanClicks;
import net.minecraft.util.MathHelper;

import java.util.Random;

final class GodBridge {
    static final float STRAIGHT_PITCH = 75.7F;
    static final float DIAGONAL_PITCH = 75.6F;

    static final float BACK_STRAIGHT_PITCH = 75.8F;
    static final float BACK_DIAGONAL_PITCH = 76.5F;

    static final float PITCH_SPREAD = 0.4F;

    static final float OFFSET_MIN = 0.3F;
    static final float OFFSET_MAX = 1.2F;

    static final float DRIFT_STEP = 0.06F;
    static final float DRIFT_MAX = 0.45F;

    static final float SETTLED = 2.0F;
    static final float TURN_STEP = 45.0F;

    static final int SWAP_TICKS = 20;

    static final int LAND_TICKS = 2;
    static final int MIN_CPS = 15;
    static final int MAX_CPS = 19;

    static final float PITCH_FIX_MAX = 6.0F;

    static final float AIR_PITCH_FIX_MAX = 12.0F;

    static final float AIR_RETURN_STEP = 1.2F;

    static final class Input {
        double x;
        double z;
        boolean onGround;

        boolean forward;

        float moveYaw;

        float sentYaw;
        float sentPitch;

        boolean edge;

        boolean lookPlaces;

        float placePitch = Float.NaN;

        float aheadPitch = Float.NaN;
        boolean hasTarget;

        boolean paceJump;

        int jumpEvery;

        boolean straightBack;
        long now;
    }

    static final class Plan {
        boolean look;
        float yaw;
        float pitch;

        float maxTurn;

        boolean stop;
        boolean jump;

        boolean click;
    }

    private final Random random;
    private final HumanClicks clicks;
    private boolean running;
    private float backYaw;
    private int side = 1;
    private float offset;
    private float pitchBase;
    private float driftYaw;
    private float driftPitch;
    private int blocks;
    private int jumpAt;
    private int ticks;
    private int groundTicks;
    private int lastSwap = Integer.MIN_VALUE / 2;
    private long nextClick = Long.MIN_VALUE;
    private float lookYaw;
    private float lookPitch;

    private float learnedPitch = Float.NaN;

    private float groundPitch = Float.NaN;

    private boolean straightBack;

    GodBridge() {
        this(new Random());
    }

    GodBridge(Random random) {
        this.random = random;
        this.clicks = new HumanClicks(random);
    }

    boolean running() {
        return this.running;
    }

    float lookYaw() {
        return this.lookYaw;
    }

    float lookPitch() {
        return this.lookPitch;
    }

    int side() {
        return this.side;
    }

    float learnedPitch() {
        return this.learnedPitch;
    }

    void release() {
        this.running = false;
    }

    void placed() {
        this.blocks++;
    }

    Plan tick(Input in) {
        this.ticks++;
        this.groundTicks = in.onGround ? this.groundTicks + 1 : 0;
        Plan plan = new Plan();
        if (!in.forward) {
            this.running = false;
            if (in.hasTarget) {
                plan.look = true;
                plan.yaw = (float) Math.floor(in.sentYaw / 90.0F) * 90.0F + 45.0F;
                plan.pitch = 75.0F;
                plan.maxTurn = TURN_STEP;
                plan.click = clickDue(in.now);
            }
            return plan;
        }
        float back = Math.round((in.moveYaw + 180.0F) / 45.0F) * 45.0F;
        if (in.straightBack != this.straightBack) {
            this.straightBack = in.straightBack;
            this.learnedPitch = Float.NaN;
            this.running = false;
        }
        if (!this.running || Math.abs(MathHelper.wrapAngleTo180_float(back - this.backYaw)) >= 45.0F) {
            start(in, back);
        }
        drift();
        if (in.onGround && !Float.isNaN(this.groundPitch)) {
            float toGround = clamp(this.groundPitch - this.pitchBase, AIR_RETURN_STEP);
            this.pitchBase += toGround;
            if (Math.abs(this.groundPitch - this.pitchBase) < 0.01F) {
                this.groundPitch = Float.NaN;
            }
        }
        boolean diagonal = diagonal(this.backYaw);
        float yaw = this.backYaw + (diagonal || this.straightBack ? 0.0F : this.side * 45.0F) + this.offset + this.driftYaw;
        float pitch = this.pitchBase + this.driftPitch;

        yaw = in.sentYaw + MathHelper.wrapAngleTo180_float(yaw - in.sentYaw);
        float away = Math.abs(MathHelper.wrapAngleTo180_float(yaw - in.sentYaw)) + Math.abs(pitch - in.sentPitch);
        boolean settled = away <= SETTLED;

        boolean fixed = false;

        boolean air = !in.onGround && in.hasTarget;

        if (in.hasTarget && !in.lookPlaces && settled && !Float.isNaN(in.placePitch)) {
            if (air && Float.isNaN(this.groundPitch)) {
                this.groundPitch = this.pitchBase;
            }
            float step = clamp(in.placePitch - pitch, air ? AIR_PITCH_FIX_MAX : PITCH_FIX_MAX);
            this.pitchBase += step;
            pitch += step;
            fixed = Math.abs(in.placePitch - pitch) < 0.01F;
            if (fixed && !air) {
                this.learnedPitch = this.pitchBase;
                this.groundPitch = Float.NaN;
            }
        } else if (in.onGround && !in.lookPlaces && settled && !Float.isNaN(in.aheadPitch)) {
            this.pitchBase += clamp(in.aheadPitch - pitch, PITCH_FIX_MAX);
            pitch = this.pitchBase + this.driftPitch;
        }
        if (in.lookPlaces && in.edge) {
            this.learnedPitch = this.pitchBase;
        }

        if (in.edge && !in.lookPlaces && !fixed && Float.isNaN(in.placePitch) && Float.isNaN(in.aheadPitch) && settled
                && !diagonal && !this.straightBack
                && this.ticks - this.lastSwap >= SWAP_TICKS) {
            this.side = -this.side;
            this.lastSwap = this.ticks;
            this.offset = drawOffset();
            yaw = in.sentYaw + MathHelper.wrapAngleTo180_float(this.backYaw + this.side * 45.0F + this.offset
                    + this.driftYaw - in.sentYaw);
            settled = false;
        }
        plan.look = true;
        plan.yaw = yaw;
        plan.pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        plan.maxTurn = settled ? 180.0F : TURN_STEP;
        this.lookYaw = plan.yaw;
        this.lookPitch = plan.pitch;

        plan.stop = in.onGround && in.edge && !settled;
        if (!plan.stop && in.onGround && settled && !in.edge && this.groundTicks > LAND_TICKS
                && (in.paceJump || in.jumpEvery > 0 && this.blocks >= this.jumpAt)) {
            plan.jump = true;
            this.blocks = 0;
            this.jumpAt = drawJump(in.jumpEvery);
        }
        plan.click = clickDue(in.now) || (in.lookPlaces || fixed) && in.hasTarget;
        return plan;
    }

    private void start(Input in, float back) {
        this.running = true;
        this.backYaw = back;
        this.side = sideAt(back, in.x, in.z);
        this.offset = drawOffset();
        float fresh = this.straightBack
                ? (diagonal(back) ? BACK_DIAGONAL_PITCH : BACK_STRAIGHT_PITCH)
                : (diagonal(back) ? DIAGONAL_PITCH : STRAIGHT_PITCH);
        float base = Float.isNaN(this.learnedPitch) ? fresh : this.learnedPitch;
        this.pitchBase = base + (this.random.nextFloat() * 2.0F - 1.0F) * PITCH_SPREAD;
        this.driftYaw = 0.0F;
        this.driftPitch = 0.0F;
        this.blocks = 0;
        this.jumpAt = drawJump(in.jumpEvery);
    }

    private boolean clickDue(long now) {
        if (now < this.nextClick) {
            return false;
        }
        long gap = this.clicks.next(MIN_CPS, MAX_CPS, now);
        this.nextClick = this.nextClick == Long.MIN_VALUE || now - this.nextClick > 500L ? now + gap : this.nextClick + gap;
        return true;
    }

    private void drift() {
        this.driftYaw = clamp(this.driftYaw + (this.random.nextFloat() * 2.0F - 1.0F) * DRIFT_STEP, DRIFT_MAX);
        this.driftPitch = clamp(this.driftPitch + (this.random.nextFloat() * 2.0F - 1.0F) * DRIFT_STEP * 0.5F,
                DRIFT_MAX * 0.5F);

        if (Math.abs(this.offset + this.driftYaw) < OFFSET_MIN) {
            this.driftYaw = Math.signum(this.offset) * OFFSET_MIN - this.offset;
        }
    }

    private float drawOffset() {
        float size = OFFSET_MIN + DRIFT_MAX + this.random.nextFloat() * (OFFSET_MAX - OFFSET_MIN);
        return this.random.nextBoolean() ? size : -size;
    }

    private int drawJump(int every) {
        return every <= 0 ? Integer.MAX_VALUE : every + this.random.nextInt(2);
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    static boolean diagonal(float yaw) {
        return Math.round(yaw) % 90 != 0;
    }

    static int sideAt(float backYaw, double x, double z) {
        double radians = Math.toRadians(backYaw);
        boolean crosses = Math.floor(x + Math.cos(radians) * 0.5) != Math.floor(x)
                || Math.floor(z + Math.sin(radians) * 0.5) != Math.floor(z);
        return crosses ? 1 : -1;
    }
}
