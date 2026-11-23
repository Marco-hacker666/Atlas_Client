package atlas.module.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class LinePlacement {
    static final double ALIGNED = 0.5;
    static final double BUCKET = 0.05;
    static final int[][] OFFSETS = offsets();

    private LinePlacement() {
    }

    static final class Line {
        final double x;
        final double z;
        final double dx;
        final double dz;

        Line(double x, double z, double dx, double dz) {
            double len = Math.sqrt(dx * dx + dz * dz);
            this.x = x;
            this.z = z;
            this.dx = len < 1.0E-9 ? 0.0 : dx / len;
            this.dz = len < 1.0E-9 ? 0.0 : dz / len;
        }

        boolean hasDirection() {
            return this.dx != 0.0 || this.dz != 0.0;
        }

        double distance(double px, double pz) {
            double rx = px - this.x;
            double rz = pz - this.z;
            if (!this.hasDirection()) {
                return Math.sqrt(rx * rx + rz * rz);
            }
            return Math.abs(rx * this.dz - rz * this.dx);
        }
    }

    static Line walkingLine(double px, double pz, float moveYaw, boolean moving, int[] last, int[] previous) {
        if (!moving) {
            return new Line(px, pz, 0.0, 0.0);
        }
        double r = Math.toRadians(moveYaw);
        double mx = -Math.sin(r);
        double mz = Math.cos(r);
        if (last != null && previous != null) {
            double bx = last[0] - previous[0];
            double bz = last[2] - previous[2];
            double len = Math.sqrt(bx * bx + bz * bz);
            if (len > 1.0E-9 && last[1] == previous[1]) {
                bx /= len;
                bz /= len;
                double dot = bx * mx + bz * mz;
                if (dot >= ALIGNED) {
                    return new Line(last[0] + 0.5, last[2] + 0.5, bx, bz);
                }
            }
        }
        return new Line(px, pz, mx, mz);
    }

    static List<int[]> candidates(int tx, int ty, int tz, Line line) {
        List<int[]> cells = new ArrayList<int[]>(OFFSETS.length);
        for (int[] o : OFFSETS) {
            cells.add(new int[]{tx + o[0], ty + o[1], tz + o[2]});
        }
        Collections.sort(cells, Comparator.comparingLong(c -> {
            double side = line.distance(c[0] + 0.5, c[2] + 0.5);
            double down = ty - c[1];
            return Math.round(Math.sqrt(side * side + down * down) / BUCKET);
        }));
        return cells;
    }

    static double facing(double ex, double ey, double ez, double fx, double fy, double fz, int nx, int ny, int nz) {
        double vx = ex - fx;
        double vy = ey - fy;
        double vz = ez - fz;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0E-9) {
            return 1.0;
        }
        return (vx * nx + vy * ny + vz * nz) / len;
    }

    static final double STRAY = 0.2;
    static final double STRAY_TOWARDS = 0.075;

    static float stabilizeKey(Line line, double px, double pz, double motionX, double motionZ, float cameraYaw) {
        if (!line.hasDirection()) {
            return 0.0F;
        }
        double rx = px - line.x;
        double rz = pz - line.z;
        double along = rx * line.dx + rz * line.dz;
        double toX = line.x + line.dx * along - px;
        double toZ = line.z + line.dz * along - pz;
        double off = Math.sqrt(toX * toX + toZ * toZ);
        boolean towards = toX * motionX + toZ * motionZ > 0.0;
        if (off <= (towards ? STRAY_TOWARDS : STRAY)) {
            return 0.0F;
        }
        double r = Math.toRadians(cameraYaw);
        double leftX = Math.cos(r);
        double leftZ = Math.sin(r);
        double dot = toX * leftX + toZ * leftZ;
        if (Math.abs(dot) < 1.0E-6) {
            return 0.0F;
        }
        return dot > 0.0 ? 1.0F : -1.0F;
    }

    private static int[][] offsets() {
        List<int[]> list = new ArrayList<int[]>();
        int[] values = {0, -1, 1};
        for (int y : new int[]{0, -1}) {
            for (int x : values) {
                for (int z : values) {
                    list.add(new int[]{x, y, z});
                }
            }
        }
        Collections.sort(list, Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
                .thenComparingInt(o -> -o[1])
                .thenComparingInt(o -> o[0])
                .thenComparingInt(o -> o[2]));
        return list.toArray(new int[0][]);
    }
}
