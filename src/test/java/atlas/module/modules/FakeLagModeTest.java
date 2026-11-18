package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FakeLagModeTest {
    @Test
    public void theModesAreLatencyAndDynamic() {
        assertArrayEquals(new String[]{"LATENCY", "DYNAMIC"}, new FakeLag().mode.visibleModes());
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
        assertEquals("DYNAMIC", fakeLag.mode.getModeString());
        assertTrue(fakeLag.mode.parseString("LATENCY"));
        assertEquals("LATENCY", fakeLag.mode.getModeString());
    }
}
