package atlas.ui.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HudAnchorsTest {
    @Test
    public void elementsHangFromTheNearestThird() {
        assertEquals(0, HudAnchors.third(10, 100, 960));
        assertEquals(1, HudAnchors.third(430, 100, 960));
        assertEquals(2, HudAnchors.third(850, 100, 960));
    }

    @Test
    public void aRightAnchoredElementStaysAtTheRightEdge() {
        float offset = HudAnchors.pointOf(2, 850, 100) - HudAnchors.anchorAt(2, 960);
        assertEquals(-10.0F, offset, 1e-4F);
        assertEquals(1170.0F, HudAnchors.startFor(2, offset, 100, 1280), 1e-4F);
    }

    @Test
    public void aCentredElementStaysCentred() {
        float offset = HudAnchors.pointOf(1, 430, 100) - HudAnchors.anchorAt(1, 960);
        assertEquals(590.0F, HudAnchors.startFor(1, offset, 100, 1280), 1e-4F);
    }

    @Test
    public void neverPushedOffScreen() {
        assertEquals(0.0F, HudAnchors.startFor(0, -50, 100, 640), 1e-4F);
        assertEquals(540.0F, HudAnchors.startFor(0, 900, 100, 640), 1e-4F);
    }
}
