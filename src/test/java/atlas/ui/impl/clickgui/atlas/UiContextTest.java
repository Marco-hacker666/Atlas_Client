package atlas.ui.impl.clickgui.atlas;

import org.junit.Test;

import static org.junit.Assert.*;

/** The menu's hit registry and motion, moved into UiContext (2026-10-23, GUI redesign M2). */
public class UiContextTest {

    @Test
    public void theNewestHitUnderThePointerWins() {
        UiContext ui = new UiContext();
        ui.hit("row", "a", 0, 0, 100, 20);
        ui.hit("switch", "a", 80, 4, 96, 16);
        assertEquals("switch", ui.hitAt(85, 10).kind);
        assertEquals("row", ui.hitAt(10, 10).kind);
        assertNull(ui.hitAt(150, 10));
        ui.beginFrame();
        assertNull(ui.hitAt(85, 10));
    }

    @Test
    public void edgesAreInside() {
        UiContext ui = new UiContext();
        ui.hit("chip", null, 10, 10, 20, 20);
        assertNotNull(ui.hitAt(10, 10));
        assertNotNull(ui.hitAt(20, 20));
        assertNull(ui.hitAt(20.01F, 20));
    }

    @Test
    public void fadesStartAtTheirTargetAndApproachTheNext() {
        UiContext ui = new UiContext();
        ui.delta = 0.016F;
        assertEquals(1.0F, ui.ease("hover", true, 12.0F), 0.0F);
        float half = ui.ease("hover", false, 12.0F);
        assertTrue(half > 0.0F && half < 1.0F);
        for (int i = 0; i < 200; i++) {
            ui.ease("hover", false, 12.0F);
        }
        assertEquals(0.0F, ui.ease("hover", false, 12.0F), 0.0F);
        ui.set("arrive", 0.0F);
        assertTrue(ui.toward("arrive", 1.0F, 11.0F) < 1.0F);
    }

    @Test
    public void withoutAnimationsSpringsSnapAndScrollsJump() {
        UiContext ui = new UiContext();
        ui.animations = false;
        ui.smoothScroll = false;
        ui.spring("pill", 0.0F, 380.0F, 26.0F);
        assertEquals(40.0F, ui.spring("pill", 40.0F, 380.0F, 26.0F).value, 0.0F);
        assertEquals(120.0F, ui.scrollEase("list", 120.0F), 0.0F);
    }

    @Test
    public void aPulseRunsOnceAndEnds() throws InterruptedException {
        UiContext ui = new UiContext();
        assertEquals(-1.0F, ui.pulseAt("tog", 400L), 0.0F);
        ui.pulse("tog");
        float t = ui.pulseAt("tog", 400L);
        assertTrue(t >= 0.0F && t < 1.0F);
        Thread.sleep(5L);
        assertEquals(-1.0F, ui.pulseAt("tog", 1L), 0.0F);
        assertEquals("over is over", -1.0F, ui.pulseAt("tog", 400L), 0.0F);
    }

    @Test
    public void resetForgetsEverything() {
        UiContext ui = new UiContext();
        ui.hit("row", null, 0, 0, 10, 10);
        ui.set("arrive", 0.0F);
        ui.reset();
        assertNull(ui.hitAt(5, 5));
        assertEquals(1.0F, ui.toward("arrive", 1.0F, 11.0F), 0.0F);
    }
}
