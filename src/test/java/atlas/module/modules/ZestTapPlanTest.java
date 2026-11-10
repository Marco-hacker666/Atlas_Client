package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZestTapPlanTest {

    private static final class Run {
        final ZestTapPlan plan;
        long now = 100000L;

        Run(long seed) {
            this.plan = new ZestTapPlan(new Random(seed));
        }

        ZestTapPlan.Keys tick(boolean landed, boolean peak) {
            ZestTapPlan.Keys keys = this.plan.tick(this.now, landed, false, true, peak, ZestTapPlan.START_ANY, true, false, 450L);
            this.now += 50L;
            return keys;
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                tick(false, false);
            }
        }
    }

    @Test
    public void startsFromAFirstHitAHitSelectOrATrade() {
        long hit = 10000L;
        assertTrue(ZestTapPlan.starts(ZestTapPlan.START_FIRST_HIT, hit, hit - 2000L));
        assertFalse(ZestTapPlan.starts(ZestTapPlan.START_FIRST_HIT, hit, hit - 300L));
        assertTrue(ZestTapPlan.starts(ZestTapPlan.START_HIT_SELECT, hit, hit - 300L));
        assertFalse(ZestTapPlan.starts(ZestTapPlan.START_HIT_SELECT, hit, hit - 50L));
        assertTrue(ZestTapPlan.starts(ZestTapPlan.START_TRADE, hit, hit - 50L));
        assertTrue(ZestTapPlan.starts(ZestTapPlan.START_TRADE, hit, hit + 100L));
        assertFalse(ZestTapPlan.starts(ZestTapPlan.START_TRADE, hit, hit - 2000L));
        assertTrue(ZestTapPlan.starts(ZestTapPlan.START_ANY, hit, hit - 300L));
        assertFalse("between a select and a first hit", ZestTapPlan.starts(ZestTapPlan.START_ANY, hit, hit - 800L));
    }

    /** 2026-11-10 (owner: "let go longer, as in the video"): W is let go from the peak until the third hit lands. */
    @Test
    public void wIsHeldForTwoHitsAndLetGoFromThePeakUntilTheThird() {
        Run run = new Run(3);
        assertFalse(run.tick(true, false).release);
        assertTrue(run.plan.inCombo());
        for (int i = 0; i < 9; i++) {
            assertFalse("W held before the second hit", run.tick(false, false).release);
        }
        assertFalse("not on the second hit itself", run.tick(true, false).release);
        assertFalse("the knockback still rising", run.tick(false, false).release);
        assertTrue("let go at the peak", run.tick(false, true).release);
        for (int i = 0; i < 4; i++) {
            assertTrue("still let go before the third hit", run.tick(false, false).release);
        }
        assertFalse("W back as the third lands", run.tick(true, false).release);
        assertEquals(3, run.plan.hits());
        assertFalse(run.tick(false, false).release);
    }

    @Test
    public void aThirdHitThatNeverComesGivesWBackWithinTheLimit() {
        Run run = new Run(4);
        run.tick(true, false);
        run.ticks(9);
        run.tick(true, false);
        run.tick(false, true);
        int released = 1;
        while (run.tick(false, false).release) {
            released++;
            assertTrue("bounded: " + released, released < 20);
        }
        long ms = released * 50L;
        assertTrue("about 450 ms: " + ms, ms >= 350L && ms <= 600L);
    }

    /** 2026-11-10 (owner's video): at the hit, W goes when the target opens and overlaps the third hit. */
    @Test
    public void atTheHitWGoesWhenTheTargetOpensAndOverlapsTheThird() {
        ZestTapPlan plan = new ZestTapPlan(new Random(8));
        plan.releaseAtHit(true);
        long now = 100000L;
        boolean[] released = new boolean[30];
        for (int t = 0; t < 30; t++) {
            boolean landed = t == 0 || t == 10 || t == 20;
            plan.targetOpen(t >= 18 && t < 20);
            released[t] = plan.tick(now, landed, false, true, t == 13, ZestTapPlan.START_ANY, true, false, 150L).release;
            now += 50L;
        }
        for (int t = 0; t < 18; t++) {
            assertFalse("not before the opening (tick " + t + ")", released[t]);
        }
        assertTrue("off at the opening", released[18]);
        assertTrue("still off as the third hit lands", released[20]);
        int length = 0;
        for (boolean r : released) {
            if (r) {
                length++;
            }
        }
        assertTrue("a split second: " + length, length >= 2 && length <= 4);
    }

    @Test
    public void withNoPeakSeenTheTapGoesAnyway() {
        Run run = new Run(5);
        run.tick(true, false);
        run.ticks(9);
        run.tick(true, false);
        boolean tapped = false;
        for (int i = 0; i < 10; i++) {
            tapped |= run.tick(false, false).release;
        }
        assertTrue(tapped);
    }

    @Test
    public void strafesThroughEachSequenceAndSwapsSides() {
        Run run = new Run(7);
        int first = run.tick(true, false).strafe;
        assertTrue(first != 0);
        assertEquals(first, run.tick(false, false).strafe);
        run.ticks(8);
        run.tick(true, false);
        run.ticks(2);
        run.tick(false, true);
        run.ticks(3);
        run.tick(true, false);
        run.ticks(9);
        int second = run.tick(true, false).strafe;
        assertEquals("the next sequence goes the other way", -first, second);
    }

    /** Random strafe (owner: "a little at a time"): short taps, never held, the side kept now and then. */
    @Test
    public void randomStrafeIsShortTapsWithGapsAndVaries() {
        java.util.Set<Integer> totals = new java.util.HashSet<Integer>();
        int kept = 0;
        int swapped = 0;
        int lastSide = 0;
        int allTaps = 0;
        ZestTapPlan plan = new ZestTapPlan(new Random(42));
        long now = 100000L;
        for (int sequence = 0; sequence < 60; sequence++) {
            int first = -1;
            int total = 0;
            int run = 0;
            int taps = 0;
            int side = 0;
            for (int t = 0; t < 30; t++) {
                boolean landed = t == 0 || t == 10 || t == 20;
                ZestTapPlan.Keys keys = plan.tick(now, landed, false, true, t == 13, ZestTapPlan.START_ANY,
                        true, true, 450L);
                now += 50L;
                if (keys.strafe != 0) {
                    if (first < 0) {
                        first = t;
                    }
                    if (run == 0) {
                        taps++;
                    }
                    run++;
                    total++;
                    side = keys.strafe;
                    assertTrue("a tap, not a hold: " + run, run <= ZestTapPlan.TAP_MAX_TICKS);
                } else {
                    run = 0;
                }
            }
            assertTrue("starts within 3 ticks: " + first, first >= 0 && first <= 3);
            assertTrue("a little: " + total, total >= 1 && total <= ZestTapPlan.DEFAULT_TAPS * ZestTapPlan.TAP_MAX_TICKS);
            totals.add(total);
            allTaps += taps;
            if (lastSide != 0) {
                if (side == lastSide) {
                    kept++;
                } else {
                    swapped++;
                }
            }
            lastSide = side;
        }
        assertTrue("the amount varies", totals.size() > 2);
        assertTrue("more than one tap a sequence on average: " + allTaps, allTaps > 60 * 2);
        assertTrue("sometimes the same side: " + kept, kept > 5);
        assertTrue("mostly the other side: " + swapped, swapped > kept);
    }

    @Test
    public void moreTapsSetMeansMoreStrafe() {
        int few = strafeTicks(1);
        int many = strafeTicks(8);
        assertTrue("8 taps strafe more than 1: " + many + " vs " + few, many > few * 2);
    }

    @Test
    public void theStrafeShapeFollowsTheSettings() {
        ZestTapPlan plan = new ZestTapPlan(new Random(5));
        plan.strafeShape(3, 4, 4, 1, 1, 0, 100);
        long now = 100000L;
        int lastSide = 0;
        for (int sequence = 0; sequence < 20; sequence++) {
            int first = -1;
            int run = 0;
            int longest = 0;
            int side = 0;
            for (int t = 0; t < 30; t++) {
                boolean landed = t == 0 || t == 10 || t == 20;
                ZestTapPlan.Keys keys = plan.tick(now, landed, false, true, t == 13, ZestTapPlan.START_ANY, true, true, 450L);
                now += 50L;
                if (keys.strafe != 0) {
                    if (first < 0) {
                        first = t;
                    }
                    run++;
                    longest = Math.max(longest, run);
                    side = keys.strafe;
                } else {
                    run = 0;
                }
            }
            assertEquals("no delay set", 0, first);
            assertTrue("taps of 4 ticks: " + longest, longest == 4);
            if (lastSide != 0) {
                assertEquals("swap chance 100", -lastSide, side);
            }
            lastSide = side;
        }
    }

    @Test
    public void aMinAboveItsMaxIsSwappedNotBroken() {
        ZestTapPlan plan = new ZestTapPlan(new Random(6));
        plan.strafeShape(4, 3, 1, 5, 2, 2, 70);
        long now = 100000L;
        int strafed = 0;
        for (int t = 0; t < 60; t++) {
            boolean landed = t % 10 == 0;
            if (plan.tick(now, landed, false, true, t % 10 == 3, ZestTapPlan.START_ANY, true, true, 450L).strafe != 0) {
                strafed++;
            }
            now += 50L;
        }
        assertTrue("still strafes: " + strafed, strafed > 0);
    }

    private static int strafeTicks(int taps) {
        ZestTapPlan plan = new ZestTapPlan(new Random(11));
        plan.strafeTaps(taps);
        long now = 100000L;
        int total = 0;
        for (int sequence = 0; sequence < 30; sequence++) {
            for (int t = 0; t < 30; t++) {
                boolean landed = t == 0 || t == 10 || t == 20;
                if (plan.tick(now, landed, false, true, t == 13, ZestTapPlan.START_ANY, true, true, 450L).strafe != 0) {
                    total++;
                }
                now += 50L;
            }
        }
        return total;
    }

    @Test
    public void anOpponentThatStopsWalkingInEndsTheCombo() {
        ZestTapPlan plan = new ZestTapPlan(new Random(1));
        long now = 100000L;
        plan.tick(now, true, false, true, false, ZestTapPlan.START_ANY, true, false, 450L);
        assertTrue(plan.inCombo());
        ZestTapPlan.Keys keys = plan.tick(now + 50L, false, false, false, false, ZestTapPlan.START_ANY, true, false, 450L);
        assertFalse(plan.inCombo());
        assertFalse(keys.release);
        assertEquals(0, keys.strafe);
    }

    @Test
    public void aBadOpponentNeverStartsOne() {
        ZestTapPlan plan = new ZestTapPlan(new Random(1));
        ZestTapPlan.Keys keys = plan.tick(100000L, true, false, false, false, ZestTapPlan.START_ANY, true, false, 450L);
        assertFalse(plan.inCombo());
        assertEquals(0, keys.strafe);
    }

    @Test
    public void aSecondWithNoHitEndsTheCombo() {
        Run run = new Run(9);
        run.tick(true, false);
        run.ticks(21);
        assertFalse(run.plan.inCombo());
    }

    @Test
    public void theFirstHitAfterAHitTakenAtTheWrongTimeDoesNotStartWithFirstHitOnly() {
        ZestTapPlan plan = new ZestTapPlan(new Random(1));
        long now = 100000L;
        plan.tick(now, false, true, true, false, ZestTapPlan.START_FIRST_HIT, true, false, 450L);
        plan.tick(now + 300L, true, false, true, false, ZestTapPlan.START_FIRST_HIT, true, false, 450L);
        assertFalse(plan.inCombo());
    }
}
