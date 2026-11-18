package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

public class NoSlowAlternateTest {
    @Test
    public void noTwoFreeTicksInARow() {
        boolean lastFree = false;
        for (int tick = 1; tick <= 200; tick++) {
            boolean free = !NoSlow.alternateSlowed(tick);
            assertFalse("two free ticks in a row at " + tick, free && lastFree);
            lastFree = free;
        }
    }

    @Test
    public void theFirstTickIsFreeAndTheSecondSlowed() {
        assertFalse(NoSlow.alternateSlowed(1));
        assertTrue(NoSlow.alternateSlowed(2));
    }

    @Test
    public void theModeNameIsInEveryList() {
        NoSlow noSlow = new NoSlow();
        assertTrue(noSlow.swordMode.getValuePrompt().contains(NoSlow.GRIM_ALTERNATE));
        assertTrue(noSlow.foodMode.getValuePrompt().contains(NoSlow.GRIM_ALTERNATE));
        assertTrue(noSlow.bowMode.getValuePrompt().contains(NoSlow.GRIM_ALTERNATE));
    }
}
