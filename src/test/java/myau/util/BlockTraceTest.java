package myau.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** The opponent's block state around a hit, for HIT-DMG (2026-10-16). */
public class BlockTraceTest {

    @Test
    public void blockNearTheFlinchCounts() {
        BlockTrace trace = new BlockTrace();
        for (int i = 0; i < 10; i++) {
            trace.record(1, 1000 + i * 50, i == 4);
        }
        assertEquals("yes", trace.atHit(1, 1250, 100));
        assertEquals("no", trace.atHit(1, 1000, 100));
        assertEquals("-", trace.atHit(2, 1250, 100));
        assertArrayEquals(new int[]{1, 10}, trace.count(1, 1000, 1450));
    }

    @Test
    public void oldTicksAreDropped() {
        BlockTrace trace = new BlockTrace();
        trace.record(1, 0, true);
        trace.record(1, 5000, false);
        assertArrayEquals(new int[]{0, 1}, trace.count(1, 0, 6000));
    }
}
