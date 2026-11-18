package atlas.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class MoveFixTurnTest {
    private static final float E = 1e-4F;

    @Test
    public void sameYawKeepsTheInput() {
        assertArrayEquals(new float[]{1, 0}, MoveUtil.turnInput(30, 30, 1, 0), E);
        assertArrayEquals(new float[]{1, 1}, MoveUtil.turnInput(30, 30, 1, 1), E);
    }

    @Test
    public void walkingForwardWithTheServerYawTurnedAround() {
        assertArrayEquals(new float[]{-1, 0}, MoveUtil.turnInput(0, 180, 1, 0), E);

        assertArrayEquals(new float[]{0, -1}, MoveUtil.turnInput(0, -90, 1, 0), E);
        assertArrayEquals(new float[]{0, 1}, MoveUtil.turnInput(0, 90, 1, 0), E);
    }

    @Test
    public void strengthIsKept() {
        assertArrayEquals(new float[]{-0.3F, 0}, MoveUtil.turnInput(0, 180, 0.3F, 0), E);
        assertArrayEquals(new float[]{0, 0.8F}, MoveUtil.turnInput(0, 90, 0.8F, 0), E);
    }

    @Test
    public void releasedForwardStaysReleased() {
        assertArrayEquals(new float[]{0, 0}, MoveUtil.turnInput(0, 135, 0, 0), E);
    }

    @Test
    public void nearestOfTheEightDirections() {
        assertArrayEquals(new float[]{1, 0}, MoveUtil.turnInput(0, 20, 1, 0), E);

        assertArrayEquals(new float[]{1, 1}, MoveUtil.turnInput(0, 40, 1, 0), E);
    }
}
