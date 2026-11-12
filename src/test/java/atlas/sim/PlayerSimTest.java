package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
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
 * The movement simulation against numbers the game is known for (2026-11-11):
 * the walking, sprinting and sneaking speeds, the heights of a jump tick by
 * tick, the tick a player hangs past an edge before it falls.
 */
public class PlayerSimTest {

    /** Boxes and nothing else; slipperiness 0.6 unless a cell says otherwise. */
    private static final class Fake implements PlayerSim.World {
        final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();
        final Map<String, Float> slippery = new HashMap<String, Float>();
        /** Cells that are a full cube (with no box here, so the push out of them is seen alone). */
        final Set<String> cubes = new HashSet<String>();
        /** Columns with x under this have no chunk. */
        int loadedFromX = Integer.MIN_VALUE;

        Fake box(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.solid.add(new AxisAlignedBB(x1, y1, z1, x2, y2, z2));
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
            Float value = this.slippery.get(x + "," + y + "," + z);
            return value == null ? 0.6F : value;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return this.cubes.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean loaded(int x, int z) {
            return x >= this.loadedFromX;
        }
    }

    private static final double RESTING_FALL = -0.0784000015258789;

    /** Ground everywhere, top at y = 0. */
    private static Fake flat() {
        return new Fake().box(-100, -1, -100, 100, 0, 100);
    }

    /** Ground where x is under 0: an edge at x = 0, nothing beyond. */
    private static Fake edge() {
        return new Fake().box(-100, -1, -100, 0, 0, 100);
    }

    private static PlayerSim standing(double x, double z) {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(x - 0.3, 0.0, z - 0.3, x + 0.3, 1.8, z + 0.3);
        sim.onGround = true;
        /* What a player on the ground carries every tick: one tick of gravity, stopped by the floor again. With 0
           the game itself has it off the ground for a tick. */
        sim.motionY = RESTING_FALL;
        return sim;
    }

    /** Keys that walk toward +x: yaw -90, forward. */
    private static PlayerSim.Input east(float forward) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = -90.0F;
        in.forward = forward;
        return in;
    }

    private static double stride(PlayerSim sim, PlayerSim.World world, PlayerSim.Input in, int ticks) {
        double last = 0.0;
        for (int i = 0; i < ticks; i++) {
            double before = sim.x();
            double beforeZ = sim.z();
            sim.tick(world, in);
            last = Math.hypot(sim.x() - before, sim.z() - beforeZ);
        }
        return last;
    }

    @Test
    public void walkingSettlesAtTheGamesSpeed() {
        /* 4.317 blocks a second. */
        assertEquals(0.2159, stride(standing(0, 0), flat(), east(1.0F), 100), 0.0005);
    }

    @Test
    public void sprintingSettlesAtTheGamesSpeed() {
        /* 5.612 blocks a second. */
        PlayerSim.Input in = east(1.0F);
        in.sprint = true;
        PlayerSim sim = standing(0, 0);
        assertEquals(0.2806, stride(sim, flat(), in, 100), 0.0005);
        assertTrue(sim.sprinting);
    }

    @Test
    public void sneakingSettlesAtTheGamesSpeed() {
        /* 1.295 blocks a second: the keys at 0.3. */
        PlayerSim.Input in = east(0.3F);
        in.sneak = true;
        assertEquals(0.0648, stride(standing(0, 0), flat(), in, 100), 0.0005);
    }

    @Test
    public void twoKeysAtOnceAreAsFastAsTheInputAllows() {
        /* Forward and strafe together are scaled to 1, not to 0.98: a touch faster than one key. */
        PlayerSim.Input in = east(1.0F);
        in.strafe = 1.0F;
        assertEquals(0.2203, stride(standing(0, 0), flat(), in, 100), 0.0005);
    }

    @Test
    public void sprintIsNotKeptWalkingBackwards() {
        PlayerSim.Input in = east(1.0F);
        in.sprint = true;
        PlayerSim sim = standing(0, 0);
        stride(sim, flat(), in, 20);
        assertTrue(sim.sprinting);
        in.forward = -1.0F;
        sim.tick(flat(), in);
        assertFalse(sim.sprinting);
    }

    @Test
    public void aJumpRisesAndFallsByTheGamesHeights() {
        /* 1.8's jump: the top is held for two ticks (the motion left there is under 0.005 and dropped), and the
           twelfth tick lands. */
        double[] heights = {0.41999998688698, 0.7531999805212, 1.00133597911214, 1.16610926093821, 1.24918707874468,
                1.24918707874468, 1.1707870772188, 1.0155550727022, 0.78502770378923, 0.48071087633169,
                0.10408037809304, 0.0};
        PlayerSim sim = standing(0, 0);
        Fake world = flat();
        PlayerSim.Input in = new PlayerSim.Input();
        in.jump = true;
        for (int i = 0; i < heights.length; i++) {
            sim.tick(world, in);
            in.jump = false;
            assertEquals("tick " + (i + 1), heights[i], sim.y(), 1.0E-7);
            assertEquals("tick " + (i + 1), i == heights.length - 1, sim.onGround);
        }
    }

