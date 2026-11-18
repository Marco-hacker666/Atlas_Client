package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SimWorldsCacheTest {
    private static final class Cells implements PlayerSim.World {
        final Map<Long, AxisAlignedBB[]> solid = new HashMap<Long, AxisAlignedBB[]>();
        final Map<Long, Character> kind = new HashMap<Long, Character>();
        int boxQueries;
        int areaQueries;

        static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (long) (z & 0xFFFFF);
        }

        void block(int x, int y, int z, double height) {
            this.solid.put(key(x, y, z), new AxisAlignedBB[]{new AxisAlignedBB(x, y, z, x + 1, y + height, z + 1)});
        }

        void cell(int x, int y, int z, char what) {
            this.kind.put(key(x, y, z), what);
        }

        char at(int x, int y, int z) {
            Character c = this.kind.get(key(x, y, z));
            return c == null ? ' ' : c;
        }

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            this.boxQueries++;
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();

            for (int x = MathHelper.floor_double(area.minX); x < MathHelper.floor_double(area.maxX + 1.0); x++) {
                for (int z = MathHelper.floor_double(area.minZ); z < MathHelper.floor_double(area.maxZ + 1.0); z++) {
                    for (int y = MathHelper.floor_double(area.minY) - 1; y < MathHelper.floor_double(area.maxY + 1.0); y++) {
                        AxisAlignedBB[] boxes = this.solid.get(key(x, y, z));
                        if (boxes != null) {
                            for (AxisAlignedBB box : boxes) {
                                if (area.intersectsWith(box)) {
                                    out.add(box);
                                }
                            }
                        }
                    }
                }
            }
            return out;
        }

        private boolean any(AxisAlignedBB area, char what) {
            this.areaQueries++;
            for (int x = MathHelper.floor_double(area.minX); x < MathHelper.floor_double(area.maxX + 1.0); x++) {
                for (int y = MathHelper.floor_double(area.minY); y < MathHelper.floor_double(area.maxY + 1.0); y++) {
                    for (int z = MathHelper.floor_double(area.minZ); z < MathHelper.floor_double(area.maxZ + 1.0); z++) {
                        char c = this.at(x, y, z);
                        if (what == '*' ? c == 'w' || c == 'l' : c == what) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public boolean water(AxisAlignedBB area, double[] flow) {
            this.areaQueries++;
            boolean in = false;
            int maxY = MathHelper.floor_double(area.maxY + 1.0);
            for (int x = MathHelper.floor_double(area.minX); x < MathHelper.floor_double(area.maxX + 1.0); x++) {
                for (int y = MathHelper.floor_double(area.minY); y < maxY; y++) {
                    for (int z = MathHelper.floor_double(area.minZ); z < MathHelper.floor_double(area.maxZ + 1.0); z++) {
                        if (this.at(x, y, z) == 'w' && maxY >= y + 1 - 0.11) {
                            in = true;

                            flow[0] += 0.01 * ((x * 7 + z) % 3 - 1);
                            flow[2] += 0.01 * ((z * 5 + x) % 3 - 1);
                        }
                    }
                }
            }
            return in;
        }

        @Override
        public boolean lava(AxisAlignedBB area) {
            return this.any(area, 'l');
        }

        @Override
        public boolean liquid(AxisAlignedBB area) {
            return this.any(area, '*');
        }

        @Override
        public int touching(AxisAlignedBB area, PlayerSim.Touch kind) {
            this.areaQueries++;
            char what = kind == PlayerSim.Touch.WEB ? 'c' : 's';
            int count = 0;
            for (int x = MathHelper.floor_double(area.minX); x <= MathHelper.floor_double(area.maxX); x++) {
                for (int y = MathHelper.floor_double(area.minY); y <= MathHelper.floor_double(area.maxY); y++) {
                    for (int z = MathHelper.floor_double(area.minZ); z <= MathHelper.floor_double(area.maxZ); z++) {
                        if (this.at(x, y, z) == what) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return this.at(x, y, z) == 'i' ? 0.98F : 0.6F;
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return this.at(x, y, z) == 'L';
        }

        @Override
        public boolean solid(int x, int y, int z) {
            AxisAlignedBB[] boxes = this.solid.get(key(x, y, z));
            return boxes != null && boxes[0].maxY - boxes[0].minY == 1.0;
        }
    }

    private static Cells course(long seed) {
        Cells world = new Cells();
        Random random = new Random(seed);
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                world.block(x, -1, z, 1.0);
                int roll = random.nextInt(100);
                if (Math.abs(x) + Math.abs(z) < 3) {
                    continue;
                }
                if (roll < 3) {
                    world.block(x, 0, z, 1.0);
                } else if (roll < 5) {
                    world.block(x, 0, z, 0.5);
                } else if (roll < 6) {
                    world.block(x, 0, z, 1.5);
                } else if (roll < 7) {
                    world.block(x, 0, z, 1.0);
                    world.block(x, 1, z, 1.0);
                } else if (roll < 9) {
                    world.cell(x, 0, z, 'w');
                } else if (roll < 10) {
                    world.cell(x, 0, z, 'l');
                } else if (roll < 11) {
                    world.cell(x, 0, z, 'c');
                } else if (roll < 12) {
                    world.block(x, -1, z, 0.875);
                    world.cell(x, -1, z, 's');
                } else if (roll < 13) {
                    world.cell(x, -1, z, 'i');
                } else if (roll < 14) {
                    world.cell(x, 0, z, 'L');
                }
            }
        }
        return world;
    }

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(0.2, 0.0, 0.2, 0.8, 1.8, 0.8);
        sim.onGround = true;
        sim.motionY = -0.0784000015258789;
        return sim;
    }

    private static void same(String where, PlayerSim a, PlayerSim b) {
        assertEquals(where, a.x(), b.x(), 0.0);
        assertEquals(where, a.y(), b.y(), 0.0);
        assertEquals(where, a.z(), b.z(), 0.0);
        assertEquals(where, a.motionX, b.motionX, 0.0);
        assertEquals(where, a.motionY, b.motionY, 0.0);
        assertEquals(where, a.motionZ, b.motionZ, 0.0);
        assertEquals(where, a.onGround, b.onGround);
        assertEquals(where, a.collidedHorizontally, b.collidedHorizontally);
        assertEquals(where, a.inWater, b.inWater);
        assertEquals(where, a.sprinting, b.sprinting);
    }

    @Test
    public void runsOnTheCachedWorldAreTheBasesToTheBit() {
        int baseQueries = 0;
        int cachedQueries = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Cells base = course(seed);
            Cells counted = course(seed);
            PlayerSim.World cached = SimWorlds.cached(counted);
            Random random = new Random(seed * 31);

            for (int run = 0; run < 25; run++) {
                PlayerSim a = standing();
                PlayerSim b = standing();
                PlayerSim.Input in = new PlayerSim.Input();
                for (int tick = 0; tick < 40; tick++) {
                    if (tick % 6 == 0) {
                        in.yaw = random.nextFloat() * 360.0F - 180.0F;
                        in.forward = random.nextInt(3) - 1;
                        in.strafe = random.nextInt(3) - 1;
                        in.jump = random.nextInt(3) == 0;
                        in.sneak = random.nextInt(8) == 0;
                        in.sprint = in.forward > 0 && random.nextBoolean();
                    }
                    a.tick(base, in);
                    b.tick(cached, in);
                    same("seed " + seed + " run " + run + " tick " + tick, a, b);
                }
            }
            baseQueries += base.boxQueries + base.areaQueries;
            cachedQueries += counted.boxQueries + counted.areaQueries;
        }

        assertTrue(cachedQueries + " of " + baseQueries, cachedQueries * 5 < baseQueries);
    }

    @Test
    public void aFollowedPlayerIsForecastTheSameOnIt() {
        Cells base = course(77);
        PlayerSim game = standing();
        PlayerSim.Input in = new PlayerSim.Input();
        in.forward = 1.0F;
        in.sprint = true;
        TargetTrack plain = new TargetTrack();
        TargetTrack onCache = new TargetTrack();
        Random random = new Random(5);
        for (int i = 0; i < 60; i++) {
            if (i % 10 == 0) {
                in.yaw = random.nextFloat() * 360.0F;
                in.jump = random.nextBoolean();
            }
            game.tick(base, in);
            game.tick(base, in);
            TargetFit.Seen seen = new TargetFit.Seen();
            seen.x = MathHelper.floor_double(game.x() * 32.0);
            seen.y = MathHelper.floor_double(game.y() * 32.0);
            seen.z = MathHelper.floor_double(game.z() * 32.0);
            seen.onGround = game.onGround;
            seen.yaw = in.yaw;
            seen.sprinting = game.sprinting;
            plain.observe(seen, base, i == 0 ? 0.0 : 2.0);
            PlayerSim.World cached = SimWorlds.cached(base);
            onCache.observe(seen, cached, i == 0 ? 0.0 : 2.0);
            TargetForecast a = plain.forecast(base, 10);
            TargetForecast b = onCache.forecast(cached, 10);
            assertEquals(a == null, b == null);
            if (a != null) {
                assertEquals(a.simulated, b.simulated);
                for (int m = 0; m <= 10; m++) {
                    assertEquals("position " + i, a.x[m], b.x[m], 0.0);
                    assertEquals("position " + i, a.y[m], b.y[m], 0.0);
                    assertEquals("position " + i, a.z[m], b.z[m], 0.0);
                }
            }
        }
    }

    @Test
    public void aBigAreaGoesToTheBase() {
        Cells base = course(3);
        PlayerSim.World cached = SimWorlds.cached(base);
        AxisAlignedBB wide = new AxisAlignedBB(-10, 0, -10, 10, 2, 10);
        assertEquals(base.boxes(wide).size(), cached.boxes(wide).size());
        int before = base.boxQueries;
        cached.boxes(wide);
        assertEquals(before + 1, base.boxQueries);
    }
}
