package atlas.sim;

import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.List;

/** Worlds for {@link PlayerSim} made from other worlds (2026-11-11). */
public final class SimWorlds {

    private SimWorlds() {
    }

    /**
     * {@code base} with a full block at this cell as well: the world as it
     * will be once a block about to be placed is down, for "and if it goes
     * down, where does the walk end?".
     */
    public static PlayerSim.World withBlock(final PlayerSim.World base, final int x, final int y, final int z) {
        final AxisAlignedBB block = new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1);
        return new PlayerSim.World() {
            @Override
            public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
                List<AxisAlignedBB> boxes = base.boxes(area);
                if (!block.intersectsWith(area)) {
                    return boxes;
                }
                List<AxisAlignedBB> with = new ArrayList<AxisAlignedBB>(boxes);
                with.add(block);
                return with;
            }

            @Override
            public float slipperiness(int cellX, int cellY, int cellZ) {
                return base.slipperiness(cellX, cellY, cellZ);
            }
        };
    }
}
