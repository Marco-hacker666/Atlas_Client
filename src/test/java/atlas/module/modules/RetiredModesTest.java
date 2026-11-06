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

    /** 2026-11-04 (owner): SPEED-TELLY is in the menu; it went on the end, so saved configs keep their indices. */
    @Test
    public void scaffoldSpeedTellyIsAddedAtTheEnd() {
        Scaffold scaffold = new Scaffold();
        assertTrue(java.util.Arrays.asList(scaffold.rotationMode.visibleModes()).contains("SPEED-TELLY"));
        assertTrue(scaffold.rotationMode.parseString("SPEED-TELLY"));
        assertEquals(12, (int) scaffold.rotationMode.getValue());
        assertFalse("off, it asks nothing of Clutch", scaffold.isSpeedTelly());
        assertFalse("nor keep-y", scaffold.isSpeedTellyKeepY());
        assertTrue("auto jump comes on", scaffold.tellyAutoJump.getValue());
        assertTrue(scaffold.tellyAutoJump.isVisible());
        assertTrue(scaffold.rotationMode.parseString("POLAR"));
        assertEquals(10, (int) scaffold.rotationMode.getValue());
        assertFalse("its switch is this mode's", scaffold.tellyAutoJump.isVisible());
    }

    /** 2026-11-04 (owner: "delete Telly, keep Speed Telly"): gone from the menu; old configs load SPEED-TELLY. */
    @Test
    public void scaffoldTellyLoadsAsSpeedTelly() {
        Scaffold scaffold = new Scaffold();
        for (String old : new String[]{"TELLY", "B4"}) {
            assertTrue(scaffold.rotationMode.parseString(old));
            assertEquals("SPEED-TELLY", scaffold.rotationMode.getModeString());
        }
        assertFalse(java.util.Arrays.asList(scaffold.rotationMode.visibleModes()).contains("TELLY"));
    }

    /** 2026-11-06 (owner): the angled jump is a mode of its own, on the end; only it shows the angle. */
    @Test
    public void weirdTellyIsAddedAtTheEnd() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("WEIRD-TELLY"));
        assertEquals(13, (int) scaffold.rotationMode.getValue());
        assertTrue(java.util.Arrays.asList(scaffold.rotationMode.visibleModes()).contains("WEIRD-TELLY"));
        assertTrue(scaffold.tellyJumpAngle.isVisible());
        assertTrue("same switches as SPEED-TELLY", scaffold.tellyAutoJump.isVisible());
        assertFalse("off, nothing is angled", scaffold.isWeirdTelly());
        assertTrue(scaffold.rotationMode.parseString("SPEED-TELLY"));
        assertFalse(scaffold.tellyJumpAngle.isVisible());
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
