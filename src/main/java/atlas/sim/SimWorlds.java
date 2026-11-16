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

    /**
     * {@code base} answering its area questions from a bigger area asked once (2026-11-16). A simulation run many
     * times from one place -- a fit's candidates, a forecast's ways out -- asks for the collision boxes, the water,
     * the lava and the cobwebs of nearly the same few blocks hundreds of times, and in the game each answer scans
     * every block of its area again. Here the first question about an area asks the base about a region grown round
     * it (blocks: {@link #GROW} to each side, {@link #GROW_Y} up and down), and every later area inside that region
     * is answered from it: the boxes that touch the area are the region's boxes that touch it, and a region with no
     * water (lava, liquid, cobweb, soul sand) has none in any area inside it. Where the region has some, the base is
     * asked about the area itself, so the answers are the base's, to the bit.
     *
     * That rests on the game's own queries: a box touches the area or not whichever bigger area it was found in
     * (World.getCollidingBoundingBoxes keeps only boxes touching what it was asked about, and no block's box reaches
     * past its own cell sideways or past 1.5 up), and each of the others is true for an area only if it is for any
     * area round it. One question at a time about single blocks (slipperiness, ladders) goes to the base unchanged.
     *
     * For one computation only, one tick: the world must not change while it is used.
     */
    public static PlayerSim.World cached(PlayerSim.World base) {
        return new Cached(base);
    }

    /** How far a cached region reaches past the area first asked about, sideways and up and down, blocks. */
    static final double GROW = 2.0;
    static final double GROW_Y = 1.5;
    /** An area wider than this (a long step, a big knockback's sweep) goes to the base. */
    static final double LARGEST = 4.0;
    /** Regions kept at most; past it the oldest are dropped. */
    static final int REGIONS = 32;

    private static final class Cached implements PlayerSim.World {
        private static final int WATER = 0;
        private static final int LAVA = 1;
        private static final int LIQUID = 2;
        private static final int WEB = 3;
        private static final int SOUL_SAND = 4;

        private final PlayerSim.World base;
        private final List<Region> regions = new ArrayList<Region>();

        Cached(PlayerSim.World base) {
            this.base = base;
        }

        private static final class Region {
            final AxisAlignedBB area;
            List<AxisAlignedBB> boxes;
            /** Per kind: 0 not asked yet, 1 none in the region, 2 some. */
            final byte[] any = new byte[5];

            Region(AxisAlignedBB area) {
                this.area = area;
            }

            boolean holds(AxisAlignedBB a) {
                return a.minX >= this.area.minX && a.maxX <= this.area.maxX && a.minY >= this.area.minY
                        && a.maxY <= this.area.maxY && a.minZ >= this.area.minZ && a.maxZ <= this.area.maxZ;
            }
        }

        /** The region holding this area, made if none does; null for an area too big to be worth it. */
        private Region region(AxisAlignedBB area) {
            for (int i = this.regions.size() - 1; i >= 0; i--) {
                if (this.regions.get(i).holds(area)) {
                    return this.regions.get(i);
                }
            }
            if (area.maxX - area.minX > LARGEST || area.maxY - area.minY > LARGEST
                    || area.maxZ - area.minZ > LARGEST) {
                return null;
            }
            if (this.regions.size() >= REGIONS) {
                this.regions.remove(0);
            }
            Region made = new Region(area.expand(GROW, GROW_Y, GROW));
            this.regions.add(made);
            return made;
        }

        /** Whether the region has none of this kind; asked of the base once per region. */
        private boolean none(Region region, int kind) {
            if (region.any[kind] == 0) {
                boolean some;
                switch (kind) {
                    case WATER:
                        some = this.base.water(region.area, new double[3]);
                        break;
                    case LAVA:
                        some = this.base.lava(region.area);
                        break;
                    case LIQUID:
                        some = this.base.liquid(region.area);
                        break;
                    case WEB:
                        some = this.base.touching(region.area, PlayerSim.Touch.WEB) > 0;
                        break;
                    default:
                        some = this.base.touching(region.area, PlayerSim.Touch.SOUL_SAND) > 0;
                        break;
                }
                region.any[kind] = (byte) (some ? 2 : 1);
            }
            return region.any[kind] == 1;
        }

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            Region region = this.region(area);
            if (region == null) {
                return this.base.boxes(area);
            }
            if (region.boxes == null) {
                region.boxes = this.base.boxes(region.area);
            }
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            for (AxisAlignedBB box : region.boxes) {
                if (box.intersectsWith(area)) {
                    out.add(box);
                }
            }
            return out;
        }

        @Override
        public boolean water(AxisAlignedBB area, double[] flow) {
            Region region = this.region(area);
            return (region == null || !this.none(region, WATER)) && this.base.water(area, flow);
        }

        @Override
        public boolean lava(AxisAlignedBB area) {
            Region region = this.region(area);
            return (region == null || !this.none(region, LAVA)) && this.base.lava(area);
        }

        @Override
        public boolean liquid(AxisAlignedBB area) {
            Region region = this.region(area);
            return (region == null || !this.none(region, LIQUID)) && this.base.liquid(area);
        }

        @Override
        public int touching(AxisAlignedBB area, PlayerSim.Touch kind) {
            Region region = this.region(area);
            if (region != null && this.none(region, kind == PlayerSim.Touch.WEB ? WEB : SOUL_SAND)) {
                return 0;
            }
            return this.base.touching(area, kind);
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return this.base.slipperiness(x, y, z);
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return this.base.climbable(x, y, z);
        }

        @Override
        public boolean slime(int x, int y, int z) {
            return this.base.slime(x, y, z);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return this.base.solid(x, y, z);
        }

        @Override
        public boolean loaded(int x, int z) {
            return this.base.loaded(x, z);
        }
    }
}
