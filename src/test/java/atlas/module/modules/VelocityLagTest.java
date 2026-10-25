package atlas.module.modules;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Velocity's Lag mode queue (2026-10-24). */
public class VelocityLagTest {

    @Test
    public void theHoldIsCutToThePingBudget() {
        assertEquals(4, VelocityLag.holdTicks(4, 50, 250));
        assertEquals(2, VelocityLag.holdTicks(4, 140, 250));
        assertEquals(0, VelocityLag.holdTicks(4, 220, 250));
        assertEquals(4, VelocityLag.holdTicks(4, -1, 250));
        assertEquals(4, VelocityLag.holdTicks(4, 400, 0));
    }

    @Test
    public void everythingComesOutInOrderWhenDue() {
        VelocityLag<String> lag = new VelocityLag<String>();
        assertTrue(lag.start("kb", 10, 4, 0));
        assertTrue(lag.add("t1"));
        assertTrue(lag.add("t2"));
        assertEquals(Collections.emptyList(), lag.due(13, 150, 500));
        assertEquals(Arrays.asList("kb", "t1", "t2"), lag.due(14, 200, 500));
        assertFalse(lag.holding());
        assertFalse(lag.add("t3")); // not holding: not taken
    }

    @Test
    public void aCooldownFollowsEachRelease() {
        VelocityLag<String> lag = new VelocityLag<String>();
        lag.start("kb", 0, 2, 0);
        lag.due(2, 100, 500);
        assertFalse(lag.start("kb2", 3, 2, 400));
        assertTrue(lag.start("kb3", 13, 2, 650));
    }

    @Test
    public void noTicksOrAlreadyHoldingStartsNothing() {
        VelocityLag<String> lag = new VelocityLag<String>();
        assertFalse(lag.start("kb", 0, 0, 0));
        assertTrue(lag.start("kb", 0, 3, 0));
        assertFalse(lag.start("kb2", 1, 3, 0));
        assertEquals(1, lag.size());
    }

    @Test
    public void drainingLetsEverythingGoAtOnce() {
        VelocityLag<String> lag = new VelocityLag<String>();
        lag.start("kb", 0, 10, 0);
        lag.add("t1");
        assertEquals(Arrays.asList("kb", "t1"), lag.drain(50, 500));
        assertEquals(0, lag.size());
        assertFalse(lag.start("kb2", 1, 3, 100)); // cooling down after the drain too
    }
}
