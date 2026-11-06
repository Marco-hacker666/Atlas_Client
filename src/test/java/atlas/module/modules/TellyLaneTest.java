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

    @Test
    public void aHalfTurnIsCutShortAndNothingElseIs() {
        assertEquals(-45.0F, TellyLane.awayFromHalfTurn(-210.0F, -45.0F, 150.0F), 1.0E-3F);
        assertEquals(-130.0F, TellyLane.awayFromHalfTurn(-280.0F, -105.0F, 150.0F), 1.0E-3F);
        assertEquals("turning the other way", -430.0F, TellyLane.awayFromHalfTurn(-280.0F, -455.0F, 150.0F), 1.0E-3F);
    }

    @Test
    public void theGridIsEveryFortyFiveDegreesAtAnyAngle() {
        for (float yaw : new float[]{0.0F, 45.0F, -45.0F, -450.0F, 765.0F, 90.05F, -135.08F}) {
            assertTrue(String.valueOf(yaw), TellyLane.onGrid(yaw));
        }
        assertFalse(TellyLane.onGrid(47.0F));
        assertFalse(TellyLane.onGrid(-45.3F));
    }

    @Test
    public void aLookComesHomeShortOfTheGrid() {
        assertEquals(-45.5F, TellyLane.offGrid(-45.0F, -210.0F, 0.5F), 1.0E-3F);
        assertEquals(-44.5F, TellyLane.offGrid(-45.0F, 100.0F, 0.5F), 1.0E-3F);
        assertEquals("not on the grid: left alone", 28.35F, TellyLane.offGrid(28.35F, 200.0F, 0.5F), 1.0E-3F);
        assertFalse(TellyLane.onGrid(TellyLane.offGrid(-45.0F, -210.0F, 0.3F)));
    }

    @Test
    public void theTurnWaitsOnlyWhileEveryBlockStillFits() {
        assertTrue("four blocks, eleven ticks", TellyLane.waits(11, 4));
        assertTrue(TellyLane.waits(10, 4));
        assertTrue(TellyLane.waits(9, 4));
        assertFalse("turn 4, blocks 4, no spare", TellyLane.waits(8, 4));
        assertFalse("a diagonal jump has no time to give", TellyLane.waits(10, 6));
        assertFalse(TellyLane.waits(0, 1));
        assertFalse("no crossing", TellyLane.waits(-1, 1));
    }

    @Test
    public void stairsCountTheStepsLeftOnBothAxes() {
        TellyLane lane = new TellyLane(0, 0, 64, -45.0F);
        assertTrue(lane.diagonal());
        assertEquals(6, lane.stepsLeft(0, 0, 3, 3));
        assertEquals(1, lane.stepsLeft(3, 2, 3, 3));
        assertEquals(0, lane.stepsLeft(3, 3, 3, 3));
        assertEquals("past the landing counts for nothing", 2, lane.stepsLeft(5, 1, 3, 3));
    }

    @Test
    public void aBigStepHomeStopsShortOnTheWay() {
        assertEquals(-60.0F, TellyLane.shortOfHome(-210.0F, -45.0F, 15.0F), 1.0E-3F);
        assertEquals(-30.0F, TellyLane.shortOfHome(100.0F, -45.0F, 15.0F), 1.0E-3F);
        assertEquals("a small step goes home", -45.0F, TellyLane.shortOfHome(-80.0F, -45.0F, 15.0F), 1.0E-3F);
        assertEquals("across the seam", -135.0F, TellyLane.shortOfHome(120.0F, -120.0F, 15.0F), 1.0E-3F);
        assertEquals("a small step across the seam", 180.0F, TellyLane.shortOfHome(-150.0F, 180.0F, 15.0F), 1.0E-3F);
    }

    @Test
    public void aJumpGoesFromOneSideOfTheViewOrTheOther() {
        assertEquals(-45.0F, TellyLane.jumpYaw(0.0F, -1, 45.0F), 1.0E-3F);
        assertEquals(45.0F, TellyLane.jumpYaw(0.0F, 1, 45.0F), 1.0E-3F);
        assertEquals("off: the view", 90.0F, TellyLane.jumpYaw(90.0F, 1, 0.0F), 1.0E-3F);
    }
}
