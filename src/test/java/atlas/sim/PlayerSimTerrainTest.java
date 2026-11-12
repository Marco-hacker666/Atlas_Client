package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Water, lava, ladders, cobwebs, soul sand and slime in the movement simulation (2026-11-12), against the speeds the
 * game is known for: sinking and swimming at 0.1 a tick, climbing a ladder at 0.1176 and sliding down it at 0.15.
 */
public class PlayerSimTerrainTest {
    private static final double RESTING_FALL = -0.0784000015258789;

    /** Boxes, and cells of the kinds the simulation asks about. */
    private static final class Terrain implements PlayerSim.World {
        final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();
        final Set<String> water = new HashSet<String>();
        final Map<String, double[]> flow = new HashMap<String, double[]>();
        final Set<String> lava = new HashSet<String>();
        final Set<String> ladder = new HashSet<String>();
        final Set<String> web = new HashSet<String>();
        final Set<String> soulSand = new HashSet<String>();
        final Set<String> slime = new HashSet<String>();

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        Terrain box(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.solid.add(new AxisAlignedBB(x1, y1, z1, x2, y2, z2));
            return this;
        }

        /** Cells x1..x2, y1..y2, z1..z2 (inclusive) into {@code set}. */
        Terrain fill(Set<String> set, int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        set.add(key(x, y, z));
                    }
                }
            }
            return this;
        }

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            for (AxisAlignedBB box : this.solid) {
                if (box.intersectsWith(area)) {
                    out.add(box);
                }
            }
            return out;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return this.slime.contains(key(x, y, z)) ? 0.8F : 0.6F;
        }

        /* Source blocks: getLiquidHeightPercent(0) is 1/9. */
        @Override
        public boolean water(AxisAlignedBB area, double[] flow) {
            boolean in = false;
            int maxY = MathHelper.floor_double(area.maxY + 1.0);
            for (int x = MathHelper.floor_double(area.minX); x < MathHelper.floor_double(area.maxX + 1.0); x++) {
                for (int y = MathHelper.floor_double(area.minY); y < maxY; y++) {
                    for (int z = MathHelper.floor_double(area.minZ); z < MathHelper.floor_double(area.maxZ + 1.0); z++) {
                        if (this.water.contains(key(x, y, z)) && maxY >= (float) (y + 1) - 1.0F / 9.0F) {
                            in = true;
                            double[] f = this.flow.get(key(x, y, z));
                            if (f != null) {
                                flow[0] += f[0];
                                flow[1] += f[1];
                                flow[2] += f[2];
                            }
                        }
                    }
                }
            }
            return in;
        }

        private boolean any(Set<String> set, AxisAlignedBB area, boolean exclusiveTop) {
            int topX = MathHelper.floor_double(exclusiveTop ? area.maxX + 1.0 : area.maxX) - (exclusiveTop ? 1 : 0);
            int topY = MathHelper.floor_double(exclusiveTop ? area.maxY + 1.0 : area.maxY) - (exclusiveTop ? 1 : 0);
            int topZ = MathHelper.floor_double(exclusiveTop ? area.maxZ + 1.0 : area.maxZ) - (exclusiveTop ? 1 : 0);
            for (int x = MathHelper.floor_double(area.minX); x <= topX; x++) {
                for (int y = MathHelper.floor_double(area.minY); y <= topY; y++) {
                    for (int z = MathHelper.floor_double(area.minZ); z <= topZ; z++) {
                        if (set.contains(key(x, y, z))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public boolean lava(AxisAlignedBB area) {
            return any(this.lava, area, true);
        }

        @Override
        public boolean liquid(AxisAlignedBB area) {
            return any(this.water, area, false) || any(this.lava, area, false);
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return this.ladder.contains(key(x, y, z));
        }

        @Override
        public boolean slime(int x, int y, int z) {
            return this.slime.contains(key(x, y, z));
        }

        @Override
        public int touching(AxisAlignedBB area, PlayerSim.Touch kind) {
            Set<String> set = kind == PlayerSim.Touch.WEB ? this.web : this.soulSand;
            int count = 0;
            for (int x = MathHelper.floor_double(area.minX); x <= MathHelper.floor_double(area.maxX); x++) {
                for (int y = MathHelper.floor_double(area.minY); y <= MathHelper.floor_double(area.maxY); y++) {
                    for (int z = MathHelper.floor_double(area.minZ); z <= MathHelper.floor_double(area.maxZ); z++) {
                        count += set.contains(key(x, y, z)) ? 1 : 0;
                    }
                }
            }
            return count;
        }
    }

    private static PlayerSim at(double x, double y, double z, boolean onGround) {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        sim.onGround = onGround;
        sim.motionY = onGround ? RESTING_FALL : 0.0;
        return sim;
    }

    private static PlayerSim.Input keys(float forward, boolean jump, boolean sneak) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = -90.0F;
        in.forward = forward;
        in.jump = jump;
        in.sneak = sneak;
        return in;
    }

    /** Ticks {@code ticks} times; the move of the last tick, {dx, dy}. */
    private static double[] run(PlayerSim sim, PlayerSim.World world, PlayerSim.Input in, int ticks) {
        double dx = 0.0;
        double dy = 0.0;
        for (int i = 0; i < ticks; i++) {
            double x = sim.x();
            double y = sim.y();
            sim.tick(world, in);
            dx = sim.x() - x;
            dy = sim.y() - y;
        }
        return new double[]{dx, dy};
    }

    /** A pool of still water, its bottom at y = -40, wide and deep. */
    private static Terrain pool() {
        Terrain t = new Terrain().box(-100, -41, -100, 100, -40, 100);
        return t.fill(t.water, -60, -40, -10, 60, 10, 10);
    }

    @Test
    public void stillWaterSinksAPlayerAtATenthATick() {
        double[] move = run(at(0, 0, 0, false), pool(), keys(0, false, false), 60);
        assertEquals(-0.1, move[1], 1.0E-4);
    }

    @Test
    public void holdingJumpInWaterSwimsUpAtATenthATick() {
        double[] move = run(at(0, -20, 0, false), pool(), keys(0, true, false), 60);
        assertEquals(0.1, move[1], 1.0E-4);
    }

    @Test
    public void swimmingAcrossIsAboutTwoBlocksASecond() {
        /* 0.8 drag and 0.02 of push: 0.0196 / 0.2 = 0.098 a tick. */
        double[] move = run(at(0, -20, 0, false), pool(), keys(1.0F, true, false), 60);
        assertEquals(0.098, move[0], 1.0E-4);
    }

    @Test
    public void depthStriderThreeWalksTheBottomAsFastAsLand() {
        PlayerSim sim = at(0, -40, 0, true);
        sim.depthStrider = 3.0F;
        double[] move = run(sim, pool(), keys(1.0F, false, false), 80);
        assertEquals(0.2159, move[0], 0.0005);
    }

    @Test
    public void flowingWaterPushes() {
        Terrain t = pool();
        for (String cell : t.water) {
            t.flow.put(cell, new double[]{2.0, 0.0, 0.0});
        }
        double[] move = run(at(0, -20, 0, false), t, keys(0, true, false), 60);
        /* 0.014 a tick against 0.8 drag: 0.014 / 0.2. */
        assertEquals(0.07, move[0], 1.0E-4);
    }

    @Test
    public void aWallInWaterIsClimbedWhenThereIsAirAbove() {
        /* Water up to y = 0, a block wall at x = 1 reaching y = 0.5: swimming into it at the surface hops out. */
        Terrain t = new Terrain().box(-100, -41, -100, 100, -40, 100).box(1, -40, -100, 100, 0.5, 100);
        t.fill(t.water, -60, -40, -10, 0, -1, 10);
        PlayerSim sim = at(0.5, -1.2, 0, false);
        boolean hopped = false;
        for (int i = 0; i < 40 && !hopped; i++) {
            sim.tick(t, keys(1.0F, true, false));
            hopped = Math.abs(sim.motionY - (0.30000001192092896)) < 1.0E-9;
        }
        assertTrue(hopped);
    }

    @Test
    public void lavaIsHalfAsDragging() {
        Terrain t = new Terrain().box(-100, -41, -100, 100, -40, 100);
        t.fill(t.lava, -60, -40, -10, 60, 10, 10);
        double[] move = run(at(0, -20, 0, false), t, keys(1.0F, true, false), 60);
        /* 0.5 drag: 0.0196 / 0.5. */
        assertEquals(0.0392, move[0], 1.0E-4);
    }

    /** A wall at x = 1 with a ladder on its west face (x 0.875..1) up to y = 20. */
    private static Terrain ladderWall() {
        Terrain t = new Terrain().box(-100, -1, -100, 100, 0, 100).box(1, 0, -100, 2, 30, 100)
                .box(0.875, 0, -0.5, 1, 20, 0.5);
        return t.fill(t.ladder, 0, 0, 0, 0, 19, 0);
    }

    @Test
    public void walkingIntoALadderClimbsItAtTheGamesSpeed() {
        double[] move = run(at(0.575, 0, 0, true), ladderWall(), keys(1.0F, false, false), 40);
        assertEquals(0.1176, move[1], 1.0E-4);
    }

    @Test
    public void aLadderIsSlidDownAtFifteenHundredthsATick() {
        double[] move = run(at(0.575, 18, 0, false), ladderWall(), keys(0, false, false), 6);
        assertEquals(-0.15, move[1], 1.0E-6);
    }

    @Test
    public void sneakingHoldsStillOnALadder() {
        PlayerSim sim = at(0.575, 10, 0, false);
        double[] move = run(sim, ladderWall(), keys(0, false, true), 10);
        assertEquals(0.0, move[1], 1.0E-9);
    }

    @Test
    public void aCobwebCutsTheMoveToAQuarterAcross() {
        Terrain t = new Terrain().box(-100, -1, -100, 100, 0, 100);
        t.fill(t.web, -50, 0, -5, 50, 1, 5);
        PlayerSim sim = at(0, 0, 0, true);
        double[] move = run(sim, t, keys(1.0F, false, false), 30);
        assertTrue(sim.inWeb);
        /* The motion is zeroed every move, so each tick's push (0.098) is all there is: a quarter of it. */
        assertEquals(0.0245, move[0], 1.0E-4);
    }

    @Test
    public void aFallIntoACobwebIsATwentiethAsFast() {
        Terrain t = new Terrain().box(-100, -1, -100, 100, 0, 100);
        t.fill(t.web, -5, 0, -5, 5, 4, 5);
        PlayerSim sim = at(0, 10, 0, false);
        double fallBefore = 0.0;
        for (int i = 0; i < 60 && !sim.inWeb; i++) {
            fallBefore = sim.y();
            sim.tick(t, keys(0, false, false));
        }
        assertTrue(sim.inWeb);
        double[] move = run(sim, t, keys(0, false, false), 3);
        /* From then on each tick's fall is 0.05 of the 0.0784 a tick of gravity leaves. */
        assertEquals(-0.0784 * 0.05, move[1], 1.0E-5);
    }

    private static Terrain soulSand() {
        /* Soul sand is 0.875 high: the player stands in it and touches it every move. */
        Terrain t = new Terrain().box(-100, -1, -100, 100, 0.875, 100);
        return t.fill(t.soulSand, -60, 0, -5, 60, 0, 5);
    }

    @Test
    public void soulSandTakesTheMotionAcrossToFourTenths() {
        /* Inside one block: one touch, the motion left after the move is 0.4 of it, then friction. */
        PlayerSim sim = at(0.5, 0.875, 0.5, true);
        sim.tick(soulSand(), keys(1.0F, false, false));
        assertEquals(0.098 * 0.4 * 0.546, sim.motionX, 1.0E-5);
    }

    @Test
    public void soulSandActsOncePerBlockTouched() {
        /* On the corner of four blocks the 0.4 is taken four times, as the game does it block by block. */
        PlayerSim sim = at(0.0, 0.875, 0.0, true);
        sim.tick(soulSand(), keys(0.1F, false, false));
        assertEquals(0.0098 * Math.pow(0.4, 4) * 0.546, sim.motionX, 1.0E-6);
    }

    @Test
    public void walkingOnSoulSandIsAtMostAboutHalfSpeed() {
        double[] move = run(at(0, 0.875, 0.5, true), soulSand(), keys(1.0F, false, false), 80);
        assertTrue(move[0] > 0.09 && move[0] <= 0.098 / (1.0 - 0.4 * 0.546) + 1.0E-4);
    }

    private static Terrain slimeFloor() {
        Terrain t = new Terrain().box(-100, -1, -100, 100, 0, 100);
        return t.fill(t.slime, -60, -1, -5, 60, -1, 5);
    }

    @Test
    public void slimeBouncesAFallBack() {
        PlayerSim sim = at(0, 5, 0, false);
        Terrain t = slimeFloor();
        double lowestMotion = 0.0;
        for (int i = 0; i < 30 && !sim.onGround; i++) {
            lowestMotion = Math.min(lowestMotion, sim.motionY);
            sim.tick(t, keys(0, false, false));
        }
        assertTrue(sim.onGround);
        assertTrue("going up again: " + sim.motionY, sim.motionY > 0.5);
        double peak = sim.y();
        for (int i = 0; i < 40; i++) {
            sim.tick(t, keys(0, false, false));
            peak = Math.max(peak, sim.y());
        }
        assertTrue("bounced back up most of the way: " + peak, peak > 3.0);
    }

    @Test
    public void sneakingOntoSlimeDoesNotBounce() {
        PlayerSim sim = at(0, 5, 0, false);
        Terrain t = slimeFloor();
        for (int i = 0; i < 30 && !sim.onGround; i++) {
            sim.tick(t, keys(0, false, true));
        }
        assertTrue(sim.onGround);
        assertEquals(0.0, sim.motionY, 0.08);
        sim.tick(t, keys(0, false, true));
        assertTrue(sim.onGround);
    }

    @Test
    public void walkingOnSlimeIsSlowerThanOnStone() {
        Terrain stone = new Terrain().box(-100, -1, -100, 100, 0, 100);
        double onStone = run(at(0, 0, 0, true), stone, keys(1.0F, false, false), 80)[0];
        double onSlime = run(at(0, 0, 0, true), slimeFloor(), keys(1.0F, false, false), 80)[0];
        assertTrue(onSlime + " against " + onStone, onSlime < onStone * 0.5);
        assertFalse(Double.isNaN(onSlime));
    }

    /** Water from y = -40 up to the cell under y = 0, flowing as given; a floor far below. */
    private static Terrain flowingPool(double flowX, double flowZ) {
        Terrain t = new Terrain().box(-100, -41, -100, 100, -40, 100);
        t.fill(t.water, -60, -40, -10, 60, -1, 10);
        for (String cell : t.water) {
            t.flow.put(cell, new double[]{flowX, 0.0, flowZ});
        }
        return t;
    }

    @Test
    public void theTickAPlayerEntersFlowingWaterItIsAlreadyPushed() {
        /* 2026-11-12, from the game's code (EntityLivingBase.updateFallState): not in water before the move, the
           player asks again where the move ended. Falling a block from y 0.5, the box is in the water after the
           move: pushed 0.014 at once (then the air's 0.91), and again at the start of the next tick. */
        Terrain t = flowingPool(2.0, 0.0);
        PlayerSim sim = at(0.5, 0.5, 0.5, false);
        sim.motionY = -1.0;
        sim.tick(t, keys(0, false, false));
        assertTrue(sim.inWater);
        assertEquals(0.5, sim.x(), 0.0);
        assertEquals(0.014 * (double) 0.91F, sim.motionX, 0.0);
        sim.tick(t, keys(0, false, false));
        assertEquals(0.5 + 0.014 * (double) 0.91F + 0.014, sim.x(), 1.0E-15);
    }

    @Test
    public void theFlowIsDividedByItsLengthAsAFloat() {
        /* Vec3.normalize takes the length through MathHelper.sqrt_double, a float. One cell counted, flowing (1, 1):
           each axis gets 1 / (float) sqrt 2 of the 0.014, not 1 / sqrt 2. */
        Terrain t = flowingPool(1.0, 1.0);
        PlayerSim sim = at(0.5, 0.5, 0.5, false);
        sim.motionY = -1.0;
        sim.tick(t, keys(0, false, false));
        double gameS = 1.0 / (double) (float) Math.sqrt(2.0) * 0.014 * (double) 0.91F;
        assertEquals(gameS, sim.motionX, 0.0);
        assertEquals(gameS, sim.motionZ, 0.0);
        assertTrue(Math.abs(1.0 / Math.sqrt(2.0) * 0.014 * (double) 0.91F - sim.motionX) > 1.0E-11);
    }

    @Test
    public void aWallHitWhileEnteringWaterStillClearsThatMotion() {
        /* The push of the entry comes before the game clears the motion a wall stopped: nothing of it is left. */
        Terrain t = flowingPool(2.0, 0.0);
        t.box(0.9, -40, -100, 100, 40, 100);
        PlayerSim sim = at(0.5, 0.5, 0.5, false);
        sim.motionY = -1.0;
        sim.motionX = 0.3;
        sim.tick(t, keys(0, false, false));
        assertTrue(sim.inWater);
        assertTrue(sim.collidedHorizontally);
        assertEquals(0.0, sim.motionX, 0.0);
    }

    @Test
    public void aWorldWithABlockAddedKeepsTheRestOfTheTerrain() {
        /* 2026-11-12: SimWorlds.withBlock passed on only the boxes and the slipperiness, so in it the water, the
           ladders, the cobwebs and the slime were gone. */
        Terrain t = pool();
        t.ladder.add(Terrain.key(3, 0, 3));
        t.slime.add(Terrain.key(4, 0, 4));
        t.web.add(Terrain.key(5, 0, 5));
        t.fill(t.lava, 20, 20, 20, 21, 21, 21);
        PlayerSim.World with = SimWorlds.withBlock(t, 3, 5, 3);
        AxisAlignedBB inPool = new AxisAlignedBB(0.2, -20.0, 0.2, 0.8, -19.0, 0.8);
        assertTrue(with.water(inPool, new double[3]));
        assertTrue(with.liquid(inPool));
        assertTrue(with.lava(new AxisAlignedBB(20.2, 20.2, 20.2, 20.8, 20.8, 20.8)));
        assertTrue(with.climbable(3, 0, 3));
        assertTrue(with.slime(4, 0, 4));
        assertEquals(1, with.touching(new AxisAlignedBB(5.2, 0.2, 5.2, 5.8, 0.8, 5.8), PlayerSim.Touch.WEB));
        /* The added cell: a full cube, with a box, and not a ladder even where the base had one. */
        assertTrue(with.solid(3, 5, 3));
        assertFalse(with.solid(3, 6, 3));
        assertFalse(with.boxes(new AxisAlignedBB(3.2, 5.2, 3.2, 3.8, 5.8, 3.8)).isEmpty());
        assertFalse(SimWorlds.withBlock(t, 3, 0, 3).climbable(3, 0, 3));
        assertFalse(SimWorlds.withBlock(t, 4, 0, 4).slime(4, 0, 4));
        /* And a player in it still sinks through the water as in the base. */
        double[] move = run(at(0, 0, 0, false), with, keys(0, false, false), 60);
        assertEquals(-0.1, move[1], 1.0E-4);
    }

    @Test
    public void aStateTakenAfterTheFlowPushIsNotPushedAgain() {
        /* Review, PR #37: taken inside the tick (handleWaterMovement done), the first move carries no second push. */
        Terrain t = pool();
        for (String cell : t.water) {
            t.flow.put(cell, new double[]{2.0, 0.0, 0.0});
        }
        PlayerSim between = at(0, -20, 0, false);
        PlayerSim inside = between.copy();
        /* As SimPlayer gives it inside the tick: the game has already found the water. */
        inside.stage = PlayerSim.Stage.INPUT;
        inside.inWater = true;
        between.tick(t, keys(0, false, false));
        inside.tick(t, keys(0, false, false));
        assertEquals(0.014, between.x() - inside.x(), 1.0E-9);
        assertEquals(PlayerSim.Stage.START, inside.stage);
        /* From the second tick on, both are pushed. */
        double a = between.x();
        double b = inside.x();
        between.tick(t, keys(0, false, false));
        inside.tick(t, keys(0, false, false));
        assertEquals(between.x() - a, inside.x() - b + 0.014 * 0.8, 1.0E-9);
    }
}
