package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** NoSlow's Hit-Window is listed as a sword mode only (2026-10-20). */
public class NoSlowHitWindowTest {

    @Test
    public void theModeNameIsStable() {
        assertEquals("Hit-Window", NoSlowHitWindow.MODE);
    }
}
