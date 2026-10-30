package atlas.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** The bow/throw aim (2026-10-31, LiquidBounce's faceTrajectory): right angles, and never NaN. */
public class TrajectoryAimTest {

    @Test
    public void bowSpeedFollowsVanillaDraw() {
        assertEquals(0.0F, TrajectoryAim.bowVelocity(0), 1.0E-6F);
        assertEquals((0.25F + 1.0F) / 3.0F, TrajectoryAim.bowVelocity(10), 1.0E-6F);
        assertEquals(1.0F, TrajectoryAim.bowVelocity(20), 1.0E-6F);
        assertEquals(1.0F, TrajectoryAim.bowVelocity(400), 1.0E-6F);
        assertEquals(0.0F, TrajectoryAim.bowVelocity(-5), 1.0E-6F);
    }

    @Test
    public void aimsAlongTheTargetsDirection() {
        float[] south = TrajectoryAim.solve(0.0, 0.0, 20.0, 1.0F, TrajectoryAim.ARROW_GRAVITY);
        assertNotNull(south);
        assertEquals(0.0F, wrap(south[0]), 1.0E-3F);
        float[] west = TrajectoryAim.solve(-20.0, 0.0, 0.0, 1.0F, TrajectoryAim.ARROW_GRAVITY);
        assertNotNull(west);
        assertEquals(90.0F, wrap(west[0]), 1.0E-3F);
    }

    @Test
    public void aFarTargetOnTheSameLevelIsAimedAbove() {
        float[] near = TrajectoryAim.solve(0.0, 0.0, 10.0, 1.0F, TrajectoryAim.ARROW_GRAVITY);
        float[] far = TrajectoryAim.solve(0.0, 0.0, 40.0, 1.0F, TrajectoryAim.ARROW_GRAVITY);
        assertNotNull(near);
        assertNotNull(far);
        /* Minecraft pitch: negative is up. Farther needs more lift. */
        assertTrue(near[1] < 0.0F);
        assertTrue(far[1] < near[1]);
    }

    @Test
    public void outOfRangeIsNoAimNotNaN() {
        assertNull(TrajectoryAim.solve(0.0, 0.0, 500.0, 0.2F, TrajectoryAim.ARROW_GRAVITY));
        assertNull(TrajectoryAim.solve(0.0, 0.0, 300.0, TrajectoryAim.THROWN_VELOCITY, TrajectoryAim.THROWN_GRAVITY));
    }

    @Test
    public void degenerateInputIsNoAim() {
        assertNull(TrajectoryAim.solve(0.0, 5.0, 0.0, 1.0F, TrajectoryAim.ARROW_GRAVITY));
        assertNull(TrajectoryAim.solve(0.0, 0.0, 10.0, 0.0F, TrajectoryAim.ARROW_GRAVITY));
        assertNull(TrajectoryAim.solve(Double.NaN, 0.0, 10.0, 1.0F, TrajectoryAim.ARROW_GRAVITY));
        assertNull(TrajectoryAim.solve(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0F, TrajectoryAim.ARROW_GRAVITY));
    }

    @Test
    public void everyAnswerIsAUsableLook() {
        for (double d = 1.0; d <= 60.0; d += 1.5) {
            for (double y = -20.0; y <= 20.0; y += 2.5) {
                for (float v : new float[]{0.1F, 0.5F, 1.0F}) {
                    float[] look = TrajectoryAim.solve(d * 0.6, y, d * 0.8, v, TrajectoryAim.ARROW_GRAVITY);
                    if (look != null) {
                        assertFalse(Float.isNaN(look[0]) || Float.isNaN(look[1]));
                        assertTrue(look[1] >= -90.0F && look[1] <= 90.0F);
                    }
                }
            }
        }
    }

    private static float wrap(float angle) {
        float a = angle % 360.0F;
        if (a >= 180.0F) {
            a -= 360.0F;
        }
        if (a < -180.0F) {
            a += 360.0F;
        }
        return a;
    }
}
