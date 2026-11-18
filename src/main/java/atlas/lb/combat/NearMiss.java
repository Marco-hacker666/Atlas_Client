package atlas.lb.combat;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Random;

public final class NearMiss {
    public static final double OUTER = 0.25;
    public static final double INNER = 0.05;
    public static final double MARGIN = 0.04;

    public static final double REACH = 3.85;

    public static final int WINDOW = 100;
    public static final int REQUIRED = 4;

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

    public void attacked() {
        this.attacks++;
        this.sinceMiss++;
        if (this.attacks >= WINDOW) {
            this.attacks = 0;
            this.misses = 0;
        }
    }

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

    public Vec3 aimPoint(Vec3 eyes, AxisAlignedBB box, int side) {
        double cx = (box.minX + box.maxX) / 2.0;
        double cy = (box.minY + box.maxY) / 2.0;
        double cz = (box.minZ + box.maxZ) / 2.0;
        double dx = cx - eyes.xCoord;
        double dz = cz - eyes.zCoord;
        double length = Math.max(1.0E-6, Math.hypot(dx, dz));

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

    public static int sideOf(Vec3 eyes, AxisAlignedBB box, Vec3 look) {
        double cx = (box.minX + box.maxX) / 2.0 - eyes.xCoord;
        double cz = (box.minZ + box.maxZ) / 2.0 - eyes.zCoord;
        double cross = cx * look.zCoord - cz * look.xCoord;
        return cross >= 0.0 ? 1 : -1;
    }

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

    private static double halfWidthAcross(AxisAlignedBB box, double ax, double az) {
        return Math.abs(ax) * (box.maxX - box.minX) / 2.0 + Math.abs(az) * (box.maxZ - box.minZ) / 2.0;
    }
}
