package atlas.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** KillAura2's hurt-window clock and the hit-reaction wait (2026-10-15). */
public class HitTimingTest {

    @Test
    public void ourHitOpensTenTicksOnOurOwnClock() {
        HitWindow w = new HitWindow();
        assertEquals("nothing known: now", 0, w.opensIn(100, 0.0F, 5));
        w.sent(100, true);
        assertEquals(10, w.opensIn(100, 0.0F, 5));
        assertEquals(3, w.opensIn(107, 0.0F, 5));
        // The flinch of our own hit, seen a round trip late, does not hold it longer.
        assertEquals(0, w.opensIn(110, 1.4F, 5));
        assertTrue(w.ours(110, 5));
    }

    @Test
    public void aHitIntoTheWindowChangesNothing() {
        HitWindow w = new HitWindow();
        w.sent(100, true);
        w.sent(104, false);
        assertEquals(0, w.opensIn(110, 0.0F, 5));
    }

    @Test
    public void anIgnoredHitOpensNoWindow() {
        HitWindow w = new HitWindow();
        w.sent(100, true);
        w.ignored();
        assertEquals(0, w.opensIn(103, 0.0F, 5));
        assertFalse(w.ours(103, 5));
    }

    @Test
    public void aComboHitNoFlinchAnswersIsDroppedAfterTheRoundTrip() {
        HitWindow w = new HitWindow();
        w.observe(99, 0, 5);
        w.sent(100, true);
        for (int t = 101; t <= 108; t++) {
            w.observe(t, 0, 5);
        }
        assertTrue("still waiting at 108", w.ours(108, 5));
        w.observe(109, 0, 5);
        assertFalse("no flinch by 109: never opened", w.ours(109, 5));
        assertEquals(0, w.opensIn(109, 0.0F, 5));
    }

    @Test
    public void aFlinchKeepsOurWindow() {
        HitWindow w = new HitWindow();
        w.observe(99, 0, 5);
        w.sent(100, true);
        w.observe(105, 10, 5);
        for (int t = 106; t <= 109; t++) {
            w.observe(t, 10 - (t - 105), 5);
        }
        assertTrue(w.ours(109, 5));
        assertEquals(1, w.opensIn(109, 2.0F, 5));
    }

    @Test
    public void someoneElsesWindowFollowsTheObservedEstimate() {
        HitWindow w = new HitWindow();
        assertEquals(4, w.opensIn(100, 3.2F, 5));
        assertEquals(0, w.opensIn(100, -0.5F, 5));
        // Long after our own window, the observed estimate decides again.
        w.sent(100, true);
        assertEquals(2, w.opensIn(140, 1.5F, 5));
    }

    @Test
    public void theReactionWaitFollowsThePing() {
        // 230 ms: 4 + ceil(330 / 50) = 11 ticks.
        assertEquals(11, HitFeedback.waitTicksFor(230));
        // Unknown ping is taken as 300 ms, never as none (it gave 3 ticks).
        assertEquals(12, HitFeedback.waitTicksFor(-1));
        assertTrue(HitFeedback.waitTicksFor(-1) > 3 + 230 / 50);
    }

    @Test
    public void observeTellsWhenAWatchedHitWasIgnored() {
        HitFeedback f = new HitFeedback();
        f.attacked(0, 0, 3);
        assertFalse(f.observe(1, 0));
        assertFalse(f.observe(3, 0));
        assertTrue(f.observe(4, 0));
        assertFalse("decided once", f.observe(5, 0));
    }

    @Test
    public void forcedTargetIsKept() {
        TargetPicker picker = new TargetPicker();
        Object a = "a";
        Object b = "b";
        java.util.List<TargetPicker.Candidate> list = new java.util.ArrayList<>();
        list.add(new TargetPicker.Candidate(a, 2.0, 20, 5, 0, true));
        list.add(new TargetPicker.Candidate(b, 2.5, 20, 5, 0, true));
        assertEquals(a, picker.pick(list, TargetPicker.Mode.SINGLE, TargetPicker.Sort.DISTANCE, 300, 0L));
        picker.force(b, 10L);
        assertEquals(b, picker.pick(list, TargetPicker.Mode.SINGLE, TargetPicker.Sort.DISTANCE, 300, 20L));
    }
}
