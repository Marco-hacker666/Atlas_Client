package atlas.module.modules;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** raven-auto-human's block timing (2026-11-08). */
public class RavenAutoPlanTest {

    private static final int PIKA_PING = 230;

    @Test
    public void facingIsMeasuredFromTheEnemysLook() {
        /* Yaw 0 looks toward +Z. This player straight ahead on +Z. */
        assertEquals(0.0, RavenAutoPlan.facingDeg(0.0F, 0.0, 3.0), 1e-6);
        assertEquals(180.0, RavenAutoPlan.facingDeg(180.0F, 0.0, 3.0), 1e-6);
        assertEquals(90.0, RavenAutoPlan.facingDeg(90.0F, 0.0, 3.0), 1e-6);
        assertEquals(10.0, RavenAutoPlan.facingDeg(370.0F, 0.0, 3.0), 1e-6);
    }

    @Test
    public void theHurtRaiseFollowsTheRoundTrip() {
        assertEquals("Pika ping, no jitter", 5, RavenAutoPlan.hurtRaiseTick(PIKA_PING, 0.0));
        assertEquals("Pika ping, slow hand", 6, RavenAutoPlan.hurtRaiseTick(PIKA_PING, 1.0));
        assertEquals("Pika ping, quick hand", 4, RavenAutoPlan.hurtRaiseTick(PIKA_PING, -1.0));
        assertEquals("singleplayer", 1, RavenAutoPlan.hurtRaiseTick(0, 0.0));
        assertEquals("capped", 9, RavenAutoPlan.hurtRaiseTick(2000, 3.0));
    }

    @Test
    public void holdsCentreOnMaxHoldAndDropsAreMostlyOneTick() {
        assertEquals(2, RavenAutoPlan.holdTicks(2, 0, 0.0, 0.0, 0.0));
        assertEquals(1, RavenAutoPlan.holdTicks(1, 0, -2.5, 0.0, 0.0));
        assertEquals("plus random-hold", 4, RavenAutoPlan.holdTicks(2, 2, 0.0, 0.99, 0.99));
        assertEquals(1, RavenAutoPlan.downTicks(0.5, 0.5, 0.0));
        assertEquals(2, RavenAutoPlan.downTicks(0.8, 0.5, 0.0));
        assertEquals(3, RavenAutoPlan.downTicks(0.95, 0.5, 0.0));
        assertEquals("a lapse", 5, RavenAutoPlan.downTicks(0.5, 0.01, 0.99));
    }

    @Test
    public void aFreshThreatWaitsForAReaction() {
        RavenAutoPlan plan = new RavenAutoPlan(new Random(1));
        assertFalse("not on the tick the enemy turns", plan.wantUp(0L, false, true, 0, false, PIKA_PING, 2, 0));
        assertFalse(plan.wantUp(RavenAutoPlan.REACT_MIN_MS - 1, false, true, 0, false, PIKA_PING, 2, 0));
        assertTrue("up after the slowest reaction", plan.wantUp(RavenAutoPlan.REACT_MAX_MS, false, true, 0, false,
                PIKA_PING, 2, 0));
    }

    @Test
    public void noThreatNoBlockAfterALinger() {
        RavenAutoPlan plan = new RavenAutoPlan(new Random(2));
        plan.wantUp(0L, false, true, 0, false, PIKA_PING, 4, 0);
        assertTrue(plan.wantUp(300L, false, true, 0, false, PIKA_PING, 4, 0));
        assertTrue("lingers as the enemy turns away", plan.wantUp(350L, true, false, 0, false, PIKA_PING, 4, 0));
        assertFalse("then lets go", plan.wantUp(350L + RavenAutoPlan.LINGER_MAX_MS, true, false, 0, false,
                PIKA_PING, 4, 0));
    }

    @Test
    public void aHitDropsTheSwordUntilTheInvulnerabilityIsNearlyOver() {
        RavenAutoPlan plan = new RavenAutoPlan(new Random(3));
        long t = 0L;
        plan.wantUp(t, false, true, 0, false, PIKA_PING, 20, 0);
        t += 300L;
        assertTrue(plan.wantUp(t, false, true, 0, false, PIKA_PING, 20, 0));
        t += 50L;
        assertFalse("down on the hit", plan.wantUp(t, true, true, 10, true, PIKA_PING, 20, 0));
        boolean upAgain = false;
        int raisedAt = -1;
        for (int hurt = 9; hurt >= 0 && !upAgain; hurt--) {
            t += 50L;
            upAgain = plan.wantUp(t, false, true, hurt, false, PIKA_PING, 20, 0);
            if (upAgain) {
                raisedAt = hurt;
            }
        }
        assertTrue(upAgain);
        assertTrue("raised near the round trip, got " + raisedAt, raisedAt >= 3 && raisedAt <= 7);
    }

    @Test
    public void theBlockIsNotASquareWave() {
        RavenAutoPlan plan = new RavenAutoPlan(new Random(4));
        boolean blocking = false;
        long t = 0L;
        Set<Integer> holds = new HashSet<Integer>();
        Set<Integer> drops = new HashSet<Integer>();
        int run = 0;
        boolean last = false;
        for (int i = 0; i < 2000; i++) {
            boolean up = plan.wantUp(t, blocking, true, 0, false, PIKA_PING, 2, 1);
            if (i > 10) {
                if (up == last) {
                    run++;
                } else {
                    (last ? holds : drops).add(run);
                    run = 1;
                }
            }
            last = up;
            blocking = up;
            t += 50L;
        }
        assertTrue("hold lengths vary: " + holds, holds.size() >= 3);
        assertTrue("drop lengths vary: " + drops, drops.size() >= 3);
    }

    @Test
    public void theNewSettingStartsOnUnderAuto() {
        BlockHit block = new BlockHit();
        assertTrue(block.ravenAutoHuman.getValue());
    }
}
