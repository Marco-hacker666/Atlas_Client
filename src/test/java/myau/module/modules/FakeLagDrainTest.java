package myau.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** FakeLag LIQUID lets the queue out ahead of melee distance (2026-10-15). */
public class FakeLagDrainTest {

    @Test
    public void runningAtEachOtherDrainsEarly() {
        // 1.5 blocks from melee, closing 0.5 a tick: 3 ticks left; 4 positions at net 1 a tick need 5.
        assertTrue(FakeLag.mustDrain(4.85, 3.35, 0.5, 4, 2));
    }

    @Test
    public void farAwayKeepsHolding() {
        // 3.65 blocks from melee at 0.5 a tick: 7.3 ticks left; 4 positions need 5.
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
        // 6 positions, 4 ticks left: at catch-up 2 they need 7 ticks, at 4 only 3.
        assertTrue(FakeLag.mustDrain(5.35, 3.35, 0.5, 6, 2));
        assertFalse(FakeLag.mustDrain(5.35, 3.35, 0.5, 6, 4));
    }
}
