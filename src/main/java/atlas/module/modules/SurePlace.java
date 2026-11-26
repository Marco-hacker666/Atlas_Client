package atlas.module.modules;

import atlas.sim.PlayerSim;
import atlas.sim.SimWorlds;
import net.minecraft.util.AxisAlignedBB;

/**
 * When a planned block is needed. The player's next moves are simulated twice, once as the world is and once
 * with the block in its cell; the block is needed from the first tick where it holds the player up (the run
 * with it ends that tick higher than the run without it). A block that only lets a sneaking player walk further
 * is not needed.
 */
final class SurePlace {
    /** The cell holds the player's box now, so the block cannot go there. */
    static final int BLOCKED = -1;

    /** The block does not hold the player up within the ticks looked at. */
    static final int NOT_YET = 0;

    private static final double HELD = 1.0E-6;

    private SurePlace() {
    }

    /**
     * {@link #neededIn} over several possible inputs for the next moves, taken conservatively: the soonest tick
     * any of them needs the block, else NOT_YET (or BLOCKED, which does not depend on the input).
     */
    static int soonest(PlayerSim start, PlayerSim.World world, PlayerSim.Input[] ins, int x, int y, int z, int horizon) {
        int best = NOT_YET;
        for (PlayerSim.Input in : ins) {
            int need = neededIn(start, world, in, x, y, z, horizon);
            if (need == BLOCKED) {
                return BLOCKED;
            }
            if (need > 0 && (best == NOT_YET || need < best)) {
                best = need;
            }
        }
        return best;
    }

    /**
     * @return the first tick (1 = the next move) at which the block holds the player up, {@link #NOT_YET} when
     * it does not within {@code horizon} ticks, or {@link #BLOCKED}
     */
    static int neededIn(PlayerSim start, PlayerSim.World world, PlayerSim.Input in, int x, int y, int z, int horizon) {
        if (start.box.intersectsWith(new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1))) {
            return BLOCKED;
        }
        PlayerSim without = start.copy();
        PlayerSim with = start.copy();
        PlayerSim.World filled = SimWorlds.withBlock(world, x, y, z);
        for (int tick = 1; tick <= horizon; tick++) {
            without.tick(world, in.copy());
            with.tick(filled, in.copy());
            if (with.box.minY > without.box.minY + HELD) {
                return tick;
            }
        }
        return NOT_YET;
    }
}
