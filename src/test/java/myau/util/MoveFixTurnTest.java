package myau.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/** MoveUtil.turnInput: the input as it stands, turned to the move yaw (2026-10-17). */
public class MoveFixTurnTest {
    private static final float E = 1e-4F;

    @Test
    public void sameYawKeepsTheInput() {
        assertArrayEquals(new float[]{1, 0}, MoveUtil.turnInput(30, 30, 1, 0), E);
        assertArrayEquals(new float[]{1, 1}, MoveUtil.turnInput(30, 30, 1, 1), E);
    }

    @Test
    public void walkingForwardWithTheServerYawTurnedAround() {
        // W at camera 0, server told 180: the world direction is behind the server yaw -> S.
        assertArrayEquals(new float[]{-1, 0}, MoveUtil.turnInput(0, 180, 1, 0), E);
        // Server told 90 (to the right of the camera's... minus side): W becomes a strafe.
        assertArrayEquals(new float[]{0, -1}, MoveUtil.turnInput(0, -90, 1, 0), E);
        assertArrayEquals(new float[]{0, 1}, MoveUtil.turnInput(0, 90, 1, 0), E);
    }

    @Test
    public void strengthIsKept() {
        // Sneaking (0.3) and a soft forward (0.8) keep their strength, turned.
        assertArrayEquals(new float[]{-0.3F, 0}, MoveUtil.turnInput(0, 180, 0.3F, 0), E);
        assertArrayEquals(new float[]{0, 0.8F}, MoveUtil.turnInput(0, 90, 0.8F, 0), E);
    }

    @Test
    public void releasedForwardStaysReleased() {
        // SprintReset let go of W this tick: nothing to turn.
        assertArrayEquals(new float[]{0, 0}, MoveUtil.turnInput(0, 135, 0, 0), E);
    }

    @Test
    public void nearestOfTheEightDirections() {
        // W at camera 0, server 30: 30 degrees off -> still forward (nearest of 45-degree steps).
        assertArrayEquals(new float[]{1, 0}, MoveUtil.turnInput(0, 20, 1, 0), E);
        // W at camera 0, server 40: closer to the diagonal.
        assertArrayEquals(new float[]{1, 1}, MoveUtil.turnInput(0, 40, 1, 0), E);
    }
}
