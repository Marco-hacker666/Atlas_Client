package atlas.module.modules;

import java.util.ArrayList;
import java.util.List;

/**
 * The block placement timing a server sees, kept on this side so a bridge never goes faster than a human one is
 * allowed to (2026-11-07). Behaviour read from Intave's public PlacementAnalysis (github.com/intave/intave, Speed and
 * Sneak parts); no code of theirs. The rules, as they judge a placement of a block under the player:
 *
 * - "Under the player": the placed block's top corner within 1.3 of the feet, and fewer than two of its four side
 *   neighbours solid. Any other placement clears the Sneak history (the Speed one is kept).
 * - The gap of a placement is the time since the last one on the same layer as its support; a placement against a
 *   block of another height counts a second more.
 * - Speed: the last 8 gaps, averaged, must be at least: blocks in one line (the last 5 placed, a layer change or two
 *   allowed) and a jump in the last 750 ms, 300 ms; one line and the yaw within 10 degrees of a multiple of 90, 500 ms
 *   with no sneak in the last 8 s, else 350; one line otherwise, 350 with no sneak, else 200; not one line, 300 when
 *   facing a multiple of 90 or not sneaking, else 150. Speed potions divide it by 1 + 0.15 level squared.
 * - Sneak: the last 24 gaps under 500 ms on average, in one strict line, with no sneak in the last 6 s.
 *
 * So a straight walk-speed bridge with no sneak flags both, which is why GODBRIDGE never lasted: a human bridging that
 * fast crouches now and then. This keeps the same books from what this client sends, and says whether a placement
 * now, or a jump now, would break them, and when a crouch is due.
 *
 * Pure: times are passed in, in ms.
 */
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
    /** Kept on top of every limit: the server's clock sees the gaps through the network, not as sent. */
    static final double MARGIN_MS = 40.0;
    /** Bridging: a placement this recent. */
    static final long ACTIVE_MS = 1500L;
    /** A crouch shorter than this, close to a placement, reads as automated (SneakAndPlace counts under 2). */
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

    /** The player was crouched this tick. */
    void sneaking(long now) {
        this.lastSneak = now;
    }

    /** The player left the ground in a jump this tick. */
    void jumped(long now) {
        this.lastJump = now;
    }

    /** A block went out: the cell it fills, whether it counts as under the player, on its support's layer, and how many side neighbours the cell has. */
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

    /**
     * Whether this placement now would put either average under its limit (with MARGIN_MS kept).
     *
     * @param yaw        the yaw the server has with it
     * @param speedLevel Speed potion level, 0 for none
     */
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

    /** The Speed part's least average, ms, for a placement now at this yaw (before any potion). */
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

    /** A jump now makes the next 750 ms judged at 300: whether the gaps so far leave room for that. */
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

    /**
     * GODBRIDGE with no crouch (2026-11-08): whether a jump should go before the next block. True when that block,
     * placed under the player on this layer {@code aheadMs} from now, would put either book under its limit (with the
     * margin), or when the Sneak part's strict line is within {@code SNEAK_ROOM} blocks of a full 24 and no crouch will
     * ever start it again. A jump never tightens a limit with no crouch (300 against 350 or 500), and a block placed
     * with the feet high in it is not under the player: Speed leaves it out, Sneak starts again.
     */
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

    /** jumpWanted: this many blocks before the Sneak part's 24 fill. */
    static final int SNEAK_ROOM = 4;

    /** The last gap the Speed part took, ms; {@code fallback} with none yet. */
    long lastGap(long fallback) {
        return this.speedGaps.isEmpty() ? fallback : this.speedGaps.get(this.speedGaps.size() - 1);
    }

    /** Bridging, and the last crouch older than {@code every}: time to crouch once. */
    boolean sneakDue(long now, long every) {
        return now - this.lastAny < ACTIVE_MS && this.speedGaps.size() >= 3 && now - this.lastSneak >= every;
    }

    long sinceSneak(long now) {
        return now - this.lastSneak;
    }

    /** Intave's test: the yaw as sent, its size modulo 90, within 10 of either end. */
    static boolean facesNinety(float yaw) {
        float left = Math.abs(yaw) % 90.0F;
        return left < 10.0F || left > 80.0F;
    }

    /** The placed block's top corner within 1.3 of the feet. */
    static boolean under(double feetX, double feetY, double feetZ, int x, int y, int z) {
        double dx = x - feetX;
        double dy = y + 1.0 - feetY;
        double dz = z - feetZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < UNDER_DISTANCE;
    }

    /** One line along x or z, the way the Speed part reads it: up to two layer changes pass. */
    static boolean looseLine(List<int[]> cells) {
        return line(cells, 2);
    }

    /** The Sneak part's line: no layer change at all. */
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
