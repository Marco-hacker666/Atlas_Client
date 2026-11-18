package atlas.module.modules;

import java.util.ArrayList;
import java.util.List;

final class PlacePace {
    static final int SPEED_LENGTH = 8;
    static final int SNEAK_LENGTH = 24;
    static final int LINE_LENGTH = 5;
    static final long SPEED_SNEAK_MS = 8000L;
    static final long SNEAK_SNEAK_MS = 6000L;
    static final long RECENT_JUMP_MS = 750L;
    static final double UNDER_DISTANCE = 1.3;
    static final long OTHER_LAYER_MS = 1000L;
    static final double SNEAK_CHECK_LIMIT_MS = 500.0;

    static final double MARGIN_MS = 40.0;

    static final long ACTIVE_MS = 1500L;

    static final int MIN_SNEAK_TICKS = 3;

    private final List<Long> speedGaps = new ArrayList<Long>();
    private final List<Long> sneakGaps = new ArrayList<Long>();
    private final List<int[]> speedCells = new ArrayList<int[]>();
    private final List<int[]> sneakCells = new ArrayList<int[]>();
    private long lastPlace;
    private long lastAny = Long.MIN_VALUE / 4;
    private long lastSneak = Long.MIN_VALUE / 4;
    private long lastJump = Long.MIN_VALUE / 4;

    void reset() {
        this.speedGaps.clear();
        this.sneakGaps.clear();
        this.speedCells.clear();
        this.sneakCells.clear();
        this.lastPlace = 0L;
        this.lastAny = Long.MIN_VALUE / 4;
        this.lastSneak = Long.MIN_VALUE / 4;
        this.lastJump = Long.MIN_VALUE / 4;
    }

    void sneaking(long now) {
        this.lastSneak = now;
    }

    void jumped(long now) {
        this.lastJump = now;
    }

    void placed(long now, boolean under, boolean sameLayer, int neighbours, int x, int y, int z) {
        this.lastAny = now;
        if (under && neighbours < 2) {
            long gap = gap(now, sameLayer);
            push(this.speedGaps, gap, SPEED_LENGTH);
            push(this.sneakGaps, gap, SNEAK_LENGTH);
            if (sameLayer) {
                this.lastPlace = now;
            }
        } else {
            this.sneakGaps.clear();
            this.sneakCells.clear();
        }
        push(this.speedCells, new int[]{x, y, z}, LINE_LENGTH);
        push(this.sneakCells, new int[]{x, y, z}, SNEAK_LENGTH);
    }

    boolean wouldFlag(long now, float yaw, boolean under, boolean sameLayer, int neighbours, int speedLevel) {
        if (!under || neighbours >= 2) {
            return false;
        }
        long gap = gap(now, sameLayer);
        double divisor = 0.15 * speedLevel * speedLevel + 1.0;
        if (this.speedGaps.size() + 1 >= SPEED_LENGTH) {
            double average = averageWith(this.speedGaps, SPEED_LENGTH, gap);
            if (average < speedLimit(now, yaw) / divisor + MARGIN_MS) {
                return true;
            }
        }
        if (this.sneakGaps.size() + 1 >= SNEAK_LENGTH && now - this.lastSneak > SNEAK_SNEAK_MS
                && strictLine(this.sneakCells)) {
            double average = averageWith(this.sneakGaps, SNEAK_LENGTH, gap);
            return average < SNEAK_CHECK_LIMIT_MS / divisor + MARGIN_MS;
        }
        return false;
    }

    double speedLimit(long now, float yaw) {
        boolean line = looseLine(this.speedCells);
        boolean noSneak = now - this.lastSneak > SPEED_SNEAK_MS;
        boolean jump = now - this.lastJump < RECENT_JUMP_MS;
        boolean ninety = facesNinety(yaw);
        if (line) {
            if (jump) {
                return 300.0;
            }
            if (ninety) {
                return noSneak ? 500.0 : 350.0;
            }
            return noSneak ? 350.0 : 200.0;
        }
        return ninety || noSneak ? 300.0 : 150.0;
    }

    boolean roomForJump() {
        if (this.speedGaps.size() < SPEED_LENGTH - 1) {
            return true;
        }
        double sum = 0.0;
        int from = this.speedGaps.size() - (SPEED_LENGTH - 1);
        for (int i = from; i < this.speedGaps.size(); i++) {
            sum += this.speedGaps.get(i);
        }
        return sum / (SPEED_LENGTH - 1) >= 300.0 + MARGIN_MS;
    }

    boolean jumpWanted(long now, long aheadMs, float yaw, int speedLevel) {
        if (now - this.lastAny >= ACTIVE_MS) {
            return false;
        }
        if (wouldFlag(now + Math.max(0L, aheadMs), yaw, true, true, 1, speedLevel)) {
            return true;
        }
        return this.sneakGaps.size() + SNEAK_ROOM >= SNEAK_LENGTH && now - this.lastSneak > SNEAK_SNEAK_MS
                && strictLine(this.sneakCells);
    }

    static final int SNEAK_ROOM = 4;

    long lastGap(long fallback) {
        return this.speedGaps.isEmpty() ? fallback : this.speedGaps.get(this.speedGaps.size() - 1);
    }

    boolean sneakDue(long now, long every) {
        return now - this.lastAny < ACTIVE_MS && this.speedGaps.size() >= 3 && now - this.lastSneak >= every;
    }

    long sinceSneak(long now) {
        return now - this.lastSneak;
    }

    static boolean facesNinety(float yaw) {
        float left = Math.abs(yaw) % 90.0F;
        return left < 10.0F || left > 80.0F;
    }

    static boolean under(double feetX, double feetY, double feetZ, int x, int y, int z) {
        double dx = x - feetX;
        double dy = y + 1.0 - feetY;
        double dz = z - feetZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < UNDER_DISTANCE;
    }

    static boolean looseLine(List<int[]> cells) {
        return line(cells, 2);
    }

    static boolean strictLine(List<int[]> cells) {
        return line(cells, 0);
    }

    private static boolean line(List<int[]> cells, int layerChanges) {
        boolean sameX = false;
        boolean sameZ = false;
        int[] previous = null;
        int left = layerChanges;
        for (int[] cell : cells) {
            if (previous != null) {
                if (previous[1] != cell[1]) {
                    if (left-- <= 0) {
                        return false;
                    }
                } else {
                    if (previous[0] == cell[0]) {
                        sameX = true;
                    } else if (sameX) {
                        return false;
                    }
                    if (previous[2] == cell[2]) {
                        sameZ = true;
                    } else if (sameZ) {
                        return false;
                    }
                }
            }
            previous = cell;
        }
        return sameX || sameZ;
    }

    private long gap(long now, boolean sameLayer) {
        long gap = now - this.lastPlace;
        return sameLayer ? gap : gap + OTHER_LAYER_MS;
    }

    private static double averageWith(List<Long> gaps, int length, long gap) {
        double sum = gap;
        int from = Math.max(0, gaps.size() - (length - 1));
        for (int i = from; i < gaps.size(); i++) {
            sum += gaps.get(i);
        }
        return sum / (gaps.size() - from + 1);
    }

    private static <T> void push(List<T> list, T value, int length) {
        if (list.size() >= length) {
            list.remove(0);
        }
        list.add(value);
    }
}
