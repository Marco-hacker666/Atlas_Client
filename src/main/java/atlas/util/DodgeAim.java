package atlas.util;

import java.util.List;

/**
 * The look that still hits when the target gets out of the way (2026-11-16, owner: "some only move once they see
 * the arrow leave"). Nobody knows which way a player will go, but the ways are few and each one's path can be
 * worked out (atlas.sim.TargetDodges). An arrow hits anything within 0.6 of its line to the side and 1.2 up or
 * down, so one look can cover the target carrying on and several of the ways it might go instead: this picks the
 * look that covers the most.
 *
 * The rules that keep it from ever being worse than aiming at the middle: a look is only taken when it still hits
 * the target carrying on as forecast, and when it hits every dodge the middle hits. Looks are tried a little to
 * either side of the middle and a little above and below it, each flown against every path.
 *
 * A hit is counted twice over: as the game counts it (the box grown by 0.3), and with the box grown by less, by
 * about what the server's random spread comes to at that distance, which leaves out a look that only just hits.
 * The look with the most of both together is taken; {@link Result#covered} is the cautious count.
 *
 * Blocks are left out here (twelve paths times some thirty looks is too many ray traces a tick); the caller flies
 * the look it gets against the blocks once before using it. No game types.
 */
public final class DodgeAim {
    /** How far to the side of the target's middle a look is tried, blocks; the middle first. */
    static final double[] SIDE = {0.0, -0.15, 0.15, -0.3, 0.3, -0.45, 0.45};
    /** How far above it. A jump lifts the feet 1.25: a look 0.3 to 0.6 up still hits the legs of one. */
    static final double[] UP = {0.0, 0.3, 0.6, -0.3};
    /** The server's spread: a gaussian of this on each part of the arrow's unit direction. */
    static final double SPREAD = 0.0075;

    /** The look picked and what it covers. */
    public static final class Result {
        public final ProjectileSolver.Aim aim;
        /** How many of the dodges it still hits with room to spare ({@link #safe}), and how many there were. */
        public final int covered;
        public final int total;
        /** Where it aims, from the target's middle: to the side (blocks, positive to the shooter's right) and up. */
        public final double side;
        public final double up;

        Result(ProjectileSolver.Aim aim, int covered, int total, double side, double up) {
            this.aim = aim;
            this.covered = covered;
            this.total = total;
            this.side = side;
            this.up = up;
        }
    }

    private DodgeAim() {
    }

    /** The projectile with its hit box shrunk by a spread's worth at this distance: a hit that is not a near miss. */
    public static ProjectileSolver.Kind safe(ProjectileSolver.Kind kind, double distance) {
        double shrink = Math.min(0.2, SPREAD * Math.max(0.0, distance));
        return new ProjectileSolver.Kind(kind.gravity, kind.drag, Math.max(0.05, kind.margin - shrink));
    }

