package atlas.ui.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** HUD elements on a phone kept off the touch buttons (2026-11-11). */
public class MobileHudTest {
    private static final float W = 600.0F;
    private static final float H = 300.0F;

    @Test
    public void anElementClearOfTheCornersStays() {
        assertNull(MobileHud.nudge(250, 10, 100, 20, W, H));
    }

    @Test
    public void aWatermarkTopLeftMovesJustBelowTheButtons() {
        /* The top-left zone ends at 16% of 300 = 48; two pixels of gap. */
        assertArrayEquals(new float[]{0, 46}, MobileHud.nudge(4, 4, 80, 14, W, H), 1e-4F);
    }

    @Test
    public void aMoveAlwaysEndsOnScreenAndClear() {
        float[][] cases = {
                {4, 250, 60, 20},
                {520, 4, 76, 120},
                {500, 200, 90, 40},
                {4, 4, 80, 14},
        };
        for (float[] c : cases) {
            float[] move = MobileHud.nudge(c[0], c[1], c[2], c[3], W, H);
            assertNotNull(move);
            float x = c[0] + move[0];
            float y = c[1] + move[1];
            assertTrue(x >= 0 && y >= 0 && x + c[2] <= W && y + c[3] <= H);
            assertFalse(MobileHud.covers(x, y, c[2], c[3], W, H));
        }
    }

    @Test
    public void nothingFarAwayIsTried() {
        /* Too big to fit anywhere clear: left where it is rather than thrown across the screen. */
        assertNull(MobileHud.nudge(0, 0, 590, 290, W, H));
    }
}
