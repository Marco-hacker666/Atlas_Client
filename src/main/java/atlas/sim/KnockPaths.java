package atlas.sim;

import net.minecraft.util.AxisAlignedBB;

public final class KnockPaths {
    public static final double BASE = 0.4;
    public static final double PER_LEVEL = 0.5;
    public static final double HIT_BORDER = 0.1;
    public static final int VOID_DEPTH = 12;
    public static final double VOID_SCORE = 100.0;

    private KnockPaths() {
    }

    public static final class Choice {
        public final float yaw;
        public final double score;
        public final double baseScore;
        public final boolean lethal;

        Choice(float yaw, double score, double baseScore, boolean lethal) {
            this.yaw = yaw;
            this.score = score;
            this.baseScore = baseScore;
            this.lethal = lethal;
        }
    }

    public static double[] knock(double attackerX, double attackerZ, double targetX, double targetZ, float yaw,
                                 int level) {
        double dx = attackerX - targetX;
        double dz = attackerZ - targetZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double vx = 0.0;
        double vz = 0.0;
        if (len > 1.0E-4) {
            vx = -dx / len * BASE;
            vz = -dz / len * BASE;
        }
        double vy = BASE;
        if (level > 0) {
            double r = Math.toRadians(yaw);
            vx += -Math.sin(r) * level * PER_LEVEL;
            vz += Math.cos(r) * level * PER_LEVEL;
            vy += 0.1;
        }
        return new double[]{vx, vy, vz};
    }

    public static double hitDistance(double ex, double ey, double ez, float yaw, float pitch, AxisAlignedBB box) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double dx = -Math.sin(yr) * Math.cos(pr);
        double dy = -Math.sin(pr);
        double dz = Math.cos(yr) * Math.cos(pr);
        AxisAlignedBB b = box.expand(HIT_BORDER, HIT_BORDER, HIT_BORDER);
        double near = 0.0;
        double far = Double.MAX_VALUE;
        double[] o = {ex, ey, ez};
        double[] d = {dx, dy, dz};
        double[] lo = {b.minX, b.minY, b.minZ};
        double[] hi = {b.maxX, b.maxY, b.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return Double.NaN;
                }
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) {
                return Double.NaN;
            }
        }
        return near;
    }

    public static float[] window(double ex, double ey, double ez, float pitch, AxisAlignedBB box, double reach,
                                 float around, float span, int samples) {
        int n = Math.max(2, samples);
        float[] found = new float[n + 1];
        int count = 0;
        double base = hitDistance(ex, ey, ez, around, pitch, box);
        if (!Double.isNaN(base) && base <= reach) {
            found[count++] = around;
        }
        for (int i = 0; i < n; i++) {
            float yaw = around - span + 2.0F * span * i / (n - 1);
            double t = hitDistance(ex, ey, ez, yaw, pitch, box);
            if (!Double.isNaN(t) && t <= reach) {
                found[count++] = yaw;
            }
        }
        float[] out = new float[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    public static boolean overVoid(PlayerSim.World world, PlayerSim sim, int depth) {
        AxisAlignedBB column = new AxisAlignedBB(sim.box.minX, sim.box.minY - depth, sim.box.minZ,
                sim.box.maxX, sim.box.minY, sim.box.maxZ);
        return world.boxes(column).isEmpty();
    }

    public static double fall(PlayerSim.World world, PlayerSim start, double[] velocity, PlayerSim.Input in,
                              int ticks) {
        PlayerSim sim = start.copy();
        sim.motionX = velocity[0];
        sim.motionY = velocity[1];
        sim.motionZ = velocity[2];
        sim.onGround = false;
        sim.sprinting = false;
        double startY = sim.y();
        for (int i = 0; i < ticks; i++) {
            sim.tick(world, in);
        }
        double drop = Math.max(0.0, startY - sim.y());
        if (!sim.onGround && overVoid(world, sim, VOID_DEPTH)) {
            return VOID_SCORE + drop;
        }
        return drop;
    }

    public static Choice best(PlayerSim.World world, PlayerSim target, double attackerX, double attackerZ,
                              float baseYaw, float[] yaws, int level, PlayerSim.Input[] responses, int ticks) {
        double baseScore = score(world, target, attackerX, attackerZ, baseYaw, level, responses, ticks);
        float bestYaw = baseYaw;
        double bestScore = baseScore;
        for (float yaw : yaws) {
            double s = score(world, target, attackerX, attackerZ, yaw, level, responses, ticks);
            boolean better = s > bestScore + 1.0E-6
                    || Math.abs(s - bestScore) <= 1.0E-6 && Math.abs(yaw - baseYaw) < Math.abs(bestYaw - baseYaw);
            if (better) {
                bestScore = s;
                bestYaw = yaw;
            }
        }
        return new Choice(bestYaw, bestScore, baseScore, bestScore >= VOID_SCORE);
    }

    static double score(PlayerSim.World world, PlayerSim target, double attackerX, double attackerZ, float yaw,
                        int level, PlayerSim.Input[] responses, int ticks) {
        double[] v = knock(attackerX, attackerZ, target.x(), target.z(), yaw, level);
        double worst = Double.MAX_VALUE;
        for (PlayerSim.Input in : responses) {
            worst = Math.min(worst, fall(world, target, v, in, ticks));
        }
        return worst;
    }
}
