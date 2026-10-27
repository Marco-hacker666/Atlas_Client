package atlas.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** KnockbackDelay's delay line and ramp (2026-10-27): in order, no bursts, latency-shaped. */
public class DelayLineTest {

    @Test
    public void dueInOrderAndStopsAtTheFirstNotDue() {
        DelayLine<Integer> line = new DelayLine<Integer>();
        line.add(1, 0);
        line.add(2, 30);
        line.add(3, 10);
        assertEquals(Arrays.asList(1), line.due(100, 100));
        // 3 is due by its own arrival but waits behind 2: never out of order.
        assertEquals(Arrays.asList(), line.due(120, 100));
        assertEquals(Arrays.asList(2, 3), line.due(130, 100));
        assertTrue(line.isEmpty());
    }

    @Test
    public void drainTakesEverything() {
        DelayLine<Integer> line = new DelayLine<Integer>();
        line.add(1, 0);
        line.add(2, 5);
        assertEquals(0L, new DelayLine<Integer>().oldestArrival());
        assertEquals(0L, line.oldestArrival());
        assertEquals(Arrays.asList(1, 2), line.drain());
        assertEquals(0, line.size());
    }

    @Test
    public void theRampNeverStepsMoreThanItsRate() {
        LatencyRamp ramp = new LatencyRamp(7L);
        double last = 0.0;
        for (int t = 0; t < 200; t++) {
            double target = t < 100 ? 120.0 : 0.0;
            double now = ramp.tick(target, 10.0, 25.0);
            assertTrue("tick " + t + ": " + last + " -> " + now, Math.abs(now - last) <= 10.0 + 1e-9);
            assertTrue(now >= 0.0 && now <= 145.0 + 1e-9);
            last = now;
        }
        assertEquals(0.0, last, 1e-9);
    }

    @Test
    public void theWanderStaysWithinTheJitterAndMoves() {
        LatencyRamp ramp = new LatencyRamp(11L);
        double min = Double.MAX_VALUE;
        double max = 0.0;
        for (int t = 0; t < 2000; t++) {
            double now = ramp.tick(100.0, 50.0, 20.0);
            if (t > 50) {
                min = Math.min(min, now);
                max = Math.max(max, now);
            }
        }
        assertTrue("min " + min, min >= 80.0 - 1e-9);
        assertTrue("max " + max, max <= 120.0 + 1e-9);
        assertTrue("it wanders: " + min + ".." + max, max - min > 5.0);
    }

    /**
     * A connection at 20 packets a tick, the delay ramping from 100 ms to
     * nothing at 10 ms a tick: no tick lets out more than the arrivals of a
     * tick and a quarter (a stop is a slow catch-up, never a burst), and the
     * order is kept.
     */
    @Test
    public void rampingDownLetsTheQueueOutWithoutABurst() {
        DelayLine<Integer> line = new DelayLine<Integer>();
        LatencyRamp ramp = new LatencyRamp(3L);
        int next = 0;
        int expected = 0;
        int most = 0;
        List<Integer> out = new ArrayList<Integer>();
        for (int t = 0; t < 120; t++) {
            long now = t * 50L;
            for (int i = 0; i < 20; i++) {
                line.add(next++, now - 50L + i * 2L);
            }
            double delay = ramp.tick(t < 40 ? 100.0 : 0.0, 10.0, 0.0);
            List<Integer> released = line.due(now, (long) delay);
            if (t > 45) {
                most = Math.max(most, released.size());
            }
            out.addAll(released);
        }
        for (int item : out) {
            assertEquals(expected++, item);
        }
        assertTrue("most in one tick " + most, most <= 25);
    }
}
