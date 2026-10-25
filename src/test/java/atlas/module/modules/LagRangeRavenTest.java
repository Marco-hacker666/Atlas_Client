package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** LagRange's RAVEN mode, Raven bS's start and flush rules (2026-10-23). */
public class LagRangeRavenTest {
    private static final double RANGE_SQ = 36.0;

    private static LagRangeRaven.Step tick(LagRangeRaven r, double dist, int selfHurt, int targetHurt) {
        return r.step(null, 7, dist * dist, RANGE_SQ, selfHurt, targetHurt, true, true, true, false, true, true);
    }

    @Test
    public void startsWhileClosingFromBeyondThree() {
        LagRangeRaven r = new LagRangeRaven();
        assertEquals(LagRangeRaven.Step.NOTHING, tick(r, 5.0, 0, 0)); // first sight: no closing yet
        assertEquals(LagRangeRaven.Step.START, tick(r, 4.6, 0, 0));
        assertTrue(r.lagging);
        assertEquals(LagRangeRaven.Step.HOLD, tick(r, 4.2, 0, 0));
    }

    @Test
    public void doesNotStartInsideThreeWithoutAHit() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 2.9, 0, 0);
        assertEquals(LagRangeRaven.Step.NOTHING, tick(r, 2.5, 0, 0));
        assertFalse(r.lagging);
    }

    @Test
    public void aLandedHitStartsAndKeepsHoldingInsideThree() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 2.8, 0, 0);
        assertEquals(LagRangeRaven.Step.START, tick(r, 2.8, 0, 10)); // our hit landed
        assertEquals(LagRangeRaven.Step.HOLD, tick(r, 2.9, 0, 9)); // gap not closing, hit-hold keeps it
    }

    @Test
    public void flushesWhenTheGapStopsClosing() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 5.0, 0, 0);
        tick(r, 4.6, 0, 0);
        assertEquals(LagRangeRaven.Step.FLUSH, tick(r, 4.7, 0, 0));
        assertEquals("not-closing", r.reason);
    }

    @Test
    public void flushesWhenHit() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 5.0, 0, 0);
        tick(r, 4.6, 0, 0);
        assertEquals(LagRangeRaven.Step.FLUSH, tick(r, 4.2, 10, 0));
        assertEquals("hurt", r.reason);
    }

    @Test
    public void flushesOutOfRangeAndOnTargetChange() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 5.0, 0, 0);
        tick(r, 4.6, 0, 0);
        assertEquals(LagRangeRaven.Step.FLUSH, tick(r, 4.4 + 2.0, 0, 0));
        assertEquals("out-of-range", r.reason);

        LagRangeRaven s = new LagRangeRaven();
        tick(s, 5.0, 0, 0);
        tick(s, 4.6, 0, 0);
        assertEquals(LagRangeRaven.Step.FLUSH,
                s.step(null, 8, 16.0, RANGE_SQ, 0, 0, true, true, true, false, true, true));
        assertEquals("target", s.reason);
    }

    @Test
    public void flushesWhenSprintStartsAgain() {
        LagRangeRaven r = new LagRangeRaven();
        r.step(null, 7, 25.0, RANGE_SQ, 0, 0, true, true, false, false, true, true);
        assertEquals(LagRangeRaven.Step.START, r.step(null, 7, 21.0, RANGE_SQ, 0, 0, true, true, false, false, true, true));
        assertEquals(LagRangeRaven.Step.FLUSH, r.step(null, 7, 18.0, RANGE_SQ, 0, 0, true, true, true, false, true, true));
        assertEquals("sprint", r.reason);
    }

    @Test
    public void anythingRulingItOutFlushesWithThatReason() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 5.0, 0, 0);
        tick(r, 4.6, 0, 0);
        assertEquals(LagRangeRaven.Step.FLUSH,
                r.step("screen", 7, 16.0, RANGE_SQ, 0, 0, true, true, true, false, true, true));
        assertEquals("screen", r.reason);
    }

    @Test
    public void afterAnAttackTheNextTickMayHoldAgain() {
        LagRangeRaven r = new LagRangeRaven();
        tick(r, 5.0, 0, 0);
        tick(r, 4.6, 0, 0);
        r.released();
        assertEquals(LagRangeRaven.Step.START, tick(r, 4.2, 0, 0));
    }

    @Test
    public void noWeaponOrStandingStillNeverStarts() {
        LagRangeRaven r = new LagRangeRaven();
        r.step(null, 7, 25.0, RANGE_SQ, 0, 0, true, false, true, false, true, true);
        assertEquals(LagRangeRaven.Step.NOTHING, r.step(null, 7, 21.0, RANGE_SQ, 0, 0, true, false, true, false, true, true));
        assertEquals(LagRangeRaven.Step.NOTHING, r.step(null, 7, 18.0, RANGE_SQ, 0, 0, false, true, true, false, true, true));
    }
}
