package atlas.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** VanillaInput's bookkeeping (2026-10-26, V1). */
public class ManagedClicksTest {

    @Test
    public void clicksTakeTheirTargetsInOrder() {
        ManagedClicks<String> c = new ManagedClicks<String>();
        c.queue("first");
        c.queue("second");
        assertTrue(c.pending());
        assertEquals("first", c.next());
        assertEquals("second", c.next());
        assertNull(c.next());
        assertFalse(c.pending());
    }

    @Test
    public void pressesVanillaDroppedAreForgottenAtTheEndOfTheTick() {
        ManagedClicks<String> c = new ManagedClicks<String>();
        c.queue("dropped");
        c.clear();
        assertNull(c.next());
    }

    @Test
    public void theCrosshairIsSwappedForOneClickAndPutBack() {
        ManagedClicks<String> c = new ManagedClicks<String>();
        String during = c.swapIn("camera", "managed");
        assertEquals("managed", during);
        assertEquals("camera", c.restore(during));
        // A click of the player's own: nothing swapped, nothing changed after.
        assertEquals("camera", c.swapIn("camera", null));
        assertEquals("camera", c.restore("camera"));
    }
}
