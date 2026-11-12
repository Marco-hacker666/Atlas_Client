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
     *
     * Everything else is the base's (2026-11-12: only the boxes and the
     * slipperiness were passed on, so in such a world the water, the ladders
     * and the cobwebs were gone). The new cell is a full cube and no longer a
     * ladder or slime; a liquid or a cobweb it replaces is still counted, as
     * those are asked for by area.
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

            private boolean added(int cellX, int cellY, int cellZ) {
                return cellX == x && cellY == y && cellZ == z;
            }

            @Override
            public boolean water(AxisAlignedBB area, double[] flow) {
                return base.water(area, flow);
            }

            @Override
            public boolean lava(AxisAlignedBB area) {
                return base.lava(area);
            }

            @Override
            public boolean liquid(AxisAlignedBB area) {
                return base.liquid(area);
            }

            @Override
            public boolean climbable(int cellX, int cellY, int cellZ) {
                return !added(cellX, cellY, cellZ) && base.climbable(cellX, cellY, cellZ);
            }

            @Override
            public boolean slime(int cellX, int cellY, int cellZ) {
                return !added(cellX, cellY, cellZ) && base.slime(cellX, cellY, cellZ);
            }

            @Override
            public int touching(AxisAlignedBB area, PlayerSim.Touch kind) {
                return base.touching(area, kind);
            }

            @Override
            public boolean solid(int cellX, int cellY, int cellZ) {
                return added(cellX, cellY, cellZ) || base.solid(cellX, cellY, cellZ);
            }

            @Override
            public boolean loaded(int cellX, int cellZ) {
                return base.loaded(cellX, cellZ);
            }
        };
    }
}
