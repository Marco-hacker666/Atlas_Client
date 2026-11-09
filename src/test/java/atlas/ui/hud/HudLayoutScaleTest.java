package atlas.ui.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Resizing HUD elements about a point (2026-11-09). */
public class HudLayoutScaleTest {

    @Test
    public void unscaledRectIsUnchanged() {
        assertArrayEquals(new float[]{10, 20, 30, 40}, HudLayout.scaledRect(5, 5, 1.0F, 10, 20, 30, 40), 1e-4F);
    }

    @Test
    public void scalingAboutTheTopLeftKeepsTheCorner() {
        assertArrayEquals(new float[]{10, 20, 60, 80}, HudLayout.scaledRect(10, 20, 2.0F, 10, 20, 30, 40), 1e-4F);
    }

    @Test
    public void scalingAboutTheBottomRightKeepsThatCorner() {
        /* A 100 x 50 box with its bottom-right corner at (200, 150), doubled about that corner. */
        float[] area = HudLayout.scaledRect(200, 150, 2.0F, 100, 100, 100, 50);
        assertArrayEquals(new float[]{0, 50, 200, 100}, area, 1e-4F);
        assertEquals(200.0F, area[0] + area[2], 1e-4F);
        assertEquals(150.0F, area[1] + area[3], 1e-4F);
    }

    @Test
    public void scalingAboutTheCentreKeepsTheCentre() {
        float[] area = HudLayout.scaledRect(50, 50, 0.5F, 0, 0, 100, 100);
        assertArrayEquals(new float[]{25, 25, 50, 50}, area, 1e-4F);
    }

    @Test
    public void wheelStepsLandOnRoundNumbersInsideTheRange() {
        assertEquals(1.05F, HudLayout.clampScale(1.0F + 0.05F, 0.5F, 2.0F), 1e-6F);
        assertEquals(1.2F, HudLayout.clampScale(1.1999999F, 0.5F, 2.0F), 1e-6F);
        assertEquals(2.0F, HudLayout.clampScale(2.04F, 0.5F, 2.0F), 1e-6F);
        assertEquals(0.5F, HudLayout.clampScale(0.3F, 0.5F, 2.0F), 1e-6F);
    }
}
