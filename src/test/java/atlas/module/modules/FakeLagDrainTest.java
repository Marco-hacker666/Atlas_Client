package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FakeLagDrainTest {
    @Test
    public void runningAtEachOtherDrainsEarly() {
        assertTrue(FakeLag.mustDrain(4.85, 3.35, 0.5, 4, 2));
    }

    @Test
    public void farAwayKeepsHolding() {
        assertFalse(FakeLag.mustDrain(7.0, 3.35, 0.5, 4, 2));
    }

    @Test
    public void notClosingOrNothingHeldNeverDrains() {
        assertFalse(FakeLag.mustDrain(4.0, 3.35, 0.0, 6, 2));
        assertFalse(FakeLag.mustDrain(4.0, 3.35, -0.3, 6, 2));
        assertFalse(FakeLag.mustDrain(3.6, 3.35, 0.6, 0, 2));
    }

    @Test
    public void aFasterCatchUpWaitsLonger() {
        assertTrue(FakeLag.mustDrain(5.35, 3.35, 0.5, 6, 2));
        assertFalse(FakeLag.mustDrain(5.35, 3.35, 0.5, 6, 4));
    }
}
