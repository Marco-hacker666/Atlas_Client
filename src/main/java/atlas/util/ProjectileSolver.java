package atlas.util;

public final class ProjectileSolver {
    public static final class Kind {
        public final double gravity;
        public final double drag;

        public final double margin;

        public Kind(double gravity, double drag, double margin) {
            this.gravity = gravity;
            this.drag = drag;
            this.margin = margin;
        }
    }

    public static final Kind ARROW = new Kind(0.05, 0.99, 0.3);
    public static final Kind THROWN = new Kind(0.03, 0.99, 0.3);

    public static final double THROWN_SPEED = 1.5;

    public static final int MAX_TICKS = 120;

    public interface Target {
        double[] box(double ticks);
    }

    public interface Blocks {
        double hit(double x1, double y1, double z1, double x2, double y2, double z2);
    }

    public static final Blocks OPEN = (x1, y1, z1, x2, y2, z2) -> 2.0;

    public static final class Aim {
        public final float yaw;
        public final float pitch;

        public final double ticks;

        public final boolean lob;

        Aim(float yaw, float pitch, double ticks, boolean lob) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.ticks = ticks;
            this.lob = lob;
        }
    }

    public static final class Flight {
        public int hitTick = -1;

        public boolean blocked;

        public final java.util.List<double[]> path = new java.util.ArrayList<double[]>();

        public boolean hits() {
            return this.hitTick > 0;
        }
    }

    private ProjectileSolver() {
    }

    public static double bowSpeed(int useTicks) {
        double f = useTicks / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        if (f < 0.1) {
            return 0.0;
        }
        return Math.min(f, 1.0) * 3.0;
    }

    public static double[] start(double eyeX, double eyeY, double eyeZ, float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{eyeX - Math.cos(r) * 0.16, eyeY - 0.1, eyeZ - Math.sin(r) * 0.16};
    }

    public static double[] direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    public static Flight fly(double eyeX, double eyeY, double eyeZ, float yaw, float pitch, double speed, Kind kind,
                             Target target, Blocks blocks, int maxTicks) {
        Flight flight = new Flight();
        double[] pos = start(eyeX, eyeY, eyeZ, yaw);
        double[] dir = direction(yaw, pitch);
        double mx = dir[0] * speed;
        double my = dir[1] * speed;
        double mz = dir[2] * speed;
        double x = pos[0];
        double y = pos[1];
        double z = pos[2];
        flight.path.add(new double[]{x, y, z});
        for (int tick = 1; tick <= maxTicks; tick++) {
            double nx = x + mx;
            double ny = y + my;
            double nz = z + mz;
            double block = blocks == null ? 2.0 : blocks.hit(x, y, z, nx, ny, nz);
            double reach = Math.min(1.0, block);
            if (target != null) {
                double[] box = target.box(tick - 1);
                if (box != null) {
                    double t = intercept(x, y, z, nx, ny, nz, box, kind.margin);
                    if (t >= 0.0 && t <= reach) {
                        flight.hitTick = tick;
                        flight.path.add(new double[]{x + (nx - x) * t, y + (ny - y) * t, z + (nz - z) * t});
                        return flight;
                    }
                }
            }
            if (block <= 1.0) {
                flight.blocked = true;
                flight.path.add(new double[]{x + (nx - x) * block, y + (ny - y) * block, z + (nz - z) * block});
                return flight;
            }
            x = nx;
            y = ny;
            z = nz;
            flight.path.add(new double[]{x, y, z});
            mx *= kind.drag;
            my *= kind.drag;
            mz *= kind.drag;
            my -= kind.gravity;
            if (y < -64.0) {
                break;
            }
        }
        return flight;
    }

    static double intercept(double x1, double y1, double z1, double x2, double y2, double z2, double[] box,
                            double margin) {
        double[] min = {box[0] - margin, box[1] - margin, box[2] - margin};
        double[] max = {box[3] + margin, box[4] + margin, box[5] + margin};
        double[] a = {x1, y1, z1};
        double[] d = {x2 - x1, y2 - y1, z2 - z1};
        double enter = 0.0;
        double leave = 1.0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-12) {
                if (a[i] < min[i] || a[i] > max[i]) {
                    return -1.0;
                }
                continue;
            }
            double t1 = (min[i] - a[i]) / d[i];
            double t2 = (max[i] - a[i]) / d[i];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            enter = Math.max(enter, t1);
            leave = Math.min(leave, t2);
            if (enter > leave) {
                return -1.0;
            }
        }
        return enter;
    }

    public static Aim solve(double eyeX, double eyeY, double eyeZ, double speed, Kind kind, Target target,
                            boolean lob) {
        if (speed <= 0.0 || target == null) {
            return null;
        }
        double ticks = 0.0;
        float yaw = 0.0F;
        float pitch = 0.0F;
        boolean found = false;
        for (int round = 0; round < 12; round++) {
            double[] box = target.box(Math.max(0.0, ticks - 0.5));
            if (box == null) {
                return null;
            }
            double tx = (box[0] + box[3]) / 2.0;
            double ty = (box[1] + box[4]) / 2.0;
            double tz = (box[2] + box[5]) / 2.0;

            float y = yawTo(tx - eyeX, tz - eyeZ);
            double[] start = start(eyeX, eyeY, eyeZ, y);
            for (int i = 0; i < 3; i++) {
                y = yawTo(tx - start[0], tz - start[2]);
                start = start(eyeX, eyeY, eyeZ, y);
            }
            double horizontal = Math.hypot(tx - start[0], tz - start[2]);
            double rise = ty - start[1];
            double[] arc = pitchFor(horizontal, rise, speed, kind, lob);
            if (arc == null) {
                return null;
            }
            boolean settled = found && Math.abs(arc[1] - ticks) < 0.05;
            yaw = y;
            pitch = (float) arc[0];
            ticks = arc[1];
            found = true;
            if (settled) {
                break;
            }
        }
        return found ? new Aim(wrap(yaw), pitch, ticks, lob) : null;
    }

    static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static float wrap(float yaw) {
        float w = yaw % 360.0F;
        if (w >= 180.0F) {
            w -= 360.0F;
        }
        if (w < -180.0F) {
            w += 360.0F;
        }
        return w;
    }

    static double[] pitchFor(double horizontal, double rise, double speed, Kind kind, boolean lob) {
        double low = -89.9;
        double high = 89.9;

        double a = low;
        double b = high;
        for (int i = 0; i < 80; i++) {
            double m1 = a + (b - a) / 3.0;
            double m2 = b - (b - a) / 3.0;
            if (heightAt(m1, horizontal, speed, kind) < heightAt(m2, horizontal, speed, kind)) {
                a = m1;
            } else {
                b = m2;
            }
        }
        double peak = (a + b) / 2.0;
        if (heightAt(peak, horizontal, speed, kind) < rise) {
            return null;
        }
        double from = lob ? peak : low;
        double to = lob ? high : peak;

        for (int i = 0; i < 80; i++) {
            double mid = (from + to) / 2.0;
            boolean above = heightAt(mid, horizontal, speed, kind) > rise;
            if (above == !lob) {
                to = mid;
            } else {
                from = mid;
            }
        }
        double elevation = (from + to) / 2.0;
        double ticks = ticksAt(elevation, horizontal, speed, kind);
        if (Double.isNaN(ticks) || ticks > MAX_TICKS) {
            return null;
        }
        return new double[]{-elevation, ticks};
    }

    static double ticksAt(double elevation, double horizontal, double speed, Kind kind) {
        double across = speed * Math.cos(Math.toRadians(elevation));
        double d = kind.drag;
        double share = horizontal * (1.0 - d) / Math.max(1.0E-9, across);
        if (share >= 1.0) {
            return Double.NaN;
        }
        return Math.log(1.0 - share) / Math.log(d);
    }

    static double heightAt(double elevation, double horizontal, double speed, Kind kind) {
        double t = ticksAt(elevation, horizontal, speed, kind);
        if (Double.isNaN(t)) {
            return -1.0E9;
        }
        double d = kind.drag;
        double g = kind.gravity;
        double up = speed * Math.sin(Math.toRadians(elevation));
        double grow = (1.0 - Math.pow(d, t)) / (1.0 - d);
        return (up + g / (1.0 - d)) * grow - g * t / (1.0 - d);
    }
}
