package atlas.module.modules;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegitPaceTest {

    @Test
    public void theOtherModesKeepTheirIndexAndSettings() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("WEIRD-TELLY"));
        assertEquals(13, (int) scaffold.rotationMode.getValue());
        assertFalse(scaffold.legitPace.isVisible());
        assertFalse("TELLY-BRIDGE was removed (2026-11-08)", scaffold.rotationMode.parseString("TELLY-BRIDGE"));
        assertTrue(scaffold.rotationMode.parseString("GODBRIDGE"));
        assertTrue("legit-pace is the GODBRIDGE fix", scaffold.legitPace.isVisible());
        assertTrue("POLAR was removed (2026-11-23): its configs load GODBRIDGE",
                scaffold.rotationMode.parseString("POLAR"));
        assertEquals("GODBRIDGE", scaffold.rotationMode.getModeString());
        assertFalse(java.util.Arrays.asList(scaffold.rotationMode.visibleModes()).contains("POLAR"));
        assertTrue(scaffold.rotationMode.parseString("DEFAULT"));
        assertFalse(scaffold.legitPace.isVisible());
        assertTrue("on unless turned off", scaffold.legitPace.getValue());
    }

    @Test
    public void aCrouchIsNeverShorterThanTheServerWants() {
        assertTrue("SneakAndPlace counts crouches under 2 ticks", PlacePace.MIN_SNEAK_TICKS >= 3);
    }
}
