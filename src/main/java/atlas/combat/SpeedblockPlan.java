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

    // ---------------------------------------------------------------- prediction (2026-11-01)

    /** Ground: what a tick of friction leaves of the motion (slipperiness 0.6 x 0.91), and a sprint's push a tick. */
    static final double GROUND_DRAG = 0.546;
    static final double GROUND_SPRINT = 0.13;
    static final double GROUND_WALK = 0.1;
    /** Air: drag, and the push a sprint (or a walk) still gives. */
    static final double AIR_DRAG = 0.91;
    static final double AIR_SPRINT = 0.026;
    static final double AIR_WALK = 0.02;
    /** A look within this of the player counts as chasing them: the look then follows them. */
    static final double CHASE_CONE = 60.0;
    /** Degrees a tick a chaser's look turns to stay on the player. */
    static final double AIM_TURN = 30.0;
    /** Half a player's width: the box's front edge reaches a cell this much before its centre. */
    static final double HALF_WIDTH = 0.3;

    /** Minecraft's yaw of a horizontal direction (0 = +z, 90 = -x). */
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

    /**
     * The angle between where the target runs and where it looks, as the keys make it: W, W+A, A ... are the eight
     * 45-degree directions, so an offset within 15 of one is taken as that key; anything else (knockback, ice) as
     * it is. 0 without a look.
     */
    public static double keyOffset(double motionYaw, double lookYaw) {
        if (Double.isNaN(lookYaw)) {
            return 0.0;
        }
        double offset = wrap(motionYaw - lookYaw);
        double snapped = Math.round(offset / 45.0) * 45.0;
        return Math.abs(offset - snapped) < 15.0 ? snapped : offset;
    }

    /**
     * The target's feet, tick by tick, under vanilla movement: every tick the motion gets the push of the keys it
     * holds -- the same keys relative to its look as now (keyOffset) -- the feet move by the motion, then friction
     * takes its share. A target looking within CHASE_CONE of the player keeps its look on them (a chaser aims at
     * whom it chases), turning at most AIM_TURN a tick, so a strafe that curls in is followed. A target that is
     * not chasing keeps its look and runs on.
     *
     * @param moveX     the target's last move, blocks (server positions, one tick apart)
     * @param lookYaw   its yaw, NaN when unknown (then it runs along its motion)
     * @param sprinting a sprint's push rather than a walk's
     * @return {x, z} of the feet after each of {@code ticks} ticks; empty when it is not moving
     */
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
        /* The motion carried into the next tick: what the last move left after friction. */
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

    /**
     * The cells the target runs into along {@code positions}, in order, with the tick its box first reaches each:
     * the front edge of the box (HALF_WIDTH ahead of the feet along the move) is what meets a block, not the
     * centre. The cell it stands in is left out.
     *
     * @return {x, y, z, tick} (tick 1 = the first position), at most maxCells cells (twice that with doubleHeight)
     */
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

    /**
     * Ticks along the predicted run (which starts from the position the server last sent us) before a block placed
     * now is a wall on the server: that position is half our round trip old, the click takes the other half to
     * arrive, and the server takes a tick. A runner whose move into the cell the server gets after that is stopped
     * there -- by the block on its own screen, or, if it has not drawn it yet, by the server putting it back. The
     * runner's own ping does not enter: its moves reach the server late by the same amount its view of the block
     * does. An unknown ping (0 or less, or absurd) counts as 100 ms.
     */
    public static int serverWallAfterTicks(int ownPingMs) {
        int own = ownPingMs > 0 && ownPingMs < 2000 ? ownPingMs : 100;
        return (int) Math.ceil(own / 50.0) + 1;
    }

    /** Math.floor, without the boxing. */
    private static int floor(double value) {
        int floor = (int) value;
        return value < floor ? floor - 1 : floor;
    }
}