    @Test
    public void jumpBoostAddsATenthALevel() {
        PlayerSim sim = standing(0, 0);
        sim.jumpBoost = 0.2F;
        PlayerSim.Input in = new PlayerSim.Input();
        in.jump = true;
        sim.tick(flat(), in);
        assertEquals(0.62, sim.y(), 1.0E-6);
    }

    @Test
    public void aSprintJumpIsPushedAlongTheYaw() {
        PlayerSim.Input in = east(1.0F);
        in.sprint = true;
        PlayerSim sim = standing(0, 0);
        Fake world = flat();
        stride(sim, world, in, 100);
        in.jump = true;
        /* What friction left of the last stride (0.2806 x 0.546), the jump's 0.2, the keys' 0.1274. */
        assertEquals(0.4806, stride(sim, world, in, 1), 0.002);
        assertFalse(sim.onGround);
        assertEquals(0.026F, sim.airSpeed, 1.0E-6F);
    }

    @Test
    public void aHeldJumpKeyJumpsAgainOnLanding() {
        PlayerSim sim = standing(0, 0);
        Fake world = flat();
        PlayerSim.Input in = new PlayerSim.Input();
        in.jump = true;
        for (int i = 0; i < 12; i++) {
            sim.tick(world, in);
        }
        assertTrue(sim.onGround);
        sim.tick(world, in);
        assertEquals(0.41999998688698, sim.y(), 1.0E-7);
    }

    @Test
    public void walkingOffAnEdgeHangsOneTickThenFalls() {
        /* The game moves down first, from where the box still stands on the block, and only then across: the tick
           that crosses the edge still ends on the ground, the next one falls. */
        PlayerSim sim = standing(-2.0, 0);
        Fake world = edge();
        PlayerSim.Input in = east(1.0F);
        double before = sim.x();
        boolean hung = false;
        for (int i = 0; i < 40 && sim.onGround; i++) {
            before = sim.x();
            sim.tick(world, in);
            if (sim.onGround && sim.x() > 0.3) {
                hung = true;
            }
        }
        assertFalse(sim.onGround);
        assertTrue("past the edge on the ground for a tick", hung && before > 0.3);
        assertEquals(-0.0784, sim.y(), 1.0E-6);
    }

    @Test
    public void aSneakingPlayerDoesNotLeaveTheBlock() {
        PlayerSim sim = standing(-2.0, 0);
        Fake world = edge();
        PlayerSim.Input in = east(0.3F);
        in.sneak = true;
        for (int i = 0; i < 200; i++) {
            sim.tick(world, in);
        }
        assertTrue(sim.onGround);
        assertEquals(0.0, sim.y(), 0.0);
        assertTrue("still over the block: " + sim.x(), sim.x() < 0.3);
        assertTrue("and at its edge: " + sim.x(), sim.x() > 0.2);
    }

    @Test
    public void aWallStopsTheMoveAndTheSprint() {
        Fake world = flat().box(1, 0, -100, 2, 2, 100);
        PlayerSim.Input in = east(1.0F);
        in.sprint = true;
        PlayerSim sim = standing(-2.0, 0);
        for (int i = 0; i < 40; i++) {
            sim.tick(world, in);
        }
        assertEquals(0.7, sim.x(), 1.0E-9);
        assertTrue(sim.collidedHorizontally);
        assertEquals(0.0, sim.motionX, 0.0);
        assertFalse(sim.sprinting);
    }

    @Test
    public void aSlabIsSteppedUpAndAFullBlockIsNot() {
        Fake slab = flat().box(1, 0, -100, 9, 0.5, 100);
        PlayerSim sim = standing(-1.0, 0);
        for (int i = 0; i < 20; i++) {
            sim.tick(slab, east(1.0F));
        }
        assertEquals(0.5, sim.y(), 1.0E-9);
        assertTrue(sim.onGround);
        assertTrue(sim.x() > 1.0);

        Fake block = flat().box(1, 0, -100, 9, 1, 100);
        sim = standing(-1.0, 0);
        for (int i = 0; i < 20; i++) {
            sim.tick(block, east(1.0F));
        }
        assertEquals(0.0, sim.y(), 0.0);
        assertEquals(0.7, sim.x(), 1.0E-9);
    }

