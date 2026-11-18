package atlas.management;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AlertLimiterTest {
    @Test
    public void theFirstOfACauseSpeaksAtOnce() {
        AlertLimiter limiter = new AlertLimiter(3000L);
        assertEquals(0, limiter.pass("Speed:move", 0L));
    }

    @Test
    public void insideTheStretchItIsHeldAndCounted() {
        AlertLimiter limiter = new AlertLimiter(3000L);
        limiter.pass("Speed:move", 1000L);
        assertEquals(-1, limiter.pass("Speed:move", 1050L));
        assertEquals(-1, limiter.pass("Speed:move", 3999L));
        assertEquals("two were held back", 2, limiter.pass("Speed:move", 4000L));

        assertEquals(-1, limiter.pass("Speed:move", 4050L));
        assertEquals(1, limiter.pass("Speed:move", 7000L));
        assertEquals(0, limiter.pass("Speed:move", 10000L));
    }

    @Test
    public void eachCauseHasItsOwnStretch() {
        AlertLimiter limiter = new AlertLimiter(3000L);
        assertEquals(0, limiter.pass("Speed:move", 1000L));
        assertEquals(0, limiter.pass("plain", 1001L));
        assertEquals(-1, limiter.pass("Speed:move", 1002L));
        assertEquals(-1, limiter.pass("plain", 1003L));
    }

    @Test
    public void clearingLetsEveryCauseSpeakAgain() {
        AlertLimiter limiter = new AlertLimiter(3000L);
        limiter.pass("plain", 1000L);
        limiter.pass("plain", 1001L);
        limiter.clear();
        assertEquals(0, limiter.pass("plain", 1002L));
    }

    @Test
    public void aClockThatStartsAtZeroStillSpeaksFirst() {
        AlertLimiter limiter = new AlertLimiter(3000L);
        assertEquals(0, limiter.pass("plain", 0L));
        assertEquals(-1, limiter.pass("plain", 2999L));
        assertEquals(1, limiter.pass("plain", 3000L));
    }
}
