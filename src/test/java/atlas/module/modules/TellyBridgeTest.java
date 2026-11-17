package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** TELLY (2026-11-18): the look goes back only on the way down of a jump, or when not walking. */
public class TellyBridgeTest {

    @Test
    public void forwardOnTheGroundAndOnTheWayUp() {
        assertFalse(TellyBridge.placing(true, 0.0, true));
        assertFalse(TellyBridge.placing(false, 0.42, true));
        assertFalse("the top itself is still forward", TellyBridge.placing(false, 0.0, true));
    }

    @Test
    public void backOnTheWayDown() {
        assertTrue(TellyBridge.placing(false, -0.08, true));
        assertTrue(TellyBridge.placing(false, -0.5, true));
    }

    @Test
    public void backWhenNotWalking() {
        assertTrue(TellyBridge.placing(true, 0.0, false));
        assertTrue(TellyBridge.placing(false, 0.3, false));
    }
}
