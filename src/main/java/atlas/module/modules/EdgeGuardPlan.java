package atlas.module.modules;

import net.minecraft.util.AxisAlignedBB;

import java.util.List;

final class EdgeGuardPlan {
    static final double GROUND_FRICTION = 0.6 * 0.91;
    static final double AIR_FRICTION = 0.91;
    static final double WALK = 0.1;
    static final double SPRINT = 0.13;
    static final double AIR_PUSH = 0.02;
    static final int FALL_TICKS = 60;
    static final int DIRECTIONS = 8;

    interface World {
        List<AxisAlignedBB> boxes(AxisAlignedBB region);
    }

    static final class Settings {
        int maxTicks = 4;

        double maxDrop = 6.0;

        boolean sprint = true;

        boolean blockRescue = true;
    }

    enum Kind {
        RELEASE,

        HOLD,

        GIVE_UP
    }

    static final class Decision {
        final Kind kind;

        final float yaw;

        final int ticks;
        final String why;

        Decision(Kind kind, float yaw, int ticks, String why) {
            this.kind = kind;
            this.yaw = yaw;
            this.ticks = ticks;
            this.why = why;
        }

        boolean standStill() {
            return Float.isNaN(this.yaw);
        }
    }

    static final class Fall {
        final boolean safe;
        final int ticks;
        final double drop;

        Fall(boolean safe, int ticks, double drop) {
            this.safe = safe;
            this.ticks = ticks;
            this.drop = drop;
        }
    }

    private EdgeGuardPlan() {
    }

    static Decision decide(World world, AxisAlignedBB box, double kx, double ky, double kz, float yaw, Settings s) {
        if (fall(world, box, kx, ky, kz, Float.NaN, s.maxDrop).safe) {
            return new Decision(Kind.RELEASE, Float.NaN, 0, "lands anyway");
        }
        Decision best = null;

        for (int n = 0; n <= s.maxTicks && best == null; n++) {
            for (int d = 0; d < DIRECTIONS; d++) {
                float walkYaw = d * 45.0F;
                boolean sprint = s.sprint && Math.abs(wrap(walkYaw - yaw)) < 45.0F;
                AxisAlignedBB after = n == 0 ? box : walk(world, box, walkYaw, n, sprint);
                if (after == null) {
                    continue;
                }
                if (fall(world, after, kx, ky, kz, walkYaw, s.maxDrop).safe) {
                    best = new Decision(n == 0 ? Kind.RELEASE : Kind.HOLD, walkYaw, n,
                            (n == 0 ? "steer toward " : "walk " + n + "t toward ") + (int) walkYaw);
                    break;
                }
            }
        }
        if (best != null) {
            return best;
        }
        if (s.blockRescue && s.maxTicks > 0) {
            return new Decision(Kind.HOLD, Float.NaN, s.maxTicks, "no walk saves it: hold for a block");
        }
        return new Decision(Kind.GIVE_UP, Float.NaN, 0, "nothing saves it");
    }

    static boolean safeNow(World world, AxisAlignedBB box, double kx, double ky, double kz, float steerYaw,
                           Settings s) {
        return fall(world, box, kx, ky, kz, steerYaw, s.maxDrop).safe;
    }

    static AxisAlignedBB walk(World world, AxisAlignedBB box, float walkYaw, int n, boolean sprint) {
        double accel = (sprint ? SPRINT : WALK) * (0.16277136 / (GROUND_FRICTION * GROUND_FRICTION * GROUND_FRICTION));
        double rad = Math.toRadians(walkYaw);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        double mx = 0.0;
        double mz = 0.0;
        for (int t = 0; t < n; t++) {
            mx += dirX * accel;
            mz += dirZ * accel;
            double[] m = {mx, -0.0784, mz};
            box = move(world, box, m);
            if (m[1] == -0.0784) {
                return null;
            }
            mx = m[0] * GROUND_FRICTION;
            mz = m[2] * GROUND_FRICTION;
        }

        double[] down = {0.0, -0.0784, 0.0};
        move(world, box, down);
        return down[1] == -0.0784 ? null : box;
    }

    static Fall fall(World world, AxisAlignedBB box, double kx, double ky, double kz, float steerYaw, double maxDrop) {
        double startY = box.minY;
        double mx = kx;
        double my = ky;
        double mz = kz;
        boolean ground = true;
        double sx = 0.0;
        double sz = 0.0;
        if (!Float.isNaN(steerYaw)) {
            double rad = Math.toRadians(steerYaw);
            sx = -Math.sin(rad) * AIR_PUSH;
            sz = Math.cos(rad) * AIR_PUSH;
        }
        for (int t = 0; t < FALL_TICKS; t++) {
            if (!ground) {
                mx += sx;
                mz += sz;
            }
            double oy = my;
            double[] m = {mx, my, mz};
            box = move(world, box, m);
            boolean landed = oy < 0.0 && m[1] != oy;
            if (landed) {
                double drop = startY - box.minY;
                return new Fall(drop <= maxDrop, t, drop);
            }
            if (startY - box.minY > maxDrop) {
                return new Fall(false, t, startY - box.minY);
            }
            double friction = ground ? GROUND_FRICTION : AIR_FRICTION;
            ground = false;
            mx = m[0] != mx ? 0.0 : m[0] * friction;
            mz = m[2] != mz ? 0.0 : m[2] * friction;
            my = m[1] != oy ? 0.0 : (m[1] - 0.08) * 0.98;
        }
        return new Fall(false, FALL_TICKS, startY - box.minY);
    }

    static AxisAlignedBB move(World world, AxisAlignedBB box, double[] m) {
        List<AxisAlignedBB> hits = world.boxes(box.addCoord(m[0], m[1], m[2]));
        double y = m[1];
        for (AxisAlignedBB hit : hits) {
            y = hit.calculateYOffset(box, y);
        }
        box = box.offset(0.0, y, 0.0);
        double x = m[0];
        for (AxisAlignedBB hit : hits) {
            x = hit.calculateXOffset(box, x);
        }
        box = box.offset(x, 0.0, 0.0);
        double z = m[2];
        for (AxisAlignedBB hit : hits) {
            z = hit.calculateZOffset(box, z);
        }
        box = box.offset(0.0, 0.0, z);
        m[0] = x;
        m[1] = y;
        m[2] = z;
        return box;
    }

    static int budgetTicks(int pingMs, int latencyBudgetMs, int maxTicks) {
        int ping = Math.max(0, pingMs);
        int left = (latencyBudgetMs - ping) / 50;
        return Math.max(0, Math.min(maxTicks, left));
    }

    static float wrap(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }
}
