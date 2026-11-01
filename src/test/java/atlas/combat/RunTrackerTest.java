package atlas.combat;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Velocity from server updates that come every second tick (2026-11-01). */
public class RunTrackerTest {

    @Test
    public void updatesEverySecondTickGiveASteadySpeed() {
        RunTracker tracker = new RunTracker();
        double v = 0.28;
        for (int t = 0; t <= 20; t++) {
            /* The position changes only on even ticks, by two ticks' worth. */
            double x = (t / 2) * 2 * v;
            tracker.observe(t, 1, x, 0.0);
            if (t >= 4) {
                assertNotNull("tick " + t, tracker.velocity(1));
                assertEquals("tick " + t, v, tracker.velocity(1)[0], 1.0E-9);
            }
        }
    }

    @Test
    public void standingAndTeleportsDropTheVelocity() {
        RunTracker tracker = new RunTracker();
        tracker.observe(0, 1, 0.0, 0.0);
        tracker.observe(1, 1, 0.0, 0.0);
        tracker.observe(2, 1, 0.5, 0.0);
        assertNotNull(tracker.velocity(1));
        for (int t = 3; t <= 6; t++) {
            tracker.observe(t, 1, 0.5, 0.0);
        }
        assertNull("no update for 4 ticks: standing", tracker.velocity(1));
        tracker.observe(7, 1, 10.0, 0.0);
        assertNull("3 blocks a tick: a teleport", tracker.velocity(1));
    }

    @Test
    public void aGapInSightStartsOver() {
        RunTracker tracker = new RunTracker();
        tracker.observe(0, 1, 0.0, 0.0);
        tracker.observe(1, 1, 0.0, 0.0);
        tracker.observe(2, 1, 0.6, 0.0);
        assertNotNull(tracker.velocity(1));
        tracker.observe(10, 1, 3.0, 0.0);
        assertNull(tracker.velocity(1));
        tracker.retain(Collections.<Integer>emptySet());
        tracker.observe(11, 1, 3.3, 0.0);
        assertNull(tracker.velocity(1));
    }
}
