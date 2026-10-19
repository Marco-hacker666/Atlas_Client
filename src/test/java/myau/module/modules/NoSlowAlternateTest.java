package myau.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * GrimNoSlowState never allows two full-speed slowed ticks in a row (Grim's
 * NoSlow flags on the second) unless a slot change came between, and keeps
 * that across uses.
 */
public class NoSlowAlternateTest {

    @Test
    public void noTwoFastTicksInARow() {
        NoSlow.GrimNoSlowState state = new NoSlow.GrimNoSlowState();
        boolean lastFast = false;
        for (int tick = 1; tick <= 200; tick++) {
            boolean fast = state.slowedTick();
            assertFalse("two fast ticks in a row at " + tick, fast && lastFast);
            lastFast = fast;
        }
    }

    @Test
    public void alternatesStartingFast() {
        NoSlow.GrimNoSlowState state = new NoSlow.GrimNoSlowState();
        assertTrue(state.slowedTick());
        assertFalse(state.slowedTick());
        assertTrue(state.slowedTick());
    }

    @Test
    public void aUseEndingFastMakesTheNextStartSlowed() {
        NoSlow.GrimNoSlowState state = new NoSlow.GrimNoSlowState();
        assertTrue(state.slowedTick());
        /* the use ends here; Grim still remembers the miss */
        assertFalse(state.slowedTick());
    }

    @Test
    public void aSlotChangeLetsTheNextTickGoFast() {
        NoSlow.GrimNoSlowState state = new NoSlow.GrimNoSlowState();
        for (int round = 0; round < 50; round++) {
            assertTrue(state.slowedTick());
            state.slotChanged = true;
        }
    }

    @Test
    public void theModeNamesAreListed() {
        NoSlow noSlow = new NoSlow();
        assertTrue(noSlow.swordMode.getValuePrompt().contains(NoSlow.GRIM_ALTERNATE));
        assertTrue(noSlow.foodMode.getValuePrompt().contains(NoSlow.GRIM_ALTERNATE));
        assertTrue(noSlow.bowMode.getValuePrompt().contains(NoSlow.GRIM_ALTERNATE));
    }
}
