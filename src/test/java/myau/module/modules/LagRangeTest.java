package myau.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** LagRange's let-out (2026-10-18). */
public class LagRangeTest {

    @Test
    public void holdingStartsAtTheFullDelayAtOnce() {
        assertEquals(3, LagRange.nextDelay(0, 3, true));
        assertEquals(3, LagRange.nextDelay(1, 3, true));
    }

    @Test
    public void gradualLetOutDropsOneTickATick() {
        assertEquals(2, LagRange.nextDelay(3, 0, true));
        assertEquals(1, LagRange.nextDelay(2, 0, true));
        assertEquals(0, LagRange.nextDelay(1, 0, true));
    }

    @Test
    public void atOnceLetOutDropsStraightToWhatIsWanted() {
        assertEquals(0, LagRange.nextDelay(4, 0, false));
    }

    @Test
    public void theDelaySettingIsRoundedToTicksAndNeverZero() {
        assertEquals(3, LagRange.ticksFor(150));
        assertEquals(1, LagRange.ticksFor(0));
        assertEquals(4, LagRange.ticksFor(180));
    }
}
