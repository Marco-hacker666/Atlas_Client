package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** NoSlow's Hit-Window is listed as a sword mode only (2026-10-20). */
public class NoSlowHitWindowTest {

    @Test
    public void theModeNameIsStable() {
        assertEquals("Hit-Window", NoSlowHitWindow.MODE);
    }

    /** A2 (2026-10-26): with hits first, a window never forces a click to wait. */
    @Test
    public void hitFirstLeavesRoomForTheNextClick() {
        // Opening needs the click 3+ ticks away: 2 ticks before the window may close, then a tick clear.
        org.junit.Assert.assertFalse(NoSlowHitWindow.mayOpenForAura(true, 0));
        org.junit.Assert.assertFalse(NoSlowHitWindow.mayOpenForAura(true, 2));
        org.junit.Assert.assertTrue(NoSlowHitWindow.mayOpenForAura(true, 3));
        // Walk a window through ticks: opened with the click 3 away, it closes with 1 left, the click a tick later.
        int clickIn = 3;
        int openedAt = 0;
        int closedAt = -1;
        for (int t = 1; t <= 3 && closedAt < 0; t++) {
            clickIn--;
            if (t - openedAt >= 2 && NoSlowHitWindow.mustCloseForAura(true, clickIn)) {
                closedAt = t;
            }
        }
        assertEquals(2, closedAt);
        assertEquals(1, clickIn);
    }

    @Test
    public void blockFirstNeverClosesForAClick() {
        org.junit.Assert.assertTrue(NoSlowHitWindow.mayOpenForAura(false, 0));
        org.junit.Assert.assertFalse(NoSlowHitWindow.mustCloseForAura(false, 0));
    }
}