    @Test
    public void iceCarriesTheSlideFurther() {
        Fake ground = flat();
        Fake ice = flat();
        for (int x = -3; x < 60; x++) {
            ice.slippery.put(x + ",-1,0", 0.98F);
        }
        assertTrue(slide(ice) > slide(ground) * 4.0);
        /* On plain ground: what friction leaves of a walking stride, tick after tick (0.118, 0.064, 0.035, 0.019,
           0.010, 0.006), until the motion is under 0.005 and the game drops it. */
        assertEquals(0.2527, slide(ground), 0.001);
    }

    private static double slide(Fake world) {
        PlayerSim sim = standing(0, 0);
        stride(sim, world, east(1.0F), 60);
        double from = sim.x();
        PlayerSim.Input idle = east(0.0F);
        for (int i = 0; i < 400; i++) {
            sim.tick(world, idle);
        }
        return sim.x() - from;
    }

    /** Walking at full speed toward the edge, {@code x} from it. */
    private static PlayerSim walkingAt(double x) {
        PlayerSim sim = standing(-30.0, 0);
        stride(sim, flat(), east(1.0F), 60);
        sim.box = sim.box.offset(x - sim.x(), 0.0, 0.0);
        return sim;
    }

    @Test
    public void theLastSafeStepIsWhereTheSlideStillEndsOnTheBlock() {
        /* One more stride (0.216) and the slide after it (0.25) must end with the box, less 0.02 a side, still on the
           block: the feet no further than 0.28 past the edge, so the last safe place to walk on from is about 0.195
           before it. */
        Fake world = edge();
        double lastSafe = Double.NaN;
        for (double x = -1.0; x < 0.25; x += 0.005) {
            if (PlayerSim.stopsOnFloor(walkingAt(x), world, east(1.0F), 0.02, 0.0)) {
                lastSafe = x;
            } else {
                break;
            }
        }
        assertEquals(-0.19, lastSafe, 0.02);
        /* Standing still at the very edge is safe; a step from there is not. */
        assertTrue(PlayerSim.stopsOnFloor(standing(0.25, 0), world, east(0.0F), 0.02, 0.0));
        assertFalse(PlayerSim.stopsOnFloor(standing(0.25, 0), world, east(1.0F), 0.02, 0.0));
    }

    @Test
    public void aBlockAboutToBePlacedIsFloor() {
        Fake world = edge();
        PlayerSim sim = walkingAt(-0.05);
        assertFalse(PlayerSim.stopsOnFloor(sim, world, east(1.0F), 0.02, 0.0));
        assertTrue(PlayerSim.stopsOnFloor(sim, SimWorlds.withBlock(world, 0, -1, 0), east(1.0F), 0.02, 0.0));
    }

    @Test
    public void aStepDownOntoAFloorCloseBelowIsNotAFall() {
        /* The block ends at x = 0; half a block lower the ground goes on. With no drop allowed that is a fall, with
           a drop of a block it is a step down. */
        Fake world = edge().box(0, -1.5, -100, 100, -0.5, 100);
        PlayerSim sim = walkingAt(-0.05);
        assertFalse(PlayerSim.stopsOnFloor(sim, world, east(1.0F), 0.02, 0.0));
        assertTrue(PlayerSim.stopsOnFloor(sim, world, east(1.0F), 0.02, 0.99));
        /* Nothing below at all: a fall either way. */
        assertFalse(PlayerSim.stopsOnFloor(sim, edge(), east(1.0F), 0.02, 0.99));
    }

    @Test
    public void aCopyGoesItsOwnWay() {
        PlayerSim sim = standing(0, 0);
        PlayerSim copy = sim.copy();
        copy.tick(flat(), east(1.0F));
        assertEquals(0.0, sim.x(), 0.0);
        assertTrue(copy.x() > 0.0);
    }

    /* 2026-11-12, from the game's own code: EntityPlayerSP.pushOutOfBlocks. */

    private static final double PUSH = (double) 0.1F;

    @Test
    public void aPlayerInsideABlockIsPushedToTheNearestOpenSide() {
        /* The box reaches 0.2 into the cube at x 1..2; the point 0.21 east of the middle is 0.11 inside it. */
        Fake world = flat();
        world.cubes.add("1,0,0");
        PlayerSim sim = standing(0.9, 0.5);
        sim.tick(world, east(0.0F));
        assertEquals(0.9 - PUSH, sim.x(), 1.0E-12);
        assertEquals(0.5, sim.z(), 0.0);
        /* From the other side of the same cube the nearest open side is the east one. */
        PlayerSim other = standing(2.1, 0.5);
        other.tick(world, east(0.0F));
        assertEquals(2.1 + PUSH, other.x(), 1.0E-12);
    }

    @Test
    public void aCubeAtTheHeadPushesToo() {
        /* Forge looks at every cell the player is tall: here only the head is inside. */
        Fake world = flat();
        world.cubes.add("1,1,0");
        PlayerSim sim = standing(0.9, 0.5);
        sim.tick(world, east(0.0F));
        assertEquals(0.9 - PUSH, sim.x(), 1.0E-12);
    }

