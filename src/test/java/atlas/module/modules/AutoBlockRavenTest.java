package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** AutoBlock's Raven mode keeps Raven bS's millisecond-to-tick rules (2026-10-31). */
public class AutoBlockRavenTest {

    @Test
    public void millisecondsRoundUpToTicks() {
        assertEquals(0, AutoBlockRaven.msToTicks(0));
        assertEquals(1, AutoBlockRaven.msToTicks(50));
        assertEquals(3, AutoBlockRaven.msToTicks(150));
        assertEquals(4, AutoBlockRaven.msToTicks(151));
        assertEquals(4, AutoBlockRaven.msToTicks(200));
        assertEquals(10, AutoBlockRaven.msToTicks(500));
    }

    @Test
    public void theDamagedTriggerIsOneToTenTicks() {
        assertEquals(4, AutoBlockRaven.hurtTrigger(200));
        assertEquals(1, AutoBlockRaven.hurtTrigger(50));
        assertEquals(10, AutoBlockRaven.hurtTrigger(500));
        assertEquals(1, AutoBlockRaven.hurtTrigger(0));
        assertEquals(3, AutoBlockRaven.hurtTrigger(150));
    }
}
