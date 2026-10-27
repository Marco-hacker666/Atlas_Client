package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** FakeLag's modes (2026-10-27): LIQUID renamed DYNAMIC, the old DYNAMIC gone; saved configs still load. */
public class FakeLagModeTest {

    @Test
    public void theModesAreLatencyDynamicRepel() {
        assertArrayEquals(new String[]{"LATENCY", "DYNAMIC", "REPEL"}, new FakeLag().mode.visibleModes());
    }

    @Test
    public void aConfigSayingLiquidLoadsAsDynamic() {
        FakeLag fakeLag = new FakeLag();
        assertTrue(fakeLag.mode.parseString("LIQUID"));
        assertEquals("DYNAMIC", fakeLag.mode.getModeString());
    }

    @Test
    public void theOtherNamesStillLoad() {
        FakeLag fakeLag = new FakeLag();
        assertTrue(fakeLag.mode.parseString("DYNAMIC"));
        assertEquals("DYNAMIC", fakeLag.mode.getModeString());
        assertTrue(fakeLag.mode.parseString("REPEL"));
        assertEquals("REPEL", fakeLag.mode.getModeString());
        assertTrue(fakeLag.mode.parseString("LATENCY"));
        assertEquals("LATENCY", fakeLag.mode.getModeString());
    }
}
