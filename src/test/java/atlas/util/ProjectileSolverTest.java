package atlas.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProjectileSolverTest {

    private static final double EYE = 1.62;

    /** A player standing at (x, y, z), moving (vx, vy, vz) blocks a tick. */
    private static ProjectileSolver.Target player(double x, double y, double z, double vx, double vy, double vz) {
        return t -> new double[]{x + vx * t - 0.3, y + vy * t, z + vz * t - 0.3,
                x + vx * t + 0.3, y + vy * t + 1.8, z + vz * t + 0.3};
    }

    private static ProjectileSolver.Flight fly(ProjectileSolver.Aim aim, double speed, ProjectileSolver.Kind kind,
                                               ProjectileSolver.Target target, ProjectileSolver.Blocks blocks) {
        return ProjectileSolver.fly(0.0, EYE, 0.0, aim.yaw, aim.pitch, speed, kind, target, blocks,
                ProjectileSolver.MAX_TICKS);
    }

    @Test
    public void theDrawGivesTheGamesSpeed() {
        assertEquals(3.0, ProjectileSolver.bowSpeed(20), 1.0E-9);
        assertEquals(3.0, ProjectileSolver.bowSpeed(40), 1.0E-9);
        assertEquals(0.0, ProjectileSolver.bowSpeed(1), 1.0E-9);
        assertEquals((0.25 + 1.0) / 3.0 * 3.0, ProjectileSolver.bowSpeed(10), 1.0E-9);
    }

    @Test
    public void aFlatShotFliesAsTheGameDoes() {
        ProjectileSolver.Flight flight = ProjectileSolver.fly(0.0, EYE, 0.0, 0.0F, 0.0F, 3.0, ProjectileSolver.ARROW,
                null, ProjectileSolver.OPEN, 2);
        double[] start = flight.path.get(0);
        assertEquals(-0.16, start[0], 1.0E-9);
        assertEquals(EYE - 0.1, start[1], 1.0E-9);
        double[] one = flight.path.get(1);
        assertEquals(3.0, one[2], 1.0E-9);
        double[] two = flight.path.get(2);
        /* After the first tick: motion 3 x 0.99 across, 0 x 0.99 - 0.05 down. */
        assertEquals(3.0 + 2.97, two[2], 1.0E-9);
        assertEquals(EYE - 0.1 - 0.05, two[1], 1.0E-9);
    }

    @Test
    public void aStandingTargetIsHitAtEveryRange() {
        for (double range = 4.0; range <= 60.0; range += 4.0) {
            for (double height : new double[]{-6.0, 0.0, 6.0}) {
                ProjectileSolver.Target target = player(range * 0.6, height, range * 0.8, 0.0, 0.0, 0.0);
                ProjectileSolver.Aim aim = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW,
                        target, false);
                assertNotNull("range " + range + " height " + height, aim);
                assertTrue("range " + range + " height " + height,
                        fly(aim, 3.0, ProjectileSolver.ARROW, target, ProjectileSolver.OPEN).hits());
            }
        }
    }

    @Test
    public void aRunningTargetIsLed() {
        /* Sprinting across the line of fire, 0.28 a tick, and strafing up a slope. */
        ProjectileSolver.Target target = player(0.0, 0.0, 35.0, 0.28, 0.05, -0.1);
        ProjectileSolver.Aim aim = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW, target, false);
        assertNotNull(aim);
        assertTrue(fly(aim, 3.0, ProjectileSolver.ARROW, target, ProjectileSolver.OPEN).hits());
        /* Not led, the same shot misses. */
        ProjectileSolver.Aim still = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW,
                player(0.0, 0.0, 35.0, 0.0, 0.0, 0.0), false);
        assertFalse(fly(still, 3.0, ProjectileSolver.ARROW, target, ProjectileSolver.OPEN).hits());
    }

    @Test
    public void aHalfDrawnBowAndThrownItemsAreSolvedToo() {
        ProjectileSolver.Target target = player(3.0, -1.0, 18.0, -0.2, 0.0, 0.1);
        double half = ProjectileSolver.bowSpeed(12);
        ProjectileSolver.Aim arrow = ProjectileSolver.solve(0.0, EYE, 0.0, half, ProjectileSolver.ARROW, target, false);
        assertNotNull(arrow);
        assertTrue(fly(arrow, half, ProjectileSolver.ARROW, target, ProjectileSolver.OPEN).hits());
        ProjectileSolver.Aim snowball = ProjectileSolver.solve(0.0, EYE, 0.0, ProjectileSolver.THROWN_SPEED,
                ProjectileSolver.THROWN, target, false);
        assertNotNull(snowball);
        assertTrue(fly(snowball, ProjectileSolver.THROWN_SPEED, ProjectileSolver.THROWN, target,
                ProjectileSolver.OPEN).hits());
    }

    @Test
    public void tooFarIsNoAim() {
        assertNull(ProjectileSolver.solve(0.0, EYE, 0.0, ProjectileSolver.bowSpeed(5), ProjectileSolver.ARROW,
                player(0.0, 0.0, 200.0, 0.0, 0.0, 0.0), false));
        assertNull(ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW,
                player(0.0, 80.0, 10.0, 0.0, 0.0, 0.0), false));
    }

    @Test
    public void theHighArcGoesOverAWallTheLowOneHits() {
        ProjectileSolver.Target target = player(0.0, 0.0, 30.0, 0.0, 0.0, 0.0);
        /* A wall 4 high halfway. */
        ProjectileSolver.Blocks wall = (x1, y1, z1, x2, y2, z2) -> {
            if ((z1 - 15.0) * (z2 - 15.0) > 0.0) {
                return 2.0;
            }
            double t = (15.0 - z1) / (z2 - z1);
            double y = y1 + (y2 - y1) * t;
            return y < 4.0 ? t : 2.0;
        };
        ProjectileSolver.Aim low = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW, target, false);
        ProjectileSolver.Flight lowFlight = fly(low, 3.0, ProjectileSolver.ARROW, target, wall);
        assertTrue(lowFlight.blocked);
        ProjectileSolver.Aim high = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW, target, true);
        assertNotNull(high);
        assertTrue(high.lob);
        assertTrue(fly(high, 3.0, ProjectileSolver.ARROW, target, wall).hits());
    }

    @Test
    public void theSideStartIsAimedFor() {
        /* Close in, 0.16 to the side matters: the yaw is turned to make up for it. */
        ProjectileSolver.Target target = player(0.0, 0.0, 4.0, 0.0, 0.0, 0.0);
        ProjectileSolver.Aim aim = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW, target, false);
        assertTrue(Math.abs(aim.yaw) > 1.0F);
        ProjectileSolver.Flight flight = fly(aim, 3.0, ProjectileSolver.ARROW, target, ProjectileSolver.OPEN);
        double[] end = flight.path.get(flight.path.size() - 1);
        assertEquals(0.0, end[0], 0.05);
    }
}
