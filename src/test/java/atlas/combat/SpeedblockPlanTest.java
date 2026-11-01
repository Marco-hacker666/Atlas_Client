package atlas.combat;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Where a speedblock goes (2026-11-01). */
public class SpeedblockPlanTest {

    @Test
    public void approachIsPositiveOnlyWhenClosingIn() {
        /* The player is four blocks along +x; the target moving +x comes at them. */
        assertEquals(0.2, SpeedblockPlan.approach(0.2, 0.0, 4.0, 0.0), 1.0E-9);
        /* The player is four blocks along -x; the same motion runs away. */
        assertTrue(SpeedblockPlan.approach(0.2, 0.0, -4.0, 0.0) < 0.0);
    }

    @Test
    public void approachIsTheShareOfSpeedPointingAtThePlayer() {
        /* The player is behind and to the left; motion along +x opens the gap. */
        double approach = SpeedblockPlan.approach(0.2, 0.0, -1.0, -1.0);
        assertEquals(-0.2 / Math.sqrt(2.0), approach, 1.0E-9);
        /* Moving across the line to the player closes no distance at all. */
        assertEquals(0.0, SpeedblockPlan.approach(0.2, 0.0, 0.0, -3.0), 1.0E-9);
    }

    @Test
    public void pathStartsAtTheFirstCellTheTargetWouldEnter() {
        /* Standing in cell 0 and running +x: the next cell is 1, then 2. */
        List<int[]> cells = SpeedblockPlan.path(0.5, 0.5, 64, 0.2, 0.0, 0.0, 2.0, 2, false);
        assertEquals(2, cells.size());
        assertArrayEquals(new int[]{1, 64, 0}, cells.get(0));
        assertArrayEquals(new int[]{2, 64, 0}, cells.get(1));
    }

    @Test
    public void pathLeavesOutTheCellTheTargetStandsIn() {
        /* Moving towards +x but not far enough to leave cell 0. */
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
        /* Cell 1 is entered 0.5 blocks along; told to lead past that, the first
           cell offered is the one after it. A block that lands while the target
           is already entering a cell is a block behind it. */
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
        /* Walking across x=0 into cell -1: a cast toward zero would call -0.1 cell 0. */
        List<int[]> cells = SpeedblockPlan.path(0.9, 0.5, 64, -0.2, 0.0, 0.0, 1.2, 1, false);
        assertEquals(1, cells.size());
        assertArrayEquals(new int[]{-1, 64, 0}, cells.get(0));
    }
}
