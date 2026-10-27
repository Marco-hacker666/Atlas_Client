package atlas.lb.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Rhythm block (2026-10-27): when the sword is up, when it comes down, and which click deals damage. */
public class RhythmBlockTest {

    private static RhythmBlock.Input fight(int tick, boolean using, int targetHurt, int rtt) {
        RhythmBlock.Input in = new RhythmBlock.Input();
        in.tick = tick;
        in.sword = true;
        in.using = using;
        in.targetId = 7;
        in.targetHurt = targetHurt;
        in.reachNow = true;
        in.reachSoon = true;
        in.threat = true;
        in.selfHurt = 0;
        in.rtt = rtt;
        return in;
    }

    @Test
    public void blocksWhileTheTargetIsInvulnerableAndAnEnemyThreatens() {
        RhythmBlock rb = new RhythmBlock();
        RhythmBlock.Decision d = rb.tick(fight(0, false, 8, 2));
        assertTrue(d.key);
        assertTrue(d.raise);
        assertFalse(d.hitNow);
        assertEquals(6, d.wait);
    }

    @Test
    public void noThreatNoBlock() {
        RhythmBlock rb = new RhythmBlock();
        RhythmBlock.Input in = fight(0, false, 8, 2);
        in.threat = false;
        assertFalse(rb.tick(in).key);
    }

    @Test
    public void noBlockWhileWeAreStillInvulnerableOnTheServer() {
        RhythmBlock rb = new RhythmBlock();
        RhythmBlock.Input in = fight(0, false, 8, 2);
        in.selfHurt = 9;
        assertFalse(rb.tick(in).key);
        // 3 - 2 <= 1: our invulnerability is over by the time a block sent now arrives.
        in = fight(1, false, 7, 2);
        in.selfHurt = 3;
        assertTrue(rb.tick(in).key);
    }

    @Test
    public void noSwordNoKey() {
        RhythmBlock rb = new RhythmBlock();
        RhythmBlock.Input in = fight(0, false, 8, 2);
        in.sword = false;
        RhythmBlock.Decision d = rb.tick(in);
        assertFalse(d.key);
        assertFalse(d.raise);
    }

    @Test
    public void comesDownTheTickBeforeTheHitIsDueButNotBeforeMinHold() {
        RhythmBlock rb = new RhythmBlock();
        assertTrue(rb.tick(fight(0, false, 8, 2)).raise);
        // Due next tick (3 - 2 = 1) but raised only one tick ago: stays up.
        assertTrue(rb.tick(fight(1, true, 3, 2)).key);
        // Held two ticks: comes down; the click is next tick's.
        RhythmBlock.Decision d = rb.tick(fight(2, true, 3, 2));
        assertFalse(d.key);
        assertFalse(d.hitNow);
    }

    @Test
    public void hitsAndRaisesAgainInTheSameTick() {
        RhythmBlock rb = new RhythmBlock();
        RhythmBlock.Decision d = rb.tick(fight(0, false, 2, 2));
        assertTrue(d.hitNow);
        assertTrue(d.key);
        assertTrue(d.raise);
    }

    @Test
    public void noHitWhileTheSwordIsUp() {
        RhythmBlock rb = new RhythmBlock();
        assertFalse(rb.tick(fight(0, true, 0, 2)).hitNow);
    }

    @Test
    public void aHigherPingHitsEarlier() {
        assertTrue(new RhythmBlock().tick(fight(0, false, 5, 5)).hitNow);
        assertFalse(new RhythmBlock().tick(fight(0, false, 5, 1)).hitNow);
    }

    @Test
    public void ourOwnHitCountsBeforeItsHurtShows() {
        RhythmBlock rb = new RhythmBlock();
        assertTrue(rb.tick(fight(10, false, 0, 3)).hitNow);
        rb.attackSent(10);
        // The hurt shows only a round trip later; until then the target is taken as invulnerable from our hit.
        RhythmBlock.Decision d = rb.tick(fight(11, true, 0, 3));
        assertFalse(d.hitNow);
        assertEquals(9, d.wait);
        assertTrue(d.key);
        rb.tick(fight(12, true, 0, 3));
        rb.tick(fight(13, true, 10, 3));
        for (int t = 14; t < 19; t++) {
            assertTrue(rb.tick(fight(t, true, 10 - (t - 13), 3)).key);
        }
        d = rb.tick(fight(19, true, 4, 3));
        assertEquals(1, d.wait);
        assertFalse(d.key);
        d = rb.tick(fight(20, false, 3, 3));
        assertEquals(0, d.wait);
        assertTrue(d.hitNow);
    }

