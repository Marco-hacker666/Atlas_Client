package atlas.ui.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** HUD anchors: the nearest of nine, kept through a screen change (2026-10-18). */
public class HudAnchorsTest {

    @Test
    public void elementsHangFromTheNearestThird() {
        // A 100-wide element on a 960-wide screen.
        assertEquals(0, HudAnchors.third(10, 100, 960));
        assertEquals(1, HudAnchors.third(430, 100, 960));
        assertEquals(2, HudAnchors.third(850, 100, 960));
    }

    @Test
    public void aRightAnchoredElementStaysAtTheRightEdge() {
        // Right column: placed 10 px from the right on 960, then the screen becomes 1280.
        float offset = HudAnchors.pointOf(2, 850, 100) - HudAnchors.anchorAt(2, 960); // -10
        assertEquals(-10.0F, offset, 1e-4F);
        assertEquals(1170.0F, HudAnchors.startFor(2, offset, 100, 1280), 1e-4F);
    }

    @Test
    public void aCentredElementStaysCentred() {
        float offset = HudAnchors.pointOf(1, 430, 100) - HudAnchors.anchorAt(1, 960); // 0
        assertEquals(590.0F, HudAnchors.startFor(1, offset, 100, 1280), 1e-4F);
    }

    @Test
    public void neverPushedOffScreen() {
        // A large offset on a smaller screen is clamped inside it.
        assertEquals(0.0F, HudAnchors.startFor(0, -50, 100, 640), 1e-4F);
        assertEquals(540.0F, HudAnchors.startFor(0, 900, 100, 640), 1e-4F);
    }
}