    @Test
    public void whollyInsideACubeTheLastCornerAskedWinsAndAShutSideIsNotChosen() {
        /* All four points are in the cube at x 1..2, and the cube east of it shuts that side. In the game's order:
           the two west corners (0.29 from the west face) set x toward west; the two east corners cannot go east, so
           the first goes north and the second, asked last, south. */
        Fake world = flat();
        world.cubes.add("1,0,0");
        world.cubes.add("2,0,0");
        PlayerSim sim = standing(1.5, 0.5);
        sim.tick(world, east(0.0F));
        assertEquals(1.5 - PUSH, sim.x(), 1.0E-12);
        assertEquals(0.5 + PUSH, sim.z(), 1.0E-12);
        /* With the east open the east corners, asked after the west ones, win: toward east, nothing across. */
        Fake open = flat();
        open.cubes.add("1,0,0");
        PlayerSim free = standing(1.5, 0.5);
        free.tick(open, east(0.0F));
        assertEquals(1.5 + PUSH, free.x(), 1.0E-12);
        assertEquals(0.5, free.z(), 0.0);
    }

    @Test
    public void aStateTakenAfterThePushOutIsNotPushedAgain() {
        Fake world = flat();
        world.cubes.add("1,0,0");
        PlayerSim sim = standing(0.9, 0.5);
        sim.stage = PlayerSim.Stage.MOVE;
        sim.tick(world, east(0.0F));
        assertEquals(0.9, sim.x(), 0.0);
        /* The tick after is a whole one again. */
        sim.tick(world, east(0.0F));
        assertEquals(0.9 - PUSH, sim.x(), 1.0E-12);
    }

    /* The chunk rule of moveEntityWithHeading. */

    @Test
    public void whereTheChunkIsNotLoadedThePlayerSinksAtAFixedRate() {
        Fake world = new Fake();
        world.loadedFromX = 16;
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(4.7, 30.0, 0.2, 5.3, 31.8, 0.8);
        sim.motionY = -1.5;
        sim.tick(world, east(0.0F));
        assertEquals(-0.1 * 0.9800000190734863, sim.motionY, 0.0);
        /* At or under y 0 it does not sink at all. */
        PlayerSim low = new PlayerSim();
        low.box = new AxisAlignedBB(4.7, -3.0, 0.2, 5.3, -1.2, 0.8);
        low.motionY = -1.5;
        low.tick(world, east(0.0F));
        assertEquals(0.0, low.motionY, 0.0);
        /* Loaded, the same fall is gravity's. */
        PlayerSim loaded = new PlayerSim();
        loaded.box = new AxisAlignedBB(19.7, 30.0, 0.2, 20.3, 31.8, 0.8);
        loaded.motionY = -1.5;
        loaded.tick(world, east(0.0F));
        assertEquals((-1.5 - 0.08) * 0.9800000190734863, loaded.motionY, 0.0);
    }

    @Test
    public void theChunkIsLookedUpByTheGamesCastNotAFloor() {
        /* x = -0.5 is column -1 by a floor and column 0 by the game's (int) cast: the game's answer is "loaded". */
        Fake world = new Fake();
        world.loadedFromX = 0;
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(-0.8, 30.0, 0.2, -0.2, 31.8, 0.8);
        sim.motionY = -1.5;
        sim.tick(world, east(0.0F));
        assertEquals((-1.5 - 0.08) * 0.9800000190734863, sim.motionY, 0.0);
    }

    /* EntityPlayerSP.sprintingTicksLeft. */

    @Test
    public void aSprintEndsByItselfAfterItsCountAndAHeldKeyStartsItAgain() {
        PlayerSim.Input walk = east(1.0F);
        PlayerSim sim = standing(0, 0);
        sim.sprinting = true;
        sim.sprintTicksLeft = 2;
        sim.sprintRule(walk);
        assertTrue(sim.sprinting);
        sim.sprintRule(walk);
        assertFalse("no sprint key: it stays off", sim.sprinting);

        PlayerSim.Input held = east(1.0F);
        held.sprint = true;
        PlayerSim keyed = standing(0, 0);
        keyed.sprinting = true;
        keyed.sprintTicksLeft = 1;
        keyed.sprintRule(held);
        assertTrue("started again in the same tick", keyed.sprinting);
        assertEquals(600, keyed.sprintTicksLeft);
        /* Stopping clears the count; a state with none (0) never ends by itself. */
        keyed.sprintRule(east(0.0F));
        assertFalse(keyed.sprinting);
        assertEquals(0, keyed.sprintTicksLeft);
        PlayerSim noCount = standing(0, 0);
        noCount.sprinting = true;
        for (int i = 0; i < 700; i++) {
            noCount.sprintRule(walk);
        }
        assertTrue(noCount.sprinting);
    }
}
