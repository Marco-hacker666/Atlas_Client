package atlas.util;

import java.util.List;

public final class DodgeAim {
    static final double[] SIDE = {0.0, -0.15, 0.15, -0.3, 0.3, -0.45, 0.45};

    static final double[] UP = {0.0, 0.3, 0.6, -0.3};

    static final double SPREAD = 0.0075;

    public static final class Result {
        public final ProjectileSolver.Aim aim;

        public final int covered;
        public final int total;

        public final double side;
        public final double up;

        public final double weight;

        Result(ProjectileSolver.Aim aim, int covered, int total, double side, double up, double weight) {
            this.aim = aim;
            this.covered = covered;
            this.total = total;
            this.side = side;
            this.up = up;
            this.weight = weight;
        }
    }

    private DodgeAim() {
    }

    public static ProjectileSolver.Kind safe(ProjectileSolver.Kind kind, double distance) {
        double shrink = Math.min(0.2, SPREAD * Math.max(0.0, distance));
        return new ProjectileSolver.Kind(kind.gravity, kind.drag, Math.max(0.05, kind.margin - shrink));
    }

    public static Result best(double eyeX, double eyeY, double eyeZ, ProjectileSolver.Aim base, double speed,
                              ProjectileSolver.Kind kind, ProjectileSolver.Target baseline,
                              List<ProjectileSolver.Target> dodges, double keepSide, double keepUp) {
        return best(eyeX, eyeY, eyeZ, base, speed, kind, baseline, dodges, keepSide, keepUp, null);
    }

    public static Result best(double eyeX, double eyeY, double eyeZ, ProjectileSolver.Aim base, double speed,
                              ProjectileSolver.Kind kind, ProjectileSolver.Target baseline,
                              List<ProjectileSolver.Target> dodges, double keepSide, double keepUp, double[] weights) {
        double[] box = baseline.box(Math.max(0.0, base.ticks - 0.5));
        double dx = (box[0] + box[3]) / 2.0 - eyeX;
        double dy = (box[1] + box[4]) / 2.0 - eyeY;
        double dz = (box[2] + box[5]) / 2.0 - eyeZ;
        double across = Math.max(0.5, Math.hypot(dx, dz));
        ProjectileSolver.Kind safe = safe(kind, Math.sqrt(dx * dx + dy * dy + dz * dz));
        int maxTicks = flightTicks(base.ticks);

        boolean[] middle = hits(eyeX, eyeY, eyeZ, base.yaw, base.pitch, speed, kind, dodges, maxTicks);
        boolean[] middleSafe = hits(eyeX, eyeY, eyeZ, base.yaw, base.pitch, speed, safe, dodges, maxTicks);
        Result best = new Result(base, count(middleSafe), dodges.size(), 0.0, 0.0, weigh(middleSafe, weights));
        double bestScore = weigh(middleSafe, weights) + weigh(middle, weights);
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
                boolean[] safeHits = hits(eyeX, eyeY, eyeZ, yaw, level.pitch, speed, safe, dodges, maxTicks);
                double score = weigh(safeHits, weights) + weigh(reached, weights);
                double keep = Math.hypot(side - keepSide, up - keepUp);
                if (score > bestScore + 1.0E-9 || Math.abs(score - bestScore) <= 1.0E-9 && keep < bestKeep - 1.0E-9) {
                    best = new Result(new ProjectileSolver.Aim(yaw, level.pitch, level.ticks, base.lob),
                            count(safeHits), dodges.size(), side, up, weigh(safeHits, weights));
                    bestScore = score;
                    bestKeep = keep;
                }
            }
        }
        return best;
    }

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

    private static double weigh(boolean[] hits, double[] weights) {
        double sum = 0.0;
        for (int i = 0; i < hits.length; i++) {
            if (hits[i]) {
                sum += weights == null || i >= weights.length ? 1.0 : weights[i];
            }
        }
        return sum;
    }

    public static boolean[] hitsEach(double eyeX, double eyeY, double eyeZ, float yaw, float pitch, double speed,
                                     ProjectileSolver.Kind kind, List<ProjectileSolver.Target> dodges, int maxTicks) {
        return hits(eyeX, eyeY, eyeZ, yaw, pitch, speed, kind, dodges, maxTicks);
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

    public static int flightTicks(double ticksToTarget) {
        return (int) Math.min(ProjectileSolver.MAX_TICKS, Math.ceil(ticksToTarget * 1.3) + 6.0);
    }
}
