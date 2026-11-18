package atlas.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DodgeAimTest {
    private static final double EYE = 1.62;
    private static final double SPEED = 3.0;

    private static ProjectileSolver.Target target(final double vx, final double vy, final double from) {
        return ticks -> {
            double moved = Math.max(0.0, ticks - from);
            double x = vx * moved;
            double y = vy * moved;
            return new double[]{x - 0.3, y, 19.7, x + 0.3, y + 1.8, 20.3};
        };
    }

    private static final ProjectileSolver.Target STILL = target(0.0, 0.0, 0.0);

    private static ProjectileSolver.Aim base() {
        return ProjectileSolver.solve(0.0, EYE, 0.0, SPEED, ProjectileSolver.ARROW, STILL, false);
    }

    private static DodgeAim.Result best(double keepSide, double keepUp, ProjectileSolver.Target... dodges) {
        return DodgeAim.best(0.0, EYE, 0.0, base(), SPEED, ProjectileSolver.ARROW, STILL, Arrays.asList(dodges),
                keepSide, keepUp);
    }

    private static boolean hits(ProjectileSolver.Aim aim, ProjectileSolver.Target target) {
        return ProjectileSolver.fly(0.0, EYE, 0.0, aim.yaw, aim.pitch, SPEED, ProjectileSolver.ARROW, target,
                ProjectileSolver.OPEN, 40).hits();
    }

    @Test
    public void aStepToOneSideIsCoveredByAimingThatWay() {
        ProjectileSolver.Target east = target(0.25, 0.0, 3.0);
        assertEquals("the middle misses it", 0, DodgeAim.covered(0.0, EYE, 0.0, base().yaw, base().pitch, SPEED,
                DodgeAim.safe(ProjectileSolver.ARROW, 20.0), Arrays.asList(east), 20));
        DodgeAim.Result result = best(0.0, 0.0, east);
        assertEquals(1, result.covered);
        assertEquals(1, result.total);
        assertTrue("to the shooter's left: " + result.side, result.side < 0.0);
        assertEquals(0.0, result.up, 0.0);

        assertTrue(hits(result.aim, STILL));
        assertTrue(hits(result.aim, east));

        assertTrue(best(0.0, 0.0, target(-0.25, 0.0, 3.0)).side > 0.0);
    }

    @Test
    public void whatTheMiddleAlreadyCoversMovesNothing() {
        ProjectileSolver.Aim base = base();
        DodgeAim.Result result = DodgeAim.best(0.0, EYE, 0.0, base, SPEED, ProjectileSolver.ARROW, STILL,
                Arrays.asList(target(0.05, 0.0, 3.0), target(-0.05, 0.0, 3.0), STILL), 0.0, 0.0);
        assertSame(base, result.aim);
        assertEquals(3, result.covered);
        assertEquals(0.0, result.side, 0.0);

        result = DodgeAim.best(0.0, EYE, 0.0, base, SPEED, ProjectileSolver.ARROW, STILL,
                new ArrayList<ProjectileSolver.Target>(), 0.0, 0.0);
        assertSame(base, result.aim);
        assertEquals(0, result.total);
    }

    @Test
    public void betweenTwoAsGoodTheLastOneIsKept() {
        ProjectileSolver.Target east = target(0.25, 0.0, 3.0);
        ProjectileSolver.Target west = target(-0.25, 0.0, 3.0);

        DodgeAim.Result first = best(0.0, 0.0, east, west);
        assertEquals(1, first.covered);
        assertEquals(2, first.total);
        DodgeAim.Result kept = best(-first.side, 0.0, east, west);
        assertEquals(1, kept.covered);
        assertEquals(-first.side, kept.side, 1.0E-9);
        assertEquals(first.side, best(first.side, 0.0, east, west).side, 1.0E-9);
    }

    @Test
    public void aJumpIsCoveredByAimingHigher() {
        ProjectileSolver.Target jump = target(0.0, 0.35, 2.0);
        assertEquals(0, DodgeAim.covered(0.0, EYE, 0.0, base().yaw, base().pitch, SPEED,
                DodgeAim.safe(ProjectileSolver.ARROW, 20.0), Arrays.asList(jump), 20));
        DodgeAim.Result result = best(0.0, 0.0, jump);
        assertEquals(1, result.covered);
        assertTrue("higher: " + result.up, result.up > 0.0);
        assertTrue(hits(result.aim, STILL));
        assertTrue(hits(result.aim, jump));
    }

    @Test
    public void aLookIsNeverTakenThatMissesTheTargetCarryingOn() {
        ProjectileSolver.Aim base = base();
        DodgeAim.Result result = DodgeAim.best(0.0, EYE, 0.0, base, SPEED, ProjectileSolver.ARROW, STILL,
                Arrays.asList(target(0.5, 0.0, 3.0)), 0.0, 0.0);
        assertSame(base, result.aim);
        assertEquals(0, result.covered);

        for (double v = -0.3; v <= 0.3; v += 0.05) {
            for (double from = 0.0; from <= 6.0; from += 2.0) {
                List<ProjectileSolver.Target> dodges = Arrays.asList(target(v, 0.0, from), target(-v / 2.0, 0.1, from));
                DodgeAim.Result r = DodgeAim.best(0.0, EYE, 0.0, base, SPEED, ProjectileSolver.ARROW, STILL, dodges,
                        0.0, 0.0);
                assertTrue(hits(r.aim, STILL));
                assertTrue(r.covered >= DodgeAim.covered(0.0, EYE, 0.0, base.yaw, base.pitch, SPEED,
                        DodgeAim.safe(ProjectileSolver.ARROW, 20.0), dodges, DodgeAim.flightTicks(base.ticks)));
            }
        }
    }

    @Test
    public void aNearMissDoesNotCount() {
        assertEquals(0.3, DodgeAim.safe(ProjectileSolver.ARROW, 0.0).margin, 1.0E-12);
        assertEquals(0.15, DodgeAim.safe(ProjectileSolver.ARROW, 20.0).margin, 1.0E-12);
        assertEquals(0.1, DodgeAim.safe(ProjectileSolver.ARROW, 100.0).margin, 1.0E-12);
        assertEquals(ProjectileSolver.ARROW.gravity, DodgeAim.safe(ProjectileSolver.ARROW, 20.0).gravity, 0.0);

        ProjectileSolver.Aim base = base();
        ProjectileSolver.Target beside = ticks -> new double[]{0.2, 0.0, 19.7, 0.8, 1.8, 20.3};
        List<ProjectileSolver.Target> one = Arrays.asList(beside);
        assertEquals(1, DodgeAim.covered(0.0, EYE, 0.0, base.yaw, base.pitch, SPEED, ProjectileSolver.ARROW, one, 20));
        assertEquals(0, DodgeAim.covered(0.0, EYE, 0.0, base.yaw, base.pitch, SPEED,
                DodgeAim.safe(ProjectileSolver.ARROW, 20.0), one, 20));
        assertEquals(16, DodgeAim.flightTicks(7.0));
        assertEquals(ProjectileSolver.MAX_TICKS, DodgeAim.flightTicks(500.0));
    }
}
