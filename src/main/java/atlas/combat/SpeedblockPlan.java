package atlas.combat;

import java.util.ArrayList;
import java.util.List;

public final class SpeedblockPlan {
    private static final double STEP = 0.1;

    private SpeedblockPlan() {
    }

    public static double approach(double motionX, double motionZ, double dx, double dz) {
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-6) {
            return 0.0;
        }

        return (motionX * dx + motionZ * dz) / distance;
    }

    public static double speed(double motionX, double motionZ) {
        return Math.sqrt(motionX * motionX + motionZ * motionZ);
    }

    public static List<int[]> path(double x, double z, int feetY, double motionX, double motionZ,
                                   double minAhead, double maxAhead, int maxCells, boolean doubleHeight) {
        List<int[]> out = new ArrayList<int[]>();
        double length = speed(motionX, motionZ);
        if (length < 1.0E-6 || maxCells <= 0 || maxAhead <= 0.0) {
            return out;
        }
        int startX = floor(x);
        int startZ = floor(z);
        int lastX = startX;
        int lastZ = startZ;
        int perCell = doubleHeight ? 2 : 1;
        for (double travelled = STEP; travelled <= maxAhead + 1.0E-9; travelled += STEP) {
            int cellX = floor(x + motionX / length * travelled);
            int cellZ = floor(z + motionZ / length * travelled);
            if (cellX == lastX && cellZ == lastZ) {
                continue;
            }
            lastX = cellX;
            lastZ = cellZ;
            if (travelled < minAhead || cellX == startX && cellZ == startZ) {
                continue;
            }
            out.add(new int[]{cellX, feetY, cellZ});
            if (doubleHeight) {
                out.add(new int[]{cellX, feetY + 1, cellZ});
            }
            if (out.size() >= maxCells * perCell) {
                break;
            }
        }
        return out;
    }

    static final double INPUT = 0.98;

    static final double GROUND_DRAG = 0.546;
    static final double GROUND_SPRINT = 0.13 * INPUT;
    static final double GROUND_WALK = 0.1 * INPUT;

    static final double AIR_DRAG = 0.91;
    static final double AIR_SPRINT = 0.026 * INPUT;
    static final double AIR_WALK = 0.02 * INPUT;

    static final double CHASE_CONE = 60.0;

    static final double AIM_TURN = 30.0;

    static final double HALF_WIDTH = 0.3;

    public static double yawOf(double dx, double dz) {
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    public static double keyOffset(double motionYaw, double lookYaw) {
        if (Double.isNaN(lookYaw)) {
            return 0.0;
        }
        double offset = wrap(motionYaw - lookYaw);
        double snapped = Math.round(offset / 45.0) * 45.0;
        return Math.abs(offset - snapped) < 15.0 ? snapped : offset;
    }

    public static double[][] simulate(double x, double z, double moveX, double moveZ, double lookYaw,
                                      double playerX, double playerZ, boolean onGround, boolean sprinting, int ticks) {
        double speed = speed(moveX, moveZ);
        if (speed < 1.0E-6 || ticks <= 0) {
            return new double[0][];
        }
        double drag = onGround ? GROUND_DRAG : AIR_DRAG;
        double push = onGround ? (sprinting ? GROUND_SPRINT : GROUND_WALK) : (sprinting ? AIR_SPRINT : AIR_WALK);
        double motionYaw = yawOf(moveX, moveZ);
        double look = Double.isNaN(lookYaw) ? motionYaw : lookYaw;
        double offset = keyOffset(motionYaw, lookYaw);

        double mx = moveX * drag;
        double mz = moveZ * drag;
        double[][] out = new double[ticks][];
        for (int t = 0; t < ticks; t++) {
            double toPlayer = yawOf(playerX - x, playerZ - z);
            double off = wrap(toPlayer - look);
            if (Math.abs(off) < CHASE_CONE) {
                look += Math.max(-AIM_TURN, Math.min(AIM_TURN, off));
            }
            double input = Math.toRadians(look + offset);
            mx += -Math.sin(input) * push;
            mz += Math.cos(input) * push;
            x += mx;
            z += mz;
            mx *= drag;
            mz *= drag;
            out[t] = new double[]{x, z};
        }
        return out;
    }

    public static List<int[]> cellsAlong(double x, double z, int feetY, double[][] positions, int maxCells,
                                         boolean doubleHeight) {
        List<int[]> out = new ArrayList<int[]>();
        int startX = floor(x);
        int startZ = floor(z);
        int lastX = startX;
        int lastZ = startZ;
        double px = x;
        double pz = z;
        int perCell = doubleHeight ? 2 : 1;
        for (int t = 0; t < positions.length && out.size() < maxCells * perCell; t++) {
            double nx = positions[t][0];
            double nz = positions[t][1];
            double dx = nx - px;
            double dz = nz - pz;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0E-9) {
                double fx = dx / length * HALF_WIDTH;
                double fz = dz / length * HALF_WIDTH;
                int steps = Math.max(1, (int) Math.ceil(length / STEP));
                for (int i = 1; i <= steps; i++) {
                    double share = i / (double) steps;
                    int cellX = floor(px + dx * share + fx);
                    int cellZ = floor(pz + dz * share + fz);
                    if (cellX == lastX && cellZ == lastZ) {
                        continue;
                    }
                    lastX = cellX;
                    lastZ = cellZ;
                    if (cellX == startX && cellZ == startZ) {
                        continue;
                    }
                    out.add(new int[]{cellX, feetY, cellZ, t + 1});
                    if (doubleHeight) {
                        out.add(new int[]{cellX, feetY + 1, cellZ, t + 1});
                    }
                    if (out.size() >= maxCells * perCell) {
                        break;
                    }
                }
            }
            px = nx;
            pz = nz;
        }
        return out;
    }

    public static int serverWallAfterTicks(int ownPingMs) {
        int own = ownPingMs > 0 && ownPingMs < 2000 ? ownPingMs : 100;
        return (int) Math.ceil(own / 50.0) + 1;
    }

    public static int firstWall(List<int[]> cells, boolean doubleHeight, double playerX, double playerZ,
                                java.util.function.Predicate<int[]> walls) {
        int perCell = doubleHeight ? 2 : 1;
        int ownX = floor(playerX);
        int ownZ = floor(playerZ);
        for (int i = 0; i + perCell - 1 < cells.size(); i += perCell) {
            int[] feet = cells.get(i);
            if (feet[0] == ownX && feet[2] == ownZ) {
                return -1;
            }
            if (walls.test(feet) && (!doubleHeight || walls.test(cells.get(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static int floor(double value) {
        int floor = (int) value;
        return value < floor ? floor - 1 : floor;
    }
}
