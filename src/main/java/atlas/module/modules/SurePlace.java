package atlas.module.modules;

import atlas.sim.PlayerSim;
import atlas.sim.SimWorlds;
import net.minecraft.util.AxisAlignedBB;

final class SurePlace {
    static final int BLOCKED = -1;

    static final int NOT_YET = 0;

    private static final double HELD = 1.0E-6;

    private SurePlace() {
    }

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
