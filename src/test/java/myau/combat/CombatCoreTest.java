package myau.combat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CombatCoreTest {
    private static final double[] EYES = {0.0, 1.62, 0.0};
    /** A player's box (0.6 wide, 1.8 tall) three blocks ahead on +Z. */
    private static final double[] BOX = {-0.3, 0.0, 2.7, 0.3, 1.8, 3.3};

    // ------------------------------------------------------------- geometry

    @Test
    public void lookingStraightAtTheBoxHitsAndAwayMisses() {
        double[] ahead = AimGeometry.direction(0.0F, 0.0F);
        assertEquals(1.0, ahead[2], 1e-6);
        assertTrue(AimGeometry.hits(EYES, ahead, BOX, 3.0));
        assertEquals(2.7, AimGeometry.hitDistance(EYES, ahead, BOX, 3.0), 1e-6);
        assertFalse(AimGeometry.hits(EYES, ahead, BOX, 2.5));
        assertFalse(AimGeometry.hits(EYES, AimGeometry.direction(90.0F, 0.0F), BOX, 3.0));
        assertTrue(AimGeometry.hits(new double[]{0.0, 1.0, 3.0}, ahead, BOX, 0.1));
    }

    @Test
    public void rotationToAndDirectionAgree() {
        double[] point = {1.5, 0.8, 2.0};
        float[] rot = AimGeometry.rotationTo(EYES, point);
        double[] dir = AimGeometry.direction(rot[0], rot[1]);
        double len = Math.sqrt(Math.pow(1.5, 2) + Math.pow(0.8 - 1.62, 2) + Math.pow(2.0, 2));
        assertEquals(1.5 / len, dir[0], 1e-4);
        assertEquals((0.8 - 1.62) / len, dir[1], 1e-4);
        assertEquals(2.0 / len, dir[2], 1e-4);
    }

    @Test
    public void nearestPointIsWhereTheLookAlreadyIsWhenItHits() {
        double[] dir = AimGeometry.direction(0.0F, 0.0F);
        double[] p = AimGeometry.nearestToRay(EYES, dir, BOX, 6.0);
        assertEquals(0.0, p[0], 1e-3);
        assertEquals(1.62, p[1], 1e-3);
    }

    @Test
    public void nearestPointToALookBesideTheBoxIsOnItsNearEdge() {
        // Looking 20 degrees to the left (yaw +20 turns toward -X): the nearest spot is on the -X side.
        double[] dir = AimGeometry.direction(20.0F, 0.0F);
        double[] p = AimGeometry.nearestToRay(EYES, dir, BOX, 6.0);
        assertEquals(-0.3, p[0], 1e-3);
        float[] rot = AimGeometry.rotationTo(EYES, p);
        assertTrue("turn should be small, was " + rot[0], rot[0] > 0.0F && rot[0] < 20.0F);
    }

    @Test
    public void trimAndGrowKeepTheCentre() {
        double[] t = AimGeometry.trim(BOX, 0.25);
        assertEquals(-0.15, t[0], 1e-9);
        assertEquals(0.45, t[1], 1e-9);
        double[] g = AimGeometry.grow(BOX, 0.1);
        assertEquals(-0.4, g[0], 1e-9);
        assertEquals(3.4, g[5], 1e-9);
        double[] none = AimGeometry.grow(BOX, -5.0);
        assertEquals(0.0, none[0], 1e-9);
        assertEquals(0.0, none[3], 1e-9);
    }

    @Test
    public void wrapTakesTheShortWay() {
        assertEquals(20.0F, AimGeometry.wrap(170.0F, -170.0F), 1e-4F);
        assertEquals(-20.0F, AimGeometry.wrap(-170.0F, 170.0F), 1e-4F);
        assertEquals(180.0F, AimGeometry.wrap(0.0F, 180.0F), 1e-4F);
    }

    // ------------------------------------------------------------- clicks

    @Test
    public void clickPlanAveragesTheChosenCps() {
        ClickPlan plan = new ClickPlan(new Random(11));
        plan.configure(10, 10, 2);
        long now = 0;
        int clicks = 0;
        int seconds = 60;
        for (int t = 0; t < seconds * 20; t++) {
            plan.tick(now);
            while (plan.take()) {
                clicks++;
            }
            now += 50;
        }
        double cps = clicks / (double) seconds;
        assertTrue("cps " + cps, cps > 9.0 && cps < 11.0);
    }

    @Test
    public void clickPlanNeverExceedsThePerTickCap() {
        ClickPlan plan = new ClickPlan(new Random(5));
        plan.configure(20, 20, 1);
        long now = 0;
        for (int t = 0; t < 400; t++) {
            plan.tick(now);
            int here = 0;
            while (plan.take()) {
                here++;
            }
            assertTrue(here <= 1);
            now += 50;
        }
    }

    @Test
    public void clickPlanForetellsWhatTheTickWillHold() {
        ClickPlan plan = new ClickPlan(new Random(9));
        plan.configure(12, 12, 2);
        long now = 0;
        for (int t = 0; t < 200; t++) {
            plan.tick(now);
            int predicted = plan.clicksAt(1);
            while (plan.take()) {
                // used up
            }
            now += 50;
            plan.tick(now);
            assertEquals(predicted, plan.clicksAt(0));
            while (plan.take()) {
                // used up
            }
            now += 50;
        }
    }

    @Test
    public void unusedPressesEndTheCombo() {
        ClickPlan plan = new ClickPlan(new Random(2));
        plan.configure(14, 14, 2);
        plan.tick(0);
        int idleTicks = 0;
        for (long now = 50; now < 2000; now += 50) {
            plan.tick(now); // nobody takes
            idleTicks++;
        }
        assertTrue(idleTicks > 0);
        // After a gap, a fresh combo starts at once.
        plan.tick(5000);
        assertTrue(plan.ticksUntilClick() <= 1);
    }

    // ------------------------------------------------------------- targets

    private static TargetPicker.Candidate c(Object key, double dist, boolean inReach) {
        return new TargetPicker.Candidate(key, dist, 20.0, 0.0, 0, inReach);
    }

    @Test
    public void inReachBeatsCloserSortValueOutOfReach() {
        TargetPicker picker = new TargetPicker();
        Object far = "far";
        Object near = "near";
        List<TargetPicker.Candidate> list = Arrays.asList(
                new TargetPicker.Candidate(far, 4.5, 2.0, 0.0, 0, false),
                new TargetPicker.Candidate(near, 2.8, 20.0, 0.0, 0, true));
        assertSame(near, picker.pick(list, TargetPicker.Mode.SINGLE, TargetPicker.Sort.HEALTH, 0, 0));
    }

    @Test
    public void singleKeepsItsTargetUntilItIsGone() {
        TargetPicker picker = new TargetPicker();
        Object a = "a";
        Object b = "b";
        assertSame(a, picker.pick(Arrays.asList(c(a, 2.5, true), c(b, 2.9, true)),
                TargetPicker.Mode.SINGLE, TargetPicker.Sort.DISTANCE, 0, 0));
        // b comes closer: single stays on a.
        assertSame(a, picker.pick(Arrays.asList(c(a, 2.9, true), c(b, 1.5, true)),
                TargetPicker.Mode.SINGLE, TargetPicker.Sort.DISTANCE, 0, 50));
        // a leaves reach while b is in it: switch.
        assertSame(b, picker.pick(Arrays.asList(c(a, 4.0, false), c(b, 1.5, true)),
                TargetPicker.Mode.SINGLE, TargetPicker.Sort.DISTANCE, 0, 100));
        // nobody left.
        assertNull(picker.pick(new ArrayList<TargetPicker.Candidate>(),
                TargetPicker.Mode.SINGLE, TargetPicker.Sort.DISTANCE, 0, 150));
    }

    @Test
    public void switchMovesOnOnlyAfterTheDelay() {
        TargetPicker picker = new TargetPicker();
        Object a = "a";
        Object b = "b";
        List<TargetPicker.Candidate> both = Arrays.asList(c(a, 2.0, true), c(b, 2.5, true));
        assertSame(a, picker.pick(both, TargetPicker.Mode.SWITCH, TargetPicker.Sort.DISTANCE, 300, 0));
        assertSame(a, picker.pick(both, TargetPicker.Mode.SWITCH, TargetPicker.Sort.DISTANCE, 300, 200));
        assertSame(b, picker.pick(both, TargetPicker.Mode.SWITCH, TargetPicker.Sort.DISTANCE, 300, 350));
        assertSame(b, picker.pick(both, TargetPicker.Mode.SWITCH, TargetPicker.Sort.DISTANCE, 300, 500));
        assertSame(a, picker.pick(both, TargetPicker.Mode.SWITCH, TargetPicker.Sort.DISTANCE, 300, 700));
    }

    // ------------------------------------------------------------- feedback

    @Test
    public void threeIgnoredHitsPauseAttacks() {
        HitFeedback f = new HitFeedback();
        int tick = 0;
        for (int i = 0; i < HitFeedback.IGNORED_TO_PAUSE; i++) {
            f.attacked(tick, 0, 5);
            for (int t = 0; t < 6; t++) {
                f.observe(++tick, 0);
            }
            if (i < HitFeedback.IGNORED_TO_PAUSE - 1) {
                assertFalse(f.paused(tick));
            }
        }
        assertTrue(f.paused(tick));
        assertTrue(f.paused(tick + HitFeedback.PAUSE_TICKS - 1));
        assertFalse(f.paused(tick + HitFeedback.PAUSE_TICKS));
    }

    @Test
    public void aReactionClearsTheCount() {
        HitFeedback f = new HitFeedback();
        int tick = 0;
        for (int round = 0; round < 5; round++) {
            f.attacked(tick, 0, 5);
            f.observe(++tick, 0);
            f.observe(++tick, round == 1 ? 10 : 0);
            for (int t = 0; t < 6; t++) {
                f.observe(++tick, 0);
            }
            if (round == 1) {
                assertFalse(f.paused(tick));
            }
        }
        // Rounds 0 (ignored), 1 (hurt: reset), 2, 3, 4 (three ignored): paused.
        assertTrue(f.paused(tick));
    }

    @Test
    public void hitsOnAHurtTargetAreNotWatched() {
        HitFeedback f = new HitFeedback();
        int tick = 0;
        for (int i = 0; i < 10; i++) {
            f.attacked(tick, 7, 5);
            for (int t = 0; t < 7; t++) {
                f.observe(++tick, 0);
            }
        }
        assertFalse(f.paused(tick));
    }
}