    @Test
    public void aHitThatNeverShowsIsTriedAgain() {
        RhythmBlock rb = new RhythmBlock();
        assertTrue(rb.tick(fight(10, false, 0, 2)).hitNow);
        rb.attackSent(10);
        assertFalse(rb.tick(fight(11, false, 0, 2)).hitNow);
        // No hurt within rtt + 3 ticks: that hit did not land.
        for (int t = 12; t <= 15; t++) {
            rb.tick(fight(t, false, 0, 2));
        }
        assertTrue(rb.tick(fight(16, false, 0, 2)).hitNow);
    }

    @Test
    public void anAttackOnATickThatWasNotDueIsNotTheWindowsHit() {
        RhythmBlock rb = new RhythmBlock();
        rb.tick(fight(0, false, 9, 2));
        rb.attackSent(0);
        RhythmBlock.Decision d = rb.tick(fight(1, false, 8, 2));
        assertEquals(6, d.wait);
    }

    @Test
    public void aNewTargetForgetsTheOldHit() {
        RhythmBlock rb = new RhythmBlock();
        rb.tick(fight(0, false, 0, 2));
        rb.attackSent(0);
        RhythmBlock.Input other = fight(1, false, 0, 2);
        other.targetId = 9;
        assertTrue(rb.tick(other).hitNow);
    }

    @Test
    public void atTheEdgeOfReachTheSwordWaitsOnlyALittleThenGoesUpUntilTheLookIsOn() {
        RhythmBlock rb = new RhythmBlock();
        rb.patience = 3;
        RhythmBlock.Input in;
        for (int t = 0; t < 3; t++) {
            in = fight(t, false, 0, 2);
            in.reachNow = false;
            assertFalse("waits for the hit, tick " + t, rb.tick(in).key);
        }
        in = fight(3, false, 0, 2);
        in.reachNow = false;
        assertTrue(rb.tick(in).raise);
        for (int t = 4; t < 8; t++) {
            in = fight(t, true, 0, 2);
            in.reachNow = false;
            assertTrue("stays up at the edge, tick " + t, rb.tick(in).key);
        }
        // The look lands: down now, the hit next tick.
        assertFalse(rb.tick(fight(8, true, 0, 2)).key);
        assertTrue(rb.tick(fight(9, false, 0, 2)).hitNow);
    }

    @Test
    public void aFlickerAtTheEdgeDoesNotStartTheWaitOver() {
        RhythmBlock rb = new RhythmBlock();
        rb.patience = 3;
        int t = 0;
        RhythmBlock.Input in;
        boolean using = false;
        int toggles = 0;
        boolean last = false;
        // Strafing at the edge: by distance in reach two ticks of three, the look never on.
        for (; t < 40; t++) {
            in = fight(t, using, 0, 2);
            in.reachNow = false;
            in.reachSoon = t % 3 != 2;
            RhythmBlock.Decision d = rb.tick(in);
            if (d.key != last) {
                toggles++;
                last = d.key;
            }
            using = d.key;
        }
        assertTrue("goes up once and stays up, toggles " + toggles, toggles == 1);
    }

    @Test
    public void approachingNoBlockIsRaisedIntoAHitDueNextTick() {
        RhythmBlock rb = new RhythmBlock();
        RhythmBlock.Input in = fight(0, false, 0, 2);
        in.reachNow = false;
        RhythmBlock.Decision d = rb.tick(in);
        assertFalse(d.key);
        assertTrue(rb.tick(fight(1, false, 0, 2)).hitNow);
    }

    @Test
    public void aTickWithoutATargetKeepsOurHit() {
        RhythmBlock rb = new RhythmBlock();
        rb.tick(fight(0, false, 0, 2));
        rb.attackSent(0);
        RhythmBlock.Input none = fight(1, true, 0, 2);
        none.targetId = -1;
        RhythmBlock.Decision d = rb.tick(none);
        assertFalse(d.hitNow);
        assertTrue("still blocks the threat", d.key);
        assertEquals(8, rb.tick(fight(2, true, 0, 2)).wait);
    }

    @Test
    public void releasedFromOutsideRaisesAgainWithAPress() {
        RhythmBlock rb = new RhythmBlock();
        assertTrue(rb.tick(fight(0, false, 8, 2)).raise);
        rb.released(1);
        assertTrue(rb.tick(fight(2, false, 6, 2)).raise);
    }

    @Test
    public void learnsTheRoundTripFromItsOwnHit() {
        RhythmBlock rb = new RhythmBlock();
        // Reported 2, really 5: the first hit's hurt comes back five ticks after the click.
        assertTrue(rb.tick(fight(0, false, 0, 2)).hitNow);
        rb.attackSent(0);
        for (int t = 1; t < 5; t++) {
            assertEquals(2, rb.tick(fight(t, true, 0, 2)).rtt);
        }
        RhythmBlock.Decision d = rb.tick(fight(5, true, 10, 2));
        assertEquals(5, d.rtt);
        // From now on the wait uses it: hurt 6 with a round trip of 5 is one tick away.
        assertEquals(1, rb.tick(fight(9, true, 6, 2)).wait);
    }

