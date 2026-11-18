package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

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

    @Test
    public void tellyIsBackAndB4StillLoadsAsSpeedTelly() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("B4"));
        assertEquals("SPEED-TELLY", scaffold.rotationMode.getModeString());
        assertTrue(scaffold.rotationMode.parseString("TELLY"));
        assertEquals("TELLY", scaffold.rotationMode.getModeString());
        assertEquals(11, (int) scaffold.rotationMode.getValue());
        assertTrue(java.util.Arrays.asList(scaffold.rotationMode.visibleModes()).contains("TELLY"));
    }

    @Test
    public void aPassThroughSpeedTellyDropsTellysReturn() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("TELLY"));
        scaffold.verifyValue("rotations");
        scaffold.tellyReturning = true;
        scaffold.noteRotationMode();
        assertTrue("same mode, same player: kept", scaffold.tellyReturning);
        assertTrue(scaffold.rotationMode.parseString("SPEED-TELLY"));
        scaffold.verifyValue("rotations");
        assertTrue(scaffold.rotationMode.parseString("TELLY"));
        scaffold.verifyValue("rotations");
        assertFalse("dropped on the way through", scaffold.tellyReturning);
    }

    @Test
    public void godBridge2AndMoonWalkAreAddedAtTheEnd() {
        Scaffold scaffold = new Scaffold();
        assertTrue(scaffold.rotationMode.parseString("GODBRIDGE2"));
        assertEquals(14, (int) scaffold.rotationMode.getValue());
        assertTrue(scaffold.rotationMode.parseString("MOONWALK"));
        assertEquals(15, (int) scaffold.rotationMode.getValue());
        assertTrue(scaffold.rotationMode.parseString("GODBRIDGE"));
        assertEquals(4, (int) scaffold.rotationMode.getValue());
        java.util.List<String> menu = java.util.Arrays.asList(scaffold.rotationMode.visibleModes());
        assertTrue(menu.contains("GODBRIDGE2") && menu.contains("MOONWALK"));
    }

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
