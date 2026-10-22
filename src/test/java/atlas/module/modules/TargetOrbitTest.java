package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** TargetOrbit's colours, and easing (2026-10-18). */
public class TargetOrbitTest {

    @Test
    public void mixRunsChannelByChannel() {
        assertEquals(0xFF000000, TargetOrbit.mix(0xFF000000, 0xFFFFFFFF, 0.0F));
        assertEquals(0xFFFFFFFF, TargetOrbit.mix(0xFF000000, 0xFFFFFFFF, 1.0F));
        assertEquals(0xFF808080, TargetOrbit.mix(0xFF000000, 0xFFFFFFFF, 0.5F));
    }

    @Test
    public void healthKeepsTheColourDownToHalfThenRunsToRed() {
        int base = 0xFFB59CFF;
        assertEquals(base, TargetOrbit.healthColor(base, 1.0F));
        assertEquals(base, TargetOrbit.healthColor(base, 0.5F));
        assertEquals(0xFFFF4D5E, TargetOrbit.healthColor(base, 0.0F));
    }

    @Test
    public void arrivalStartsAtZeroOvershootsThenSettlesAtOne() {
        assertEquals(0.0F, TargetOrbit.easeOutBack(0.0F), 1.0E-5F);
        assertEquals(1.0F, TargetOrbit.easeOutBack(1.0F), 1.0E-5F);
        assertTrue(TargetOrbit.easeOutBack(0.7F) > 1.0F);
        assertEquals(1.0F, TargetOrbit.easeOutBack(5.0F), 1.0E-5F);
    }
}
