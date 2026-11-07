package atlas.module.modules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PlacePace (2026-11-07): the books Intave PlacementAnalysis Speed and Sneak keep, rebuilt from their behaviour. A
 * bridge is driven along +z, one block under the feet each gap, and the questions are the ones the server answers.
 */
public class PlacePaceTest {

    /** GODBRIDGE's straight look: back and 45.3 to a side, so off every multiple of 90. */
    private static final float GODBRIDGE_YAW = 135.3F;
    private static final float STRAIGHT_BACK = 180.0F;

    /** count blocks placed along +z, gap ms apart, starting at t = 10 000; returns the time of the last. */
    private static long bridge(PlacePace pace, int count, long gap) {
        long t = 10000L;
        for (int i = 0; i < count; i++) {
            t += gap;
            pace.placed(t, true, true, 1, 0, 64, i);
        }
        return t;
    }

    @Test
    public void aWalkSpeedBridgeWithNoCrouchIsFlagged() {
        PlacePace pace = new PlacePace();
        long t = bridge(pace, 8, 300L);
        assertTrue("300 ms a block, no crouch: under Speed's 350", pace.wouldFlag(t + 300L, GODBRIDGE_YAW, true, true, 1, 0));
    }

    @Test
    public void aCrouchInTheLastEightSecondsLowersTheLimitTo200() {
        PlacePace pace = new PlacePace();
        long t = bridge(pace, 8, 300L);
        pace.sneaking(t - 2000L);
        assertEquals(200.0, pace.speedLimit(t + 300L, GODBRIDGE_YAW), 1.0E-9);
        assertFalse(pace.wouldFlag(t + 300L, GODBRIDGE_YAW, true, true, 1, 0));
        PlacePace fast = new PlacePace();
        long u = bridge(fast, 8, 200L);
        fast.sneaking(u);
        assertTrue("200 is under 200 plus the margin", fast.wouldFlag(u + 200L, GODBRIDGE_YAW, true, true, 1, 0));
    }

    @Test
    public void facingAMultipleOf90NeedsTheSlowerPace() {
        PlacePace pace = new PlacePace();
        long t = bridge(pace, 8, 300L);
        pace.sneaking(t);
        assertEquals(350.0, pace.speedLimit(t + 300L, STRAIGHT_BACK), 1.0E-9);
        assertTrue(pace.wouldFlag(t + 300L, STRAIGHT_BACK, true, true, 1, 0));
        assertTrue(PlacePace.facesNinety(-0.5F));
        assertTrue(PlacePace.facesNinety(-171.0F));
        assertFalse(PlacePace.facesNinety(135.3F));
    }

    @Test
    public void aRecentJumpIsJudgedAt300() {
        PlacePace pace = new PlacePace();
        long t = bridge(pace, 8, 320L);
        pace.sneaking(t);
        pace.jumped(t);
        assertEquals(300.0, pace.speedLimit(t + 320L, GODBRIDGE_YAW), 1.0E-9);
        assertFalse("320 is under 300 + margin", pace.roomForJump());
        PlacePace slow = new PlacePace();
        bridge(slow, 8, 400L);
        assertTrue(slow.roomForJump());
    }

    @Test
    public void aPlacementAgainstAnotherLayerCountsASecondMore() {
        PlacePace pace = new PlacePace();
        long t = bridge(pace, 9, 300L);
        assertTrue(pace.wouldFlag(t + 300L, GODBRIDGE_YAW, true, true, 1, 0));
        assertFalse("the +1000 lifts the average over 350", pace.wouldFlag(t + 300L, GODBRIDGE_YAW, true, false, 1, 0));
    }

    @Test
    public void notUnderThePlayerIsNotJudged() {
        PlacePace pace = new PlacePace();
        long t = bridge(pace, 8, 100L);
        assertFalse(pace.wouldFlag(t + 100L, GODBRIDGE_YAW, false, true, 1, 0));
        assertFalse("two neighbours already: not a bridge block", pace.wouldFlag(t + 100L, GODBRIDGE_YAW, true, true, 2, 0));
    }

    @Test
    public void theSneakPartNeeds24AndClearsOnABlockAway() {
        PlacePace pace = new PlacePace();
        long t = 10000L;
        for (int i = 0; i < 24; i++) {
            t += 420L;
            pace.placed(t, true, true, 1, 0, 64, i);
        }
        /* 420 passes Speed (crouch 7 s ago: limit 200 with the crouch in its 8 s), not Sneak (no crouch in 6 s, under 500). */
        pace.sneaking(t - 7000L);
        assertTrue(pace.wouldFlag(t + 420L, GODBRIDGE_YAW, true, true, 1, 0));
        pace.placed(t + 100L, false, true, 1, 5, 64, 5);
        assertFalse("a block away from the feet cleared the Sneak books", pace.wouldFlag(t + 520L, GODBRIDGE_YAW, true, true, 1, 0));
    }

    @Test
    public void aCrouchIsDueOnlyWhileBridging() {
        PlacePace pace = new PlacePace();
        assertFalse(pace.sneakDue(20000L, 3500L));
        long t = bridge(pace, 4, 300L);
        assertTrue(pace.sneakDue(t + 100L, 3500L));
        pace.sneaking(t);
        assertFalse(pace.sneakDue(t + 100L, 3500L));
        for (int i = 0; i < 12; i++) {
            pace.placed(t + 300L * (i + 1), true, true, 1, 0, 64, 10 + i);
        }
        assertFalse(pace.sneakDue(t + 3400L, 3500L));
        assertTrue(pace.sneakDue(t + 3600L, 3500L));
        assertFalse("stopped bridging", pace.sneakDue(t + 3600L + 1600L, 3500L));
    }

    @Test
    public void linesAsTheServerReadsThem() {
        List<int[]> straight = new ArrayList<int[]>();
        List<int[]> stairs = new ArrayList<int[]>();
        List<int[]> stepUp = new ArrayList<int[]>();
        for (int i = 0; i < 5; i++) {
            straight.add(new int[]{3, 64, i});
            stairs.add(new int[]{(i + 1) / 2, 64, i / 2});
            stepUp.add(new int[]{3, 64 + (i == 4 ? 1 : 0), i});
        }
        assertTrue(PlacePace.looseLine(straight));
        assertTrue(PlacePace.strictLine(straight));
        assertFalse("a diagonal staircase is not one line", PlacePace.looseLine(stairs));
        assertTrue("Speed lets a layer change pass", PlacePace.looseLine(stepUp));
        assertFalse("Sneak does not", PlacePace.strictLine(stepUp));
    }

    @Test
    public void underIsTheTopCornerWithin1point3() {
        assertTrue(PlacePace.under(0.5, 65.0, 0.5, 0, 64, 0));
        assertFalse("high in a telly jump", PlacePace.under(0.5, 66.2, 0.5, 0, 64, 0));
        assertFalse("two blocks behind", PlacePace.under(0.5, 65.0, 2.5, 0, 64, 0));
    }
}
