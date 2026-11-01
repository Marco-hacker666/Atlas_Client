package atlas.combat;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** SpeedBlocker's prediction of a player running in (2026-11-01). */
public class SpeedblockPredictTest {

    @Test
    public void yawFollowsMinecraft() {
        assertEquals(0.0, SpeedblockPlan.wrap(SpeedblockPlan.yawOf(0.0, 1.0)), 1.0E-9);
        assertEquals(90.0, SpeedblockPlan.wrap(SpeedblockPlan.yawOf(-1.0, 0.0)), 1.0E-9);
        assertEquals(-90.0, SpeedblockPlan.wrap(SpeedblockPlan.yawOf(1.0, 0.0)), 1.0E-9);
    }

    @Test
    public void keysSnapToTheEightDirections() {
        assertEquals(0.0, SpeedblockPlan.keyOffset(3.0, 0.0), 1.0E-9);
        assertEquals(45.0, SpeedblockPlan.keyOffset(50.0, 0.0), 1.0E-9);
        assertEquals(-45.0, SpeedblockPlan.keyOffset(-40.0, 0.0), 1.0E-9);
        /* Between keys: knockback or the like, taken as it is. */
        assertEquals(25.0, SpeedblockPlan.keyOffset(25.0, 0.0), 1.0E-9);
        assertEquals(0.0, SpeedblockPlan.keyOffset(30.0, Double.NaN), 1.0E-9);
    }

    @Test
    public void aSprintSettlesAtVanillaSpeed() {
        /* Sprinting along +z from rest-ish: the step converges on about 0.286 blocks a tick. */
        double[][] p = SpeedblockPlan.simulate(0.5, 0.5, 0.0, 0.05, 0.0, 0.5, 100.0, true, true, 40);
        double last = p[39][1] - p[38][1];
        assertEquals(0.2863, last, 0.002);
        assertEquals(0.5, p[39][0], 1.0E-6);
    }

    @Test
    public void aStrafingChaserCurlsInTowardThePlayer() {
        /* The player is 6 blocks along +z. The target looks straight at them (yaw 0) but runs W+D, 45 degrees to
           the right of its look (toward -x). Its look stays on the player, so it keeps running 45 degrees off a
           look that turns as it moves: it does not run on in a straight line. */
        double moveYaw = -45.0;
        double mx = -Math.sin(Math.toRadians(moveYaw)) * 0.28;
        double mz = Math.cos(Math.toRadians(moveYaw)) * 0.28;
        double[][] p = SpeedblockPlan.simulate(0.0, 0.0, mx, mz, 0.0, 0.0, 6.0, true, true, 8);
        /* A straight line would be at x = 8 * 0.28 * sin(45) ~ 1.58 after 8 ticks. */
        double straightX = 8 * 0.28 * Math.sin(Math.toRadians(45.0));
        assertTrue("x " + p[7][0], p[7][0] > 0.0);
        assertTrue("z " + p[7][1], p[7][1] > 1.0);
        assertTrue(Math.abs(p[7][0] - straightX) < 0.6);
    }

    @Test
    public void aRunnerNotChasingKeepsItsLine() {
        /* Running along +x, looking along +x (yaw -90), the player far off to +z: no chase, a straight line. */
        double[][] p = SpeedblockPlan.simulate(0.0, 0.0, 0.28, 0.0, -90.0, 0.0, 50.0, true, true, 10);
        for (double[] pos : p) {
            assertEquals(0.0, pos[1], 1.0E-6);
        }
        assertTrue(p[9][0] > 2.5);
    }

    @Test
    public void theFrontEdgeReachesACellBeforeTheCentre() {
        /* From x 0.5 along +x at 0.28 a tick: the centre enters cell 1 on tick 2 (x 1.06), the front edge
           (0.3 ahead) on tick 1 (x 0.78 + 0.3 = 1.08). */
        double[][] p = {{0.78, 0.5}, {1.06, 0.5}, {1.34, 0.5}, {1.62, 0.5}, {1.9, 0.5}, {2.18, 0.5}};
        List<int[]> cells = SpeedblockPlan.cellsAlong(0.5, 0.5, 64, p, 2, false);
        assertEquals(2, cells.size());
        assertArrayEquals(new int[]{1, 64, 0, 1}, cells.get(0));
        assertArrayEquals(new int[]{2, 64, 0, 5}, cells.get(1));
    }

    @Test
    public void doubleHeightAddsTheCellAboveAtTheSameTick() {
        double[][] p = {{0.78, 0.5}, {1.06, 0.5}};
        List<int[]> cells = SpeedblockPlan.cellsAlong(0.5, 0.5, 64, p, 1, true);
        assertEquals(2, cells.size());
        assertArrayEquals(new int[]{1, 65, 0, 1}, cells.get(1));
    }

    @Test
    public void aBlockIsAWallAfterOurRoundTrip() {
        /* 220 ms: the runner's position is 110 ms old, the click takes 110 more = 5 ticks, plus the server's tick. */
        assertEquals(6, SpeedblockPlan.serverWallAfterTicks(220));
        assertEquals(3, SpeedblockPlan.serverWallAfterTicks(0));
        assertEquals(3, SpeedblockPlan.serverWallAfterTicks(5000));
    }
}
