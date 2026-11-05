package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** SPEED-TELLY's lane (2026-11-04): one direction, every cell next to the last. */
public class TellyLaneTest {

    private static final float[] YAWS = {0F, 45F, 90F, 135F, 180F, -135F, -90F, -45F};

    /** 2026-11-04 (owner: "cannot stack diagonally"): the stairs follow the flight, however lopsided it is. */
    @Test
    public void diagonalStairsReachWhereTheJumpLands() {
        int[][] landings = {{2, 4}, {4, 2}, {3, 3}, {0, 5}, {5, 0}};
        for (int[] landing : landings) {
            for (float yaw : new float[]{45F, 135F, -135F, -45F}) {
                TellyLane lane = new TellyLane(10, 20, 64, yaw);
                int landX = 10 + lane.stepX * landing[0];
                int landZ = 20 + lane.stepZ * landing[1];
                int[] head = {10, 20};
                int cells = 0;
                while (!lane.stepsToward(head[0], head[1], landX, landZ).isEmpty()) {
                    int[] next = lane.stepsToward(head[0], head[1], landX, landZ).get(0);
                    assertEquals("joined", 1, Math.abs(next[0] - head[0]) + Math.abs(next[1] - head[1]));
                    head = next;
                    assertTrue("no block past the landing", ++cells <= landing[0] + landing[1]);
                }
                assertArrayEquals(new int[]{landX, landZ}, head);
            }
        }
    }

    @Test
    public void bothStepsAreOfferedWhileBothAxesRemain() {
        TellyLane lane = new TellyLane(0, 0, 64, 45F);
        assertEquals(-1, lane.stepX);
        assertEquals(1, lane.stepZ);
        assertEquals(2, lane.stepsToward(0, 0, -2, 3).size());
        assertArrayEquals("the longer way first", new int[]{0, 1}, lane.stepsToward(0, 0, -2, 3).get(0));
        assertEquals("only the axis left", 1, lane.stepsToward(-2, 0, -2, 3).size());
        assertTrue("nothing behind the head", lane.stepsToward(-2, 3, -1, 2).isEmpty());
    }

    @Test
    public void yawZeroGoesSouthAndNinetyWest() {
        TellyLane south = new TellyLane(10, 20, 64, 3.0F);
        assertArrayEquals(new int[]{10, 23}, south.cell(3));
        TellyLane west = new TellyLane(10, 20, 64, 88.0F);
        assertArrayEquals(new int[]{7, 20}, west.cell(3));
    }

    @Test
    public void everyCellSharesAFaceWithTheOneBefore() {
        for (float yaw : YAWS) {
            TellyLane lane = new TellyLane(-4, 9, 64, yaw);
            assertArrayEquals(new int[]{-4, 9}, lane.cell(0));
            for (int i = 1; i < 40; i++) {
                int[] before = lane.cell(i - 1);
                int[] now = lane.cell(i);
                assertEquals("yaw " + yaw + " cell " + i, 1, Math.abs(now[0] - before[0]) + Math.abs(now[1] - before[1]));
            }
        }
    }

    @Test
    public void indexOfUndoesCell() {
        for (float yaw : YAWS) {
            TellyLane lane = new TellyLane(30, -12, 64, yaw);
            for (int i = -10; i < 40; i++) {
                int[] column = lane.cell(i);
                assertEquals("yaw " + yaw, i, lane.indexOf(column[0], column[1]));
            }
        }
    }

    @Test
    public void aCellBesideTheLaneIsOff() {
        TellyLane straight = new TellyLane(0, 0, 64, 0.0F);
        assertEquals(TellyLane.OFF, straight.indexOf(1, 5));
        TellyLane diagonal = new TellyLane(0, 0, 64, -45.0F);
        assertEquals(TellyLane.OFF, diagonal.indexOf(3, 0));
        assertEquals(TellyLane.OFF, diagonal.indexOf(0, 3));
    }

    @Test
    public void indexAtFollowsThePlayerAlongTheLaneEvenWhenBesideIt() {
        for (float yaw : YAWS) {
            TellyLane lane = new TellyLane(30, -12, 64, yaw);
            for (int i = 0; i < 30; i++) {
                int[] column = lane.cell(i);
                assertEquals("yaw " + yaw, i, lane.indexAt(column[0] + 0.5, column[1] + 0.5));
            }
        }
        TellyLane south = new TellyLane(0, 0, 64, 0.0F);
        assertEquals(6, south.indexAt(2.9, 6.4));
    }

    @Test
    public void aheadIsOnTheLineFurtherAlong() {
        TellyLane south = new TellyLane(0, 0, 64, 0.0F);
        assertArrayEquals(new double[]{0.5, 9.2}, south.ahead(0.9, 6.2, 3.0), 1.0E-9);
        TellyLane diagonal = new TellyLane(0, 0, 64, -45.0F);
        double[] point = diagonal.ahead(0.5, 0.5, 2.0);
        double[] again = diagonal.ahead(point[0], point[1], 0.0);
        assertArrayEquals("already on the line", point, again, 1.0E-9);
        assertTrue("further along", point[0] > 0.5 && point[1] > 0.5);
    }
}