    /**
     * @param base     the look that hits the middle of the target carrying on ({@link ProjectileSolver#solve})
     * @param baseline the target carrying on as forecast
     * @param dodges   the target getting out of the way, a path each
     * @param keepSide,keepUp where the last look for this target aimed: among equals the nearest to it is taken,
     *                 so the look does not jump between two that are as good
     * @return the look covering the most dodges among those that still hit the baseline; {@code base} itself when
     *         none does better. Never null.
     */
    public static Result best(double eyeX, double eyeY, double eyeZ, ProjectileSolver.Aim base, double speed,
                              ProjectileSolver.Kind kind, ProjectileSolver.Target baseline,
                              List<ProjectileSolver.Target> dodges, double keepSide, double keepUp) {
        double[] box = baseline.box(Math.max(0.0, base.ticks - 0.5));
        double dx = (box[0] + box[3]) / 2.0 - eyeX;
        double dy = (box[1] + box[4]) / 2.0 - eyeY;
        double dz = (box[2] + box[5]) / 2.0 - eyeZ;
        double across = Math.max(0.5, Math.hypot(dx, dz));
        ProjectileSolver.Kind safe = safe(kind, Math.sqrt(dx * dx + dy * dy + dz * dz));
        int maxTicks = flightTicks(base.ticks);
        /* What the middle hits, as the game counts it: no look that loses one of these is taken. */
        boolean[] middle = hits(eyeX, eyeY, eyeZ, base.yaw, base.pitch, speed, kind, dodges, maxTicks);
        Result best = new Result(base, covered(eyeX, eyeY, eyeZ, base.yaw, base.pitch, speed, safe, dodges, maxTicks),
                dodges.size(), 0.0, 0.0);
        int bestScore = best.covered + count(middle);
        double bestKeep = Math.hypot(keepSide, keepUp);
        for (final double up : UP) {
            ProjectileSolver.Aim level = base;
            if (up != 0.0) {
                final ProjectileSolver.Target inner = baseline;
                level = ProjectileSolver.solve(eyeX, eyeY, eyeZ, speed, kind, ticks -> {
                    double[] b = inner.box(ticks);
                    return b == null ? null : new double[]{b[0], b[1] + up, b[2], b[3], b[4] + up, b[5]};
                }, base.lob);
                if (level == null) {
                    continue;
                }
            }
            for (double side : SIDE) {
                if (up == 0.0 && side == 0.0) {
                    continue;
                }
                /* A yaw further on is further to the shooter's right. */
                float yaw = level.yaw + (float) Math.toDegrees(Math.atan2(side, across));
                if (!ProjectileSolver.fly(eyeX, eyeY, eyeZ, yaw, level.pitch, speed, safe, baseline,
                        ProjectileSolver.OPEN, maxTicks).hits()) {
                    continue;
                }
                boolean[] reached = hits(eyeX, eyeY, eyeZ, yaw, level.pitch, speed, kind, dodges, maxTicks);
                boolean loses = false;
                for (int i = 0; i < middle.length; i++) {
                    loses |= middle[i] && !reached[i];
                }
                if (loses) {
                    continue;
                }
                int count = covered(eyeX, eyeY, eyeZ, yaw, level.pitch, speed, safe, dodges, maxTicks);
                int score = count + count(reached);
                double keep = Math.hypot(side - keepSide, up - keepUp);
                if (score > bestScore || score == bestScore && keep < bestKeep - 1.0E-9) {
                    best = new Result(new ProjectileSolver.Aim(yaw, level.pitch, level.ticks, base.lob), count,
                            dodges.size(), side, up);
                    bestScore = score;
                    bestKeep = keep;
                }
            }
        }
        return best;
    }

    /**
     * How many of the dodges this look, flown, still hits. {@code kind} as given: pass {@link #safe} to leave near
     * misses out.
     */
    public static int covered(double eyeX, double eyeY, double eyeZ, float yaw, float pitch, double speed,
                              ProjectileSolver.Kind kind, List<ProjectileSolver.Target> dodges, int maxTicks) {
        int count = 0;
        for (ProjectileSolver.Target dodge : dodges) {
            if (ProjectileSolver.fly(eyeX, eyeY, eyeZ, yaw, pitch, speed, kind, dodge, ProjectileSolver.OPEN,
                    maxTicks).hits()) {
                count++;
            }
        }
        return count;
    }

    private static boolean[] hits(double eyeX, double eyeY, double eyeZ, float yaw, float pitch, double speed,
                                  ProjectileSolver.Kind kind, List<ProjectileSolver.Target> dodges, int maxTicks) {
        boolean[] out = new boolean[dodges.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProjectileSolver.fly(eyeX, eyeY, eyeZ, yaw, pitch, speed, kind, dodges.get(i),
                    ProjectileSolver.OPEN, maxTicks).hits();
        }
        return out;
    }

    private static int count(boolean[] hits) {
        int count = 0;
        for (boolean hit : hits) {
            if (hit) {
                count++;
            }
        }
        return count;
    }

    /** How long a flight is followed: the time to the target and a margin for one that moved away. */
    public static int flightTicks(double ticksToTarget) {
        return (int) Math.min(ProjectileSolver.MAX_TICKS, Math.ceil(ticksToTarget * 1.3) + 6.0);
    }
}
