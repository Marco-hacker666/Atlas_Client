package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NoSlowHitWindowTest {
    @Test
    public void theModeNameIsStable() {
        assertEquals("Hit-Window", NoSlowHitWindow.MODE);
    }

    @Test
    public void hitFirstLeavesRoomForTheNextClick() {
        org.junit.Assert.assertFalse(NoSlowHitWindow.mayOpenForAura(true, 0));
        org.junit.Assert.assertFalse(NoSlowHitWindow.mayOpenForAura(true, 2));
        org.junit.Assert.assertTrue(NoSlowHitWindow.mayOpenForAura(true, 3));

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
