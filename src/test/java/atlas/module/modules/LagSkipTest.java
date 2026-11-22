package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

public class LagSkipTest {
    private static int holdsBeforeSkip(LagSkip skip, int every) {
        int holds = 0;
        while (!skip.startSkips(every)) {
            holds++;
            skip.approachOver();
            assertTrue("never skips", holds < 100);
        }
        return holds;
    }

    @Test
    public void offNeverSkips() {
        LagSkip skip = new LagSkip(new Random(1));
        for (int i = 0; i < 50; i++) {
            assertFalse(skip.startSkips(0));
        }
    }

    @Test
    public void skipsAfterEveryOrOneMoreHolds() {
        for (long seed = 0; seed < 40; seed++) {
            LagSkip skip = new LagSkip(new Random(seed));
            int holds = holdsBeforeSkip(skip, 3);
            assertTrue("3 or 4 holds, got " + holds, holds == 3 || holds == 4);
        }
    }

    @Test
    public void aSkippedApproachStaysSkippedUntilItEnds() {
        LagSkip skip = new LagSkip(new Random(2));
        holdsBeforeSkip(skip, 2);
        assertTrue(skip.skipping());
        assertTrue("the same approach, asked again", skip.startSkips(2));
        skip.approachOver();
        assertFalse(skip.skipping());
        assertFalse("the next approach holds", skip.startSkips(2));
    }
}
