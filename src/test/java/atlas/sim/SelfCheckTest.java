package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The self check against moves made by the simulation itself: a real input fits, a pushed or turned move does not. */
public class SelfCheckTest {

    private static final double RESTING_FALL = -0.0784000015258789;

    private static final class Flat implements PlayerSim.World {
        private final AxisAlignedBB ground = new AxisAlignedBB(-100, -1, -100, 100, 0, 100);

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            if (this.ground.intersectsWith(area)) {
                out.add(this.ground);
            }
            return out;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6F;
        }
    }

    private static final PlayerSim.World WORLD = new Flat();

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(0.2, 0.0, 0.2, 0.8, 1.8, 0.8);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    private static PlayerSim.Input keys(float yaw, float forward, float strafe, boolean jump, boolean sneak,
                                        boolean sprint) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        in.forward = forward;
        in.strafe = strafe;
        in.jump = jump;
        in.sneak = sneak;
        in.sprint = sprint;
        return in;
    }

    /** Runs {@code ticks} of the input from standing, checking each against the check; returns the worst offset. */
    private static double worst(PlayerSim.Input in, int ticks, String expectedKeys) {
        PlayerSim sim = standing();
        double worst = 0.0;
        for (int i = 0; i < ticks; i++) {
            PlayerSim before = sim.copy();
            sim.tick(WORLD, in);
            SelfCheck.Result result = SelfCheck.check(before, WORLD, in.yaw, sim.sprinting, in.sneak, sim.x(),
                    sim.y(), sim.z());
            worst = Math.max(worst, result.offset);
            if (expectedKeys != null && i > 0) {
                assertEquals("tick " + i, expectedKeys, result.keys());
            }
        }
        return worst;
    }

    @Test
    public void walkingFitsWithNoOffset() {
        assertEquals(0.0, worst(keys(0.0F, 1.0F, 0.0F, false, false, false), 20, "W"), 1.0E-9);
    }

    @Test
    public void sprintingAndSprintJumpingFit() {
        assertEquals(0.0, worst(keys(37.0F, 1.0F, 0.0F, false, false, true), 20, "W"), 1.0E-9);
        assertEquals(0.0, worst(keys(-120.0F, 1.0F, 1.0F, true, false, true), 30, null), 1.0E-9);
    }

    @Test
    public void sneakingAndAnItemsSlowdownFit() {
        assertEquals(0.0, worst(keys(90.0F, 0.3F, -0.3F, false, true, false), 20, "W D"), 1.0E-9);
        assertEquals(0.0, worst(keys(10.0F, 0.2F, 0.0F, false, false, false), 20, "W item"), 1.0E-9);
    }

    @Test
    public void motionAddedFromOutsideDoesNotFit() {
        PlayerSim sim = standing();
        PlayerSim.Input in = keys(0.0F, 1.0F, 0.0F, false, false, false);
        for (int i = 0; i < 10; i++) {
            sim.tick(WORLD, in);
        }
        PlayerSim before = sim.copy();
        PlayerSim pushed = sim.copy();
        pushed.motionX += 0.2;
        pushed.tick(WORLD, in);
        SelfCheck.Result result = SelfCheck.check(before, WORLD, 0.0F, pushed.sprinting, false, pushed.x(),
                pushed.y(), pushed.z());
        assertTrue("a push no key explains: " + result.offset, result.offset > SelfCheck.OFF);
    }

    @Test
    public void aMoveThatDoesNotFitTheYawSentDoesNotFit() {
        PlayerSim sim = standing();
        PlayerSim.Input in = keys(0.0F, 1.0F, 0.0F, false, false, false);
        for (int i = 0; i < 10; i++) {
            sim.tick(WORLD, in);
        }
        PlayerSim before = sim.copy();
        sim.tick(WORLD, in);
        /* 22.5 degrees off: between two of the eight directions the keys give. */
        SelfCheck.Result result = SelfCheck.check(before, WORLD, 22.5F, sim.sprinting, false, sim.x(), sim.y(),
                sim.z());
        assertTrue("moved by another yaw: " + result.offset, result.offset > SelfCheck.OFF);
    }

    @Test
    public void standingStillFitsNoKeys() {
        assertEquals(0.0, worst(keys(0.0F, 0.0F, 0.0F, false, false, false), 10, "none"), 1.0E-9);
    }
}
