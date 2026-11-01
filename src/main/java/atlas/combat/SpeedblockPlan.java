package atlas.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a speedblock goes, without game types (2026-11-01).
 *
 * A sprint in 1.8 is momentum: walk the target into a block and the server
 * ends its sprint, so the hit it was carrying loses most of its knockback and
 * the player who placed the block gets the first clean hit of the exchange.
 * This class answers the two questions a module needs, from a target's last
 * two positions and nothing else:
 *
 *   approach  how fast it is closing on the player, in blocks a tick. Only a
 *             target that is actually coming in is worth a block.
 *   path      which cells its feet enter next, nearest first. The first is
 *             where the block stops it; the rest are fallbacks for when that
 *             cell cannot be placed (outside reach, no face to click).
 *
 * The cell the target already stands in is never returned: a block there is
 * inside its box, which the server refuses, and a block in the cell it is
 * leaving does nothing.
 */
public final class SpeedblockPlan {
    /** Step along the path, in blocks. Short enough not to skip a cell corner. */
    private static final double STEP = 0.1;

    private SpeedblockPlan() {
    }

    /**
     * Blocks a tick the target closes on the player: positive when it moves
     * toward them, negative when it moves away, 0 when it is the same.
     *
     * @param motionX the target's horizontal velocity, blocks a tick
     * @param dx      the player's position minus the target's, one axis
     */
    public static double approach(double motionX, double motionZ, double dx, double dz) {
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-6) {
            return 0.0;
        }
        /* dx,dz is the player minus the target: the motion closes the gap
           exactly when it points along it. */
        return (motionX * dx + motionZ * dz) / distance;
    }

    /** Horizontal speed, blocks a tick. */
    public static double speed(double motionX, double motionZ) {
        return Math.sqrt(motionX * motionX + motionZ * motionZ);
    }

    /**
     * The cells the target's feet enter along its motion, nearest first and
     * its own cell left out.
     *
     * @param minAhead     distance along the motion before a cell is worth one:
     *                     a block must reach the target's client before it does,
     *                     so a cell it is about to enter now is often too late
     * @param maxAhead     how far along the motion to look for the next cell
     * @param maxCells     at most this many cells (two is the useful number)
     * @param doubleHeight also the cell above each one, for a two-high wall
     * @return the cells as {x, y, z}; empty when the target does not move
     */
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

    /** Math.floor, without the boxing. */
    private static int floor(double value) {
        int floor = (int) value;
        return value < floor ? floor - 1 : floor;
    }
}
