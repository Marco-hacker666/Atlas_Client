package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** The 2026-10-31 consolidation: retired modes leave the menus and old configs load the kept ones. */
public class RetiredModesTest {

    @Test
    public void velocityKeepsFourModesAndDelayIsTheFormerLag() {
        Velocity velocity = new Velocity();
        assertArrayEquals(new String[]{"Vanilla", "Hypixel", "Delay", "Polar"}, velocity.mode.visibleModes());
        assertTrue(velocity.mode.parseString("Lag"));
        assertEquals("Delay", velocity.mode.getModeString());
        assertEquals(22, (int) velocity.mode.getValue());
        assertTrue(velocity.mode.parseString("Delay"));
        assertEquals(22, (int) velocity.mode.getValue());
        assertTrue(velocity.mode.parseString("Intave"));
        assertEquals("Polar", velocity.mode.getModeString());
        assertTrue(velocity.mode.parseString("IntaveTimer"));
        assertEquals("Polar", velocity.mode.getModeString());
    }

    @Test
    public void scaffoldRetiredRotationsLoadAsGodBridge() {
        Scaffold scaffold = new Scaffold();
        for (String old : new String[]{"SIDEWAYS", "SNAP", "3FMC", "SNAP2"}) {
            assertTrue(scaffold.rotationMode.parseString(old));
            assertEquals("GODBRIDGE", scaffold.rotationMode.getModeString());
        }
        for (String name : scaffold.rotationMode.visibleModes()) {
            assertFalse(name, name.equals("SNAP") || name.equals("SNAP2") || name.equals("3FMC") || name.equals("SIDEWAYS"));
        }
    }
}
