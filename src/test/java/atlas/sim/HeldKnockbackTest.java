package atlas.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeldKnockbackTest {

    @Test
    public void aHeldKnockbackIsFoundWhenTheGameAppliesIt() {
        HeldKnockback held = new HeldKnockback();
        held.held(0.451, 0.461, -0.759, "KnockbackDelay", 1000L);
        assertNull("another motion is not it", held.applied(0.1, 0.42, 0.0));
        HeldKnockback.Held kb = held.applied(0.451, 0.461, -0.759);
        assertNotNull(kb);
        assertEquals("KnockbackDelay", kb.by);
        assertNull("taken once", held.applied(0.451, 0.461, -0.759));
        assertTrue(held.expired(10000L).isEmpty());
    }

    @Test
    public void twoHeldAreMatchedOldestFirst() {
        HeldKnockback held = new HeldKnockback();
        held.held(0.3, 0.36, 0.0, "KnockbackDelay", 1000L);
        held.held(0.3, 0.36, 0.0, "KnockbackDelay", 1100L);
        assertEquals(1000L, held.applied(0.3, 0.36, 0.0).at);
        assertEquals(1100L, held.applied(0.3, 0.36, 0.0).at);
    }

    @Test
    public void oneNeverAppliedIsDroppedAfterTheLimit() {
        HeldKnockback held = new HeldKnockback();
        held.held(0.2, 0.4, 0.2, "Velocity", 1000L);
        assertTrue(held.expired(1000L + HeldKnockback.DROPPED_AFTER_MS).isEmpty());
        List<HeldKnockback.Held> dropped = held.expired(1001L + HeldKnockback.DROPPED_AFTER_MS);
        assertEquals(1, dropped.size());
        assertEquals("Velocity", dropped.get(0).by);
        assertNull(held.applied(0.2, 0.4, 0.2));
    }
}
