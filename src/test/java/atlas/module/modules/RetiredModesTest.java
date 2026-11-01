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
        for (String old : new String[]{"SIDEWAYS", "3FMC"}) {
            assertTrue(scaffold.rotationMode.parseString(old));
            assertEquals("GODBRIDGE", scaffold.rotationMode.getModeString());
        }
        for (String name : scaffold.rotationMode.visibleModes()) {
            assertFalse(name, name.equals("3FMC") || name.equals("SIDEWAYS") || name.equals("SNAP2"));
        }
    }

    /** 2026-11-01 (owner): SNAP is back in the menu; SNAP2 loads as itself again, hidden as before. */
    @Test
    public void scaffoldSnapIsBack() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("SNAP"));
        assertEquals("SNAP", scaffold.rotationMode.getModeString());
        assertTrue(scaffold.rotationMode.parseString("SNAP2"));
        assertEquals("SNAP2", scaffold.rotationMode.getModeString());
        assertTrue(java.util.Arrays.asList(scaffold.rotationMode.visibleModes()).contains("SNAP"));
    }
}
