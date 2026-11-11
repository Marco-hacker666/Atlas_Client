package atlas.module.modules;

import atlas.util.HumanClicks;
import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * GODBRIDGE, written again from the technique (2026-11-08, owner: "rewrite GODBRIDGE from the start"), and with no
 * crouch at all since the second 2026-11-08 change (owner: "god bridge is defined by NOT crouching").
 *
 * What a person does (bridging guides and tutorials, and the godbridge modes of LiquidBounce, Rise and Yuri read for
 * behaviour only, no code of theirs): faces back along the bridge and 45 degrees to one side, and walks back on S and
 * the side key, so the walk goes straight down the line without sprint. The look is held still, pitch about 75.7, so
 * the crosshair crosses the side of the block stood on just as the feet reach its edge, and fast clicking puts the
 * block down then. Never a crouch: not to start, not at an edge, not to recover. A jump now and then.
 *
 * What a server sees of it (Intave PlacementAnalysis, ENGINEERING-NOTES 2026-11-07 and 2026-11-08): a look that does
 * not move, so no snap, no flick, no rotation speed; blocks under the player in one line, which Intave's Speed and
 * Sneak parts judge by the time between them and, with no crouch ever, by their strictest limits (350 ms a block over
 * 8, 500 over 24 in a strict line). A block placed with the feet high in a jump is not "under the player" to them:
 * Speed leaves it out (the next gap counts the whole time) and Sneak starts its 24 again. So the pace is kept with
 * jumps, never with a crouch: PlacePace says when the next block would put a book under its limit, and a jump goes
 * first. Where even that is not enough, Scaffold holds the click and the edge guard lets go of the keys (a stop, not a
 * crouch) until the block may go.
 *
 * This class decides, per tick and with no world: the run (its way and side, worked out when it starts and held), the
 * look (the run's, plus a small offset off the 45 degree grid drawn per run and a slow drift of a few tenths), how fast
 * the look may turn there (several ticks for the turn round at the start, none after), a stop, the jump and whether a
 * click goes out (HumanClicks' rhythm, plus any tick the look places at an edge). Scaffold supplies what it reads from
 * the world and does what this says.
 */
final class GodBridge {

    static final float STRAIGHT_PITCH = 75.7F;
    static final float DIAGONAL_PITCH = 75.6F;
    /** Per-run pitch, drawn within this of the base. */
    static final float PITCH_SPREAD = 0.4F;
    /** Per-run yaw offset off the 45 degree line: at least the first, at most the second (Intave AngleSnap: 0.08). */
    static final float OFFSET_MIN = 0.3F;
    static final float OFFSET_MAX = 1.2F;
    /** The slow drift a held hand has, degrees a tick and at most. */
    static final float DRIFT_STEP = 0.06F;
    static final float DRIFT_MAX = 0.45F;
    /** Within this of the run's look it is there; further, the turn is capped at TURN_STEP a tick. */
    static final float SETTLED = 2.0F;
    static final float TURN_STEP = 45.0F;
    /** Ticks between two changes of side. */
    static final int SWAP_TICKS = 20;
    /** Ticks on the ground after landing before the next jump (a jump straight off a landing reads as bhop). */
    static final int LAND_TICKS = 2;
    static final int MIN_CPS = 15;
    static final int MAX_CPS = 19;
    /** Most the pitch moves a tick to reach a pitch that places; the rest follows next tick. */
    static final float PITCH_FIX_MAX = 6.0F;
    /** In the air the eyes rise 1.25 in six ticks, so the pitch that places moves faster than at an edge. */
    static final float AIR_PITCH_FIX_MAX = 12.0F;
    /** After a jump, the pitch eases back to the ground one this much a tick: under SETTLED, so the walk never stops for it. */
    static final float AIR_RETURN_STEP = 1.2F;

    /** What Scaffold read this tick. */
    static final class Input {
        double x;
        double z;
        boolean onGround;
        /** W (the walk keys) held. */
        boolean forward;
        /** The way the keys walk, by the camera. */
        float moveYaw;
        /** The look the server has. */
        float sentYaw;
        float sentPitch;
        /** On the ground, walking, and the next step has no floor. */
        boolean edge;
        /** The run's look (lookYaw, lookPitch of the last plan) places the wanted block from here. */
        boolean lookPlaces;
        /** At the run's yaw, the pitch nearest the run's that places from here; NaN for none or not looked for. */
        float placePitch = Float.NaN;
        /** The same, from where the player will be next tick; NaN for none or not looked for. */
        float aheadPitch = Float.NaN;
        boolean hasTarget;
        /** PlacePace: without a jump first, the next block would put the server's books under their limit. */
        boolean paceJump;
        /** Blocks between jumps, 0 for none. */
        int jumpEvery;
        long now;
    }

    /** What to do this tick. */
    static final class Plan {
        /** A look to send; false: none (no keys, no block). */
        boolean look;
        float yaw;
        float pitch;
        /** Degrees a tick the look may turn on the way there. */
        float maxTurn;
        /** Let go of the walk keys this tick: an edge reached while the look still turns. Never a crouch. */
        boolean stop;
        boolean jump;
        /** A click may go out: a block is placed if the look sent hits a wanted cell. */
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
    /** The pitch that last placed, kept for the next run; NaN until one has. */
    private float learnedPitch = Float.NaN;
    /** The run's pitch before the air fix moved it; NaN when the air fix has not. */
    private float groundPitch = Float.NaN;

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

    /** The run's look, as last planned. */
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

    /** The keys let go, or the module went off: the next run is worked out again. */
    void release() {
        this.running = false;
    }

    /** A block went down. */
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
                /* No keys and a block wanted: the corner of the side aimed at, as before. */
                plan.look = true;
                plan.yaw = (float) Math.floor(in.sentYaw / 90.0F) * 90.0F + 45.0F;
                plan.pitch = 75.0F;
                plan.maxTurn = TURN_STEP;
                plan.click = clickDue(in.now);
            }
            return plan;
        }
        float back = Math.round((in.moveYaw + 180.0F) / 45.0F) * 45.0F;
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
        float yaw = this.backYaw + (diagonal ? 0.0F : this.side * 45.0F) + this.offset + this.driftYaw;
        float pitch = this.pitchBase + this.driftPitch;
        /* Continuous with the look sent, so the turn goes the short way. */
        yaw = in.sentYaw + MathHelper.wrapAngleTo180_float(yaw - in.sentYaw);
        float away = Math.abs(MathHelper.wrapAngleTo180_float(yaw - in.sentYaw)) + Math.abs(pitch - in.sentPitch);
        boolean settled = away <= SETTLED;

        /* The fixed LB pitch meets the side only with the feet past the edge on this target: at the edge, the pitch
           goes to the one that places from where the player is, and the run keeps it. */
        boolean fixed = false;
        /* In the air (a jump) the eyes are up to 1.25 higher, so the run's pitch meets the side only far past the edge:
           the pitch that places from here is taken too, faster, not learned, and eased back after the landing. */
        boolean air = !in.onGround && in.hasTarget;
        /* 2026-11-11 (owner: slow, misses, a nod at every edge): not only at the edge. The fixed pitch places in a
           strip 0.008 of a block wide and a walking tick is 0.216, so waiting for the edge meant a stop at every
           block. Any tick the block can go from where the player is, the pitch goes to the one that places... */
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
            /* No hasTarget here: the column under the feet may be filled while the next step's still needs a block
               (review, PR #35). Scaffold only sets aheadPitch when that block exists. */
            /* ...and the tick before, it is already on its way to the one that places from the next step, so the
               move is spread over two ticks and the walk does not wait for it. No click: nothing places yet. */
            this.pitchBase += clamp(in.aheadPitch - pitch, PITCH_FIX_MAX);
            pitch = this.pitchBase + this.driftPitch;
        }
        if (in.lookPlaces && in.edge) {
            this.learnedPitch = this.pitchBase;
        }

        if (in.edge && !in.lookPlaces && !fixed && Float.isNaN(in.placePitch) && Float.isNaN(in.aheadPitch) && settled
                && !diagonal
                && this.ticks - this.lastSwap >= SWAP_TICKS) {
            /* An edge the look cannot place at: the other side (a stop meanwhile, below). */
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

        /* At an edge while the look is still turning onto the run's: the keys let go until it is there. Upright. An
           edge the settled look misses is walked on: the look places only with the feet well over the edge (the
           75.7 ray meets the block's side about 0.29 out), and Scaffold's fall guard lets go of the keys only when one
           more step would leave no floor. */
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
        float base = Float.isNaN(this.learnedPitch) ? (diagonal(back) ? DIAGONAL_PITCH : STRAIGHT_PITCH)
                : this.learnedPitch;
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
        /* The drift never takes the yaw back onto the grid. */
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

    /** The side of the row the player is on: half a block toward that side stays in the column, or crosses it. */
    static int sideAt(float backYaw, double x, double z) {
        double radians = Math.toRadians(backYaw);
        boolean crosses = Math.floor(x + Math.cos(radians) * 0.5) != Math.floor(x)
                || Math.floor(z + Math.sin(radians) * 0.5) != Math.floor(z);
        return crosses ? 1 : -1;
    }
}