    /**
     * A trade against a model server: the enemy lands a hit on the first tick
     * we can take one (it clicks fast), we hit on ours. The sword is up when
     * the enemy's hits land, and down most of the time in between: up only
     * from just before our invulnerability ends until two ticks after, at any
     * ping.
     */
    @Test
    public void aTradeBlocksTheEnemysHitsWithTheSwordUpOnlyAroundThem() {
        for (int rtt = 0; rtt <= 6; rtt += 2) {
            for (int phase = 0; phase < 10; phase++) {
                RhythmBlock rb = new RhythmBlock();
                int oneWay = rtt / 2;
                boolean using = false;
                int ourDamage = -1000;
                int targetHurtFrom = Integer.MIN_VALUE;
                int enemyHit = 3 + phase;
                int selfHurtFrom = Integer.MIN_VALUE;
                int ticks = 300;
                boolean[] upAfter = new boolean[ticks];
                int taken = 0;
                int blocked = 0;
                int up = 0;
                for (int t = 0; t < ticks; t++) {
                    if (t == enemyHit) {
                        /* Judged against the sword as it left us half a trip ago. */
                        int sent = t - oneWay - 1;
                        if (sent >= 0 && upAfter[sent]) {
                            blocked++;
                        }
                        taken++;
                        selfHurtFrom = t + oneWay;
                        enemyHit = t + 10;
                    }
                    int targetHurt = targetHurtFrom == Integer.MIN_VALUE || t < targetHurtFrom ? 0
                            : Math.max(0, 10 - (t - targetHurtFrom));
                    int selfHurt = selfHurtFrom == Integer.MIN_VALUE || t < selfHurtFrom ? 0
                            : Math.max(0, 10 - (t - selfHurtFrom));
                    RhythmBlock.Input in = fight(t, using, targetHurt, rtt);
                    in.selfHurt = selfHurt;
                    RhythmBlock.Decision d = rb.tick(in);
                    if (d.hitNow) {
                        rb.attackSent(t);
                        int arrives = t + oneWay;
                        if (arrives - ourDamage >= 10) {
                            ourDamage = arrives;
                            targetHurtFrom = arrives + oneWay;
                        }
                    }
                    using = d.key;
                    upAfter[t] = d.key;
                    if (d.key) {
                        up++;
                    }
                }
                String at = "rtt " + rtt + " phase " + phase;
                assertTrue(at + ": blocked " + blocked + "/" + taken, blocked * 10 >= taken * 8);
                /* Up from a tick before the enemy's hit can land until Guard (2) ticks after: whatever the ping. */
                assertTrue(at + ": up " + up + "/" + ticks, up * 100 <= ticks * 45);
            }
        }
    }

    /**
     * The whole exchange against a model server: hits travel half the round
     * trip, the target's hurt comes back the other half, 1.8's ten ticks of
     * invulnerability. Every damaging hit is exactly ten ticks apart, none is
     * wasted, and the sword is up for most of the fight.
     */
    @Test
    public void aFightKeepsTheDamageAndBlocksMostOfTheTime() {
        for (int rtt = 0; rtt <= 8; rtt += 2) {
            RhythmBlock rb = new RhythmBlock();
            int oneWay = rtt / 2;
            boolean using = false;
            int lastDamage = -1000;
            int clientHurtFrom = Integer.MIN_VALUE;
            java.util.List<Integer> damagedAt = new java.util.ArrayList<Integer>();
            int wasted = 0;
            int up = 0;
            int ticks = 200;
            for (int t = 0; t < ticks; t++) {
                int hurt = clientHurtFrom == Integer.MIN_VALUE || t < clientHurtFrom ? 0
                        : Math.max(0, 10 - (t - clientHurtFrom));
                RhythmBlock.Decision d = rb.tick(fight(t, using, hurt, rtt));
                if (d.hitNow) {
                    rb.attackSent(t);
                    int arrives = t + oneWay;
                    if (arrives - lastDamage >= 10) {
                        lastDamage = arrives;
                        damagedAt.add(arrives);
                        clientHurtFrom = arrives + oneWay;
                    } else {
                        wasted++;
                    }
                }
                using = d.key;
                if (d.key) {
                    up++;
                }
            }
            assertEquals("rtt " + rtt + ": no wasted damaging click", 0, wasted);
            for (int i = 1; i < damagedAt.size(); i++) {
                assertEquals("rtt " + rtt + ": hit " + i, 10, damagedAt.get(i) - damagedAt.get(i - 1));
            }
            assertTrue("rtt " + rtt + ": hits " + damagedAt.size(), damagedAt.size() >= ticks / 10 - 1);
            assertTrue("rtt " + rtt + ": up " + up, up >= ticks * 8 / 10);
        }
    }
}
