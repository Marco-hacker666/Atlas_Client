package atlas.combat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** SpeedBlocker's Press burst and the walled-run check (2026-11-02). */
public class PressBurstTest {

    @Test
    public void aTapRunsAtOnceAndForItsLength() {
        PressBurst burst = new PressBurst();
        assertFalse(burst.update(9, false, 60));
        assertTrue("the tick the key goes down", burst.update(10, true, 60));
        for (int t = 11; t <= 70; t++) {
            assertTrue("t=" + t, burst.update(t, false, 60));
        }
        assertFalse(burst.update(71, false, 60));
    }

    @Test
    public void holdingKeepsItGoingAndTheLengthCountsFromTheRelease() {
        PressBurst burst = new PressBurst();
        for (int t = 0; t <= 200; t++) {
            assertTrue(burst.update(t, true, 20));
        }
        assertTrue(burst.update(220, false, 20));
        assertFalse(burst.update(221, false, 20));
    }

    @Test
    public void endStopsATapButAHeldKeyStartsItAgain() {
        PressBurst burst = new PressBurst();
        burst.update(0, true, 60);
        burst.update(1, false, 60);
        burst.lock(42);
        burst.end();
        assertEquals(-1, burst.target());
        assertFalse("too close: the tap is over", burst.update(2, false, 60));

        burst.update(10, true, 60);
        burst.end();
        assertTrue("still held: the next tick runs again", burst.update(11, true, 60));
    }

    @Test
    public void aNewPressDropsTheLockedTarget() {
        PressBurst burst = new PressBurst();
        burst.update(0, true, 60);
        burst.lock(7);
        burst.update(1, true, 60);
        assertEquals("held: kept", 7, burst.target());
        burst.update(2, false, 60);
        assertEquals("released within the burst: kept", 7, burst.target());
        burst.update(3, true, 60);
        assertEquals("pressed again: chosen afresh", -1, burst.target());
    }

    private static Set<String> solid(int[]... cells) {
        Set<String> out = new HashSet<String>();
        for (int[] c : cells) {
            out.add(c[0] + "," + c[1] + "," + c[2]);
        }
        return out;
    }

    @Test
    public void aWallInTheRunIsFoundBeforeThePlayerNotAfter() {
        /* A run along +x at y 64 towards a player standing in x 5. */
        List<int[]> cells = new ArrayList<int[]>();
        for (int x = 1; x <= 8; x++) {
            cells.add(new int[]{x, 64, 0, x * 3});
        }
        Set<String> walls = solid(new int[]{3, 64, 0});
        assertEquals(2, SpeedblockPlan.firstWall(cells, false, 5.5, 0.5, c -> walls.contains(c[0] + "," + c[1] + "," + c[2])));
        Set<String> behind = solid(new int[]{7, 64, 0});
        assertEquals("past the player it does not count", -1,
                SpeedblockPlan.firstWall(cells, false, 5.5, 0.5, c -> behind.contains(c[0] + "," + c[1] + "," + c[2])));
        assertEquals(-1, SpeedblockPlan.firstWall(cells, false, 5.5, 0.5, c -> false));
    }

    @Test
    public void doubleHeightNeedsBothCells() {
        List<int[]> cells = new ArrayList<int[]>(Arrays.asList(
                new int[]{1, 64, 0, 3}, new int[]{1, 65, 0, 3},
                new int[]{2, 64, 0, 6}, new int[]{2, 65, 0, 6}));
        Set<String> feetOnly = solid(new int[]{1, 64, 0});
        assertEquals("a feet block alone can be jumped: still open", -1,
                SpeedblockPlan.firstWall(cells, true, 9.5, 0.5, c -> feetOnly.contains(c[0] + "," + c[1] + "," + c[2])));
        Set<String> both = solid(new int[]{2, 64, 0}, new int[]{2, 65, 0});
        assertEquals(2, SpeedblockPlan.firstWall(cells, true, 9.5, 0.5, c -> both.contains(c[0] + "," + c[1] + "," + c[2])));
    }
}
