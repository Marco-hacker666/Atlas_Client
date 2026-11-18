package atlas.combat;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SpeedblockPlanTest {
    @Test
    public void approachIsPositiveOnlyWhenClosingIn() {
        assertEquals(0.2, SpeedblockPlan.approach(0.2, 0.0, 4.0, 0.0), 1.0E-9);

        assertTrue(SpeedblockPlan.approach(0.2, 0.0, -4.0, 0.0) < 0.0);
    }

    @Test
    public void approachIsTheShareOfSpeedPointingAtThePlayer() {
        double approach = SpeedblockPlan.approach(0.2, 0.0, -1.0, -1.0);
        assertEquals(-0.2 / Math.sqrt(2.0), approach, 1.0E-9);

        assertEquals(0.0, SpeedblockPlan.approach(0.2, 0.0, 0.0, -3.0), 1.0E-9);
    }

    @Test
    public void pathStartsAtTheFirstCellTheTargetWouldEnter() {
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, 0.0, 0.0, 2.0, 2, false);
        assertEquals(2, cells.size());
        assertArrayEquals(new int[]{1, 64, 0}, cells.get(0));
        assertArrayEquals(new int[]{2, 64, 0}, cells.get(1));
    }

    @Test
    public void pathLeavesOutTheCellTheTargetStandsIn() {
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, 0.0, 0.0, 0.4, 2, false);
        assertTrue("the cell it is in is not a goal", cells.isEmpty());
    }

    @Test
    public void pathFollowsMotionOnBothAxes() {
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, -0.2, 0.0, 2.0, 1, false);
        assertEquals(1, cells.size());
        assertArrayEquals(new int[]{1, 64, -1}, cells.get(0));
    }

    @Test
    public void leadSkipsTheCellTheTargetReachesTooSoon() {
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, 0.0, 0.6, 2.0, 2, false);
        assertEquals(1, cells.size());
        assertArrayEquals(new int[]{2, 64, 0}, cells.get(0));
    }

    @Test
    public void leadShorterThanTheCellKeepsIt() {
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, 0.0, 0.4, 2.0, 1, false);
        assertEquals(1, cells.size());
        assertArrayEquals(new int[]{1, 64, 0}, cells.get(0));
    }

    @Test
    public void doubleHeightAddsTheCellAbove() {
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, 0.0, 0.0, 1.0, 1, true);
        assertEquals(2, cells.size());
        assertArrayEquals(new int[]{1, 64, 0}, cells.get(0));
        assertArrayEquals(new int[]{1, 65, 0}, cells.get(1));
    }

    @Test
    public void aTargetThatDoesNotMoveHasNoPath() {
        assertTrue(SpeedblockPlan.path(0.5, 0.5, 64, 0.0, 0.0, 0.0, 3.0, 2, false).isEmpty());
    }

    @Test
    public void aNegativeCoordinateFloorsDown() {
        List<int[]> cells = SpeedblockPlan.path(0.9, 0.5, 64, -0.2, 0.0, 0.0, 1.2, 1, false);
        assertEquals(1, cells.size());
        assertArrayEquals(new int[]{-1, 64, 0}, cells.get(0));
    }
}
