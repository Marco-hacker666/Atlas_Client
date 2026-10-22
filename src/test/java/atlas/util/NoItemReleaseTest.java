package atlas.util;

import atlas.module.modules.NoItemRelease;
import org.junit.Test;

import static org.junit.Assert.*;

/** NoItemRelease's rule, rebuilt on Slinky's method (2026-10-19). */
public class NoItemReleaseTest {

    @Test
    public void swordModeCoversOnlySwords() {
        assertTrue(NoItemRelease.covers(0, true, false));
        assertFalse(NoItemRelease.covers(0, false, false));
    }

    @Test
    public void consumableModeCoversEverythingButSwords() {
        assertFalse(NoItemRelease.covers(1, true, false));
        assertTrue(NoItemRelease.covers(1, false, false));
    }

    @Test
    public void allModeCoversBoth() {
        assertTrue(NoItemRelease.covers(2, true, false));
        assertTrue(NoItemRelease.covers(2, false, false));
    }

    @Test
    public void bowsAndRodsAreNeverCovered() {
        for (int mode = 0; mode < 3; mode++) {
            assertFalse(NoItemRelease.covers(mode, false, true));
        }
    }

    @Test
    public void theReleaseAfterACoveredUseIsDroppedOnce() {
        NoItemRelease.Gate gate = new NoItemRelease.Gate();
        gate.use(true);
        assertTrue(gate.dig(true));
        assertFalse("dropped only once", gate.dig(true));
    }

    @Test
    public void anyOtherDigDisarms() {
        NoItemRelease.Gate gate = new NoItemRelease.Gate();
        gate.use(true);
        assertFalse("starting to mine is not a release", gate.dig(false));
        assertFalse("and it disarms", gate.dig(true));
    }

    @Test
    public void anUncoveredUseDisarms() {
        NoItemRelease.Gate gate = new NoItemRelease.Gate();
        gate.use(true);
        gate.use(false);
        assertFalse(gate.dig(true));
    }

    @Test
    public void nothingIsDroppedBeforeAnyUse() {
        assertFalse(new NoItemRelease.Gate().dig(true));
    }
}
