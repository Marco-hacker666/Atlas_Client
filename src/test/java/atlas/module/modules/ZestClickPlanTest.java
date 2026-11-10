package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZestClickPlanTest {

    @Test
    public void noClicksWithNoComboOrNoButton() {
        ZestClickPlan plan = new ZestClickPlan(new Random(1));
        long now = 100000L;
        for (int i = 0; i < 20; i++, now += 50L) {
            assertEquals(0, plan.tick(now, false, true, true));
            assertEquals(0, plan.tick(now, true, false, true));
        }
        assertFalse(ZestClickPlan.owns(false, true));
        assertFalse(ZestClickPlan.owns(true, false));
        assertTrue(ZestClickPlan.owns(true, true));
    }

    @Test
    public void holdsWhileTheTargetCannotBeHurtThenBurstsTwoOrThree() {
        for (long seed = 0; seed < 30; seed++) {
            ZestClickPlan plan = new ZestClickPlan(new Random(seed));
            long now = 100000L;
            for (int i = 0; i < 8; i++, now += 50L) {
                assertEquals("held while the target is immune", 0, plan.tick(now, true, true, false));
            }
            int clicks = 0;
            int ticks = 0;
            for (int i = 0; i < 5; i++, now += 50L) {
                int c = plan.tick(now, true, true, true);
                assertTrue("one a tick at most", c <= 1);
                clicks += c;
                ticks++;
            }
            assertTrue("a burst of 2-3: " + clicks, clicks >= ZestClickPlan.BURST_MIN && clicks <= ZestClickPlan.BURST_MAX);
            assertEquals("the first one at once", 1, new ZestClickPlan(new Random(seed)).tick(now, true, true, true));
        }
    }

    @Test
    public void aBurstThatDidNotLandIsFollowedByAnother() {
        ZestClickPlan plan = new ZestClickPlan(new Random(4));
        long now = 100000L;
        int clicks = 0;
        for (int i = 0; i < 20; i++, now += 50L) {
            clicks += plan.tick(now, true, true, true);
        }
        assertTrue("more than one burst in a second of open target: " + clicks, clicks > ZestClickPlan.BURST_MAX);
        assertTrue("but not a click every tick: " + clicks, clicks < 15);
    }

    @Test
    public void aLandedHitHoldsAgainAndTheNextOpeningBurstsAgain() {
        ZestClickPlan plan = new ZestClickPlan(new Random(9));
        long now = 100000L;
        int first = 0;
        for (int i = 0; i < 3; i++, now += 50L) {
            first += plan.tick(now, true, true, true);
        }
        assertTrue(first >= 1);
        for (int i = 0; i < 6; i++, now += 50L) {
            assertEquals(0, plan.tick(now, true, true, false));
        }
        assertEquals("the next opening clicks at once", 1, plan.tick(now, true, true, true));
    }
}
