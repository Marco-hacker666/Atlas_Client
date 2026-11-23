package atlas.module.modules;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LinePlacementTest {
    @Test
    public void theTargetCellComesFirstAndTheCellsBesideTheLineLast() {
        LinePlacement.Line line = LinePlacement.walkingLine(0.5, 0.5, 0.0F, true, null, null);
        List<int[]> cells = LinePlacement.candidates(0, 63, 0, line);
        assertEquals(18, cells.size());
        assertArrayEquals(new int[]{0, 63, 0}, cells.get(0));
        int firstOff = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i)[0] != 0 && firstOff < 0) {
                firstOff = i;
            }
        }
        for (int i = 0; i < firstOff; i++) {
            assertEquals("cells on the line (x = 0) before any beside it", 0, cells.get(i)[0]);
        }
        assertTrue(cells.get(1)[0] == 0 && cells.get(1)[1] == 63);
        assertTrue(cells.get(2)[0] == 0 && cells.get(2)[1] == 63);
    }

    @Test
    public void walkingDiagonallyTheDiagonalNeighbourBeatsTheSideOne() {
        LinePlacement.Line line = LinePlacement.walkingLine(0.5, 0.5, -45.0F, true, null, null);
        List<int[]> cells = LinePlacement.candidates(0, 63, 0, line);
        int diagonal = index(cells, 1, 63, 1);
        int side = index(cells, 1, 63, -1);
        assertTrue(diagonal < side);
    }

    @Test
    public void theLastTwoBlocksSetTheLineWhenTheyAgreeWithTheWalk() {
        LinePlacement.Line line = LinePlacement.walkingLine(0.9, 3.2, 0.0F, true,
                new int[]{0, 63, 3}, new int[]{0, 63, 2});
        assertEquals(0.5, line.x, 1.0E-9);
        assertEquals(0.0, line.distance(0.5, 10.5), 1.0E-9);
        LinePlacement.Line turned = LinePlacement.walkingLine(0.9, 3.2, 90.0F, true,
                new int[]{0, 63, 3}, new int[]{0, 63, 2});
        assertEquals("walking another way: the line goes through the player", 0.0,
                turned.distance(5.0, 3.2), 1.0E-9);
    }

    @Test
    public void standingStillTheNearestCellToThePlayerWins() {
        LinePlacement.Line still = LinePlacement.walkingLine(0.9, 0.5, 0.0F, false, null, null);
        List<int[]> cells = LinePlacement.candidates(0, 63, 0, still);
        assertArrayEquals(new int[]{0, 63, 0}, cells.get(0));
        assertTrue(index(cells, 1, 63, 0) < index(cells, -1, 63, 0));
    }

    @Test
    public void aFaceTurnedToTheEyesScoresAboveOneTurnedAway() {
        double top = LinePlacement.facing(0.5, 65.62, 0.5, 0.5, 64.0, 0.5, 0, 1, 0);
        double bottom = LinePlacement.facing(0.5, 65.62, 0.5, 0.5, 63.0, 0.5, 0, -1, 0);
        assertEquals(1.0, top, 1.0E-9);
        assertTrue(bottom < 0.0);
    }

    @Test
    public void stabilizeStepsBackTowardsTheLineWithAWholeKey() {
        LinePlacement.Line line = new LinePlacement.Line(0.5, 0.0, 0.0, 1.0);
        assertEquals(0.0F, LinePlacement.stabilizeKey(line, 0.6, 3.0, 0.0, 0.2, 0.0F), 0.0F);
        float key = LinePlacement.stabilizeKey(line, 0.9, 3.0, 0.0, 0.2, 0.0F);
        assertEquals("yaw 0: left (+1) is +x, the line is at -x", -1.0F, key, 0.0F);
        assertEquals(1.0F, LinePlacement.stabilizeKey(line, 0.1, 3.0, 0.0, 0.2, 0.0F), 0.0F);
        assertEquals("drifting away but within the looser bound", 0.0F,
                LinePlacement.stabilizeKey(line, 0.65, 3.0, 0.1, 0.2, 0.0F), 0.0F);
        assertEquals("heading back: kept correcting until close", -1.0F,
                LinePlacement.stabilizeKey(line, 0.65, 3.0, -0.1, 0.2, 0.0F), 0.0F);
        assertEquals(0.0F, LinePlacement.stabilizeKey(new LinePlacement.Line(0.5, 0.5, 0, 0), 3, 3, 0, 0, 0F), 0.0F);
    }

    private static int index(List<int[]> cells, int x, int y, int z) {
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            if (c[0] == x && c[1] == y && c[2] == z) {
                return i;
            }
        }
        return -1;
    }
}
