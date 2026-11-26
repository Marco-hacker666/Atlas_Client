package atlas.module.modules;

import atlas.sim.PlayerSim;
import net.minecraft.util.AxisAlignedBB;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SurePlaceTest {
    private static final double RESTING_FALL = -0.0784000015258789;

    private static final class Floor implements PlayerSim.World {
        final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();

        Floor box(double x1, double y1, double z1, double x2, double y2, double z2) {
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
            return 0.6F;
        }
    }

    /** Floor up to x = 0, the bridge's end; the cells past it are empty. */
    private static Floor bridgeEnd() {
        return new Floor().box(-100, -1, -100, 0, 0, 100);
    }

    private static PlayerSim standing(double x, double y, double z) {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    private static PlayerSim.Input east(float forward, boolean jump, boolean sneak) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = -90.0F;
        in.forward = forward;
        in.jump = jump;
        in.sneak = sneak;
        return in;
    }

    @Test
    public void walkingOffTheEndCountsDownToTheLastTick() {
        Floor world = bridgeEnd();
        PlayerSim sim = standing(-1.5, 0.0, 0.5);
        PlayerSim.Input in = east(1.0F, false, false);
        int[] need = new int[13];
        for (int t = 0; t < need.length; t++) {
            need[t] = SurePlace.neededIn(sim, world, in, 0, -1, 0, 3);
            sim.tick(world, in);
        }
        for (int t = 0; t < 8; t++) {
            assertEquals("tick " + t, SurePlace.NOT_YET, need[t]);
        }
        assertEquals(3, need[8]);
        assertEquals(2, need[9]);
        // the last tick a block can still go in: one move later the player is falling into the cell
        assertEquals(1, need[10]);
        assertEquals(SurePlace.BLOCKED, need[11]);
        assertEquals(SurePlace.BLOCKED, need[12]);
    }

    @Test
    public void aSprintJumpNeedsOnlyTheLandingCellsAndOnlyLate() {
        Floor world = bridgeEnd();
        PlayerSim sim = standing(-0.4, 0.0, 0.5);
        sim.sprinting = true;
        for (int t = 0; t < 12; t++) {
            PlayerSim.Input in = east(1.0F, t == 0, false);
            in.sprint = true;
            int first = SurePlace.neededIn(sim, world, in, 0, -1, 0, 2);
            int second = SurePlace.neededIn(sim, world, in, 1, -1, 0, 2);
            int third = SurePlace.neededIn(sim, world, in, 2, -1, 0, 2);
            assertEquals("tick " + t, SurePlace.NOT_YET, first);
            int expected = t == 10 ? 2 : t == 11 ? 1 : SurePlace.NOT_YET;
            assertEquals("tick " + t, expected, second);
            assertEquals("tick " + t, expected, third);
            sim.tick(world, in);
        }
    }

    @Test
    public void aCellThePlayerIsInIsBlocked() {
        PlayerSim sim = standing(0.5, -0.4, 0.5);
        sim.onGround = false;
        sim.motionY = -0.3;
        assertEquals(SurePlace.BLOCKED, SurePlace.neededIn(sim, bridgeEnd(), east(0.0F, false, false), 0, -1, 0, 2));
    }

    @Test
    public void standingOnTheFloorNeedsNothing() {
        assertEquals(SurePlace.NOT_YET,
                SurePlace.neededIn(standing(-5.0, 0.0, 0.5), bridgeEnd(), east(0.0F, false, false), 0, -1, 0, 5));
    }

    @Test
    public void sneakingAtTheEndNeedsNothing() {
        Floor world = bridgeEnd();
        PlayerSim sim = standing(-0.4, 0.0, 0.5);
        PlayerSim.Input in = east(0.3F, false, true);
        for (int t = 0; t < 30; t++) {
            // with the block the sneak clamp lets the player walk on, but without it they still do not fall
            assertEquals("tick " + t, SurePlace.NOT_YET, SurePlace.neededIn(sim, world, in, 0, -1, 0, 2));
            sim.tick(world, in);
        }
        assertEquals(0.3, sim.box.maxX - 0.3, 0.01);
    }
}
