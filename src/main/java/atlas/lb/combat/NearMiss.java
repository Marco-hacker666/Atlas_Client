package atlas.lb.combat;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Random;

/**
 * Swings that just miss (2026-11-08). Behaviour read from Intave's public combat heuristics (github.com/intave/intave);
 * no code of theirs:
 *
 * - PreAttack: of every 100 attacks, at least 4 ticks must have a swing and no attack while the look, from the last
 *   position, meets the target's box grown by 0.25 within 4 blocks. Fewer: "attacks seem automated", and the player's
 *   hits are cut (medium damage nerf). A person misses by a hair now and then; an aura that only swings when it hits
 *   never does.
 * - AccuracyLongTerm: over 80 attacks, swings without an attack under 3 % of attacks: "high attack accuracy".
 * - AttackRequired: a swing and no attack while the look meets the box grown by only 0.05 within 3 blocks is an attack
 *   packet left out; twice between 1.5 and 20 s apart flags. So a miss with the look still on the target, as a
 *   "swing only" miss is, is worse than none.
 *
 * So the miss has to be a near miss: the look off the box by more than 0.05 and less than 0.25. This keeps the count
 * the way the server does, says when one is due (on a click the target is still immune to, where possible, so it
 * costs no damage), and works out and checks the look.
 *
 * Pure but for Minecraft's box and vector maths.
 */
public final class NearMiss {

    /** The server's grown boxes, with a margin inside each for the look it has being a tick or a rounding off. */
    public static final double OUTER = 0.25;
    public static final double INNER = 0.05;
    public static final double MARGIN = 0.04;
    /** PreAttack's range for the near miss (3 + 1), less a margin. */
    public static final double REACH = 3.85;
    /** PreAttack's window, in attacks, and its least near misses in it. */
    public static final int WINDOW = 100;
    public static final int REQUIRED = 4;
    /** Attacks between two near misses at least, so they do not come in clumps. */
    public static final int SPACING = 5;

    private final Random random;
    private int attacks;
    private int misses;
    private int sinceMiss = SPACING;

    public NearMiss() {
        this(new Random());
    }

    public NearMiss(Random random) {
        this.random = random;
    }

    /** An attack packet went out (whoever's: the server counts them all). */
    public void attacked() {
        this.attacks++;
        this.sinceMiss++;
        if (this.attacks >= WINDOW) {
            this.attacks = 0;
            this.misses = 0;
        }
    }

    /** A near miss went out. */
    public void missed() {
        this.misses++;
        this.sinceMiss = 0;
    }

    public int attacks() {
        return this.attacks;
    }

    public int misses() {
        return this.misses;
    }

    /**
     * Whether this click should be a near miss: behind a pace of {@code percent} per hundred attacks, spaced, and on a
     * click that cannot hurt the target anyway ({@code immune}), unless the window would end short of REQUIRED.
     */
    public boolean due(int percent, boolean immune) {
        if (this.sinceMiss < SPACING) {
            return false;
        }
        boolean behind = this.misses * 100 < (this.attacks + 1) * Math.max(REQUIRED, percent);
        if (!behind) {
            return false;
        }
        int left = WINDOW - this.attacks;
        boolean late = REQUIRED + 1 - this.misses >= left / SPACING;
        return immune || late;
    }

    /**
     * The look point for a near miss: level with the box's middle, past its side ({@code side} 1 or -1, across the
     * line of sight) by a distance drawn between the two grown boxes, checked with isNearMiss; null when no draw of a
     * few passes (too close, or the box too far for the grown one).
     */
    public Vec3 aimPoint(Vec3 eyes, AxisAlignedBB box, int side) {
        double cx = (box.minX + box.maxX) / 2.0;
        double cy = (box.minY + box.maxY) / 2.0;
        double cz = (box.minZ + box.maxZ) / 2.0;
        double dx = cx - eyes.xCoord;
        double dz = cz - eyes.zCoord;
        double length = Math.max(1.0E-6, Math.hypot(dx, dz));
        /* Across the line of sight, horizontally; a grown box grows this much further along it. */
        double ax = -dz / length;
        double az = dx / length;
        double grow = Math.abs(ax) + Math.abs(az);
        double half = halfWidthAcross(box, ax, az);
        double low = (INNER + MARGIN) * grow;
        double high = (OUTER - MARGIN) * grow;
        for (int attempt = 0; attempt < 8; attempt++) {
            double off = half + low + this.random.nextDouble() * (high - low);
            Vec3 point = new Vec3(cx + ax * side * off, cy, cz + az * side * off);
            Vec3 look = point.subtract(eyes).normalize();
            if (isNearMiss(eyes, look, box)) {
                return point;
            }
        }
        return null;
    }

    /** The side of the box's middle a look points to, across the line of sight: 1 or -1. */
    public static int sideOf(Vec3 eyes, AxisAlignedBB box, Vec3 look) {
        double cx = (box.minX + box.maxX) / 2.0 - eyes.xCoord;
        double cz = (box.minZ + box.maxZ) / 2.0 - eyes.zCoord;
        double cross = cx * look.zCoord - cz * look.xCoord;
        return cross >= 0.0 ? 1 : -1;
    }

    /**
     * Whether a look from {@code eyes} along {@code look} (a unit vector) is a near miss of {@code box}: it misses the
     * box grown by INNER plus the margin, and meets the one grown by OUTER less the margin, within REACH.
     */
    public static boolean isNearMiss(Vec3 eyes, Vec3 look, AxisAlignedBB box) {
        Vec3 far = eyes.addVector(look.xCoord * REACH, look.yCoord * REACH, look.zCoord * REACH);
        if (meets(box.expand(INNER + MARGIN, INNER + MARGIN, INNER + MARGIN), eyes, far)) {
            return false;
        }
        return meets(box.expand(OUTER - MARGIN, OUTER - MARGIN, OUTER - MARGIN), eyes, far);
    }

    private static boolean meets(AxisAlignedBB box, Vec3 from, Vec3 to) {
        if (box.isVecInside(from)) {
            return true;
        }
        MovingObjectPosition hit = box.calculateIntercept(from, to);
        return hit != null;
    }

    /** Half the box's extent along a horizontal unit direction. */
    private static double halfWidthAcross(AxisAlignedBB box, double ax, double az) {
        return Math.abs(ax) * (box.maxX - box.minX) / 2.0 + Math.abs(az) * (box.maxZ - box.minZ) / 2.0;
    }
}
