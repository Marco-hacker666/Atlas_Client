package atlas.module.modules;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * legit-pace (2026-11-07): which modes show it, and the paced ledge rule. The flow itself needs a world.
 */
public class LegitPaceTest {

    private static final int JUMP = 0;
    private static final int SNEAK = 1;
    private static final int STOP_INPUT = 2;
    private static final int NONE = -1;

    @Test
    public void theOtherModesKeepTheirIndexAndSettings() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("WEIRD-TELLY"));
        assertEquals(13, (int) scaffold.rotationMode.getValue());
        assertFalse(scaffold.legitPace.isVisible());
        assertFalse("TELLY-BRIDGE was removed (2026-11-08)", scaffold.rotationMode.parseString("TELLY-BRIDGE"));
        assertTrue(scaffold.rotationMode.parseString("GODBRIDGE"));
        assertTrue("legit-pace is the GODBRIDGE fix", scaffold.legitPace.isVisible());
        assertTrue(scaffold.rotationMode.parseString("POLAR"));
        assertTrue(scaffold.legitPace.isVisible());
        assertTrue(scaffold.rotationMode.parseString("DEFAULT"));
        assertFalse(scaffold.legitPace.isVisible());
        assertTrue("on unless turned off", scaffold.legitPace.getValue());
    }

    @Test
    public void aJumpWithNoRoomBecomesACrouch() {
        assertEquals(SNEAK, Scaffold.pacedLedgeAction(JUMP, false, false));
        assertEquals(JUMP, Scaffold.pacedLedgeAction(JUMP, true, false));
    }

    @Test
    public void aDueCrouchIsTakenAtTheEdge() {
        assertEquals(SNEAK, Scaffold.pacedLedgeAction(NONE, true, true));
        assertEquals(SNEAK, Scaffold.pacedLedgeAction(JUMP, true, true));
        assertEquals(NONE, Scaffold.pacedLedgeAction(NONE, true, false));
        assertEquals("the player's own choice of action stays", STOP_INPUT,
                Scaffold.pacedLedgeAction(STOP_INPUT, false, true));
    }

    @Test
    public void aCrouchIsNeverShorterThanTheServerWants() {
        assertTrue("SneakAndPlace counts crouches under 2 ticks", PlacePace.MIN_SNEAK_TICKS >= 3);
    }
}
