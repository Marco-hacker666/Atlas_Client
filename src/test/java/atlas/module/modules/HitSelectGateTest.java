package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

public class HitSelectGateTest {

    @Test
    public void onlyAHurtSoonAfterOurAttackOnThatTargetCounts() {
        HitSelectGate gate = new HitSelectGate();
        gate.attacked(1, 7.0, 1000L);
        gate.hurt(2, 1100L);
        assertEquals("someone else was hurt", 0, gate.combo(1100L));
        gate.hurt(1, 1000L + HitSelectGate.LAND_MS);
        assertEquals("too late to be ours", 0, gate.combo(1800L));
        gate.hurt(1, 1200L);
        gate.attacked(1, 7.0, 1500L);
        gate.hurt(1, 1600L);
        assertEquals(2, gate.combo(1600L));
    }

    @Test
    public void knockbackAndSilenceEndTheCombo() {
        HitSelectGate gate = new HitSelectGate();
        gate.attacked(1, 7.0, 1000L);
        gate.hurt(1, 1100L);
        gate.knockedBack(1200L);
        assertEquals(0, gate.combo(1200L));
        gate.attacked(1, 7.0, 2000L);
        gate.hurt(1, 2100L);
        assertEquals(1, gate.combo(2000L + HitSelectGate.COMBO_MS));
        assertEquals(0, gate.combo(2001L + HitSelectGate.COMBO_MS));
    }

    @Test
    public void aTargetNotAttackedForAWhileIsAFirstHitAgain() {
        HitSelectGate gate = new HitSelectGate();
        assertTrue(gate.firstHit(1, 1000L));
        gate.attacked(1, 7.0, 1000L);
        assertFalse(gate.firstHit(1, 1000L + HitSelectGate.FIRST_HIT_MS));
        assertTrue(gate.firstHit(1, 1001L + HitSelectGate.FIRST_HIT_MS));
    }

    @Test
    public void onlyAHarderHitBeatsTheLastOne() {
        HitSelectGate gate = new HitSelectGate();
        assertFalse("nothing to compare with", gate.beats(1, 10.0));
        gate.attacked(1, 7.0, 1000L);
        assertFalse(gate.beats(1, 7.0));
        assertTrue(gate.beats(1, HitSelectGate.damage(7.0, 0.0, true)));
    }

    @Test
    public void aCriticalLeavesEnchantmentsOutOfTheMultiplier() {
        assertEquals(8.25, HitSelectGate.damage(4.0, 2.25, true), 1.0E-9);
        assertEquals(6.25, HitSelectGate.damage(4.0, 2.25, false), 1.0E-9);
    }

    @Test
    public void theWaitRunsFromTheFirstAskAndKnockbackEndsIt() {
        HitSelectGate gate = new HitSelectGate();
        assertFalse("off", gate.waiting(1, 1000L, 0L));
        assertTrue(gate.waiting(1, 1000L, 200L));
        assertTrue(gate.waiting(1, 1150L, 200L));
        assertFalse(gate.waiting(1, 1200L, 200L));
        assertFalse("done until the player leaves", gate.waiting(1, 1250L, 200L));
        gate.knockedBack(1260L);
        gate.attacked(1, 7.0, 1300L);
        assertFalse("hit once: not a first hit", gate.waiting(1, 1300L, 200L));
    }

    /** Knockback ends a wait: it must not start a new one for the next hit. */
    @Test
    public void knockbackDuringAWaitLetsTheNextHitGo() {
        HitSelectGate gate = new HitSelectGate();
        assertTrue(gate.waiting(1, 1000L, 300L));
        gate.knockedBack(1100L);
        assertFalse(gate.waiting(1, 1150L, 300L));
        assertFalse("nor for anyone else", gate.waiting(2, 1200L, 300L));
    }

    @Test
    public void aWaitStartsAgainAfterThePlayerLeft() {
        HitSelectGate gate = new HitSelectGate();
        assertTrue(gate.waiting(1, 1000L, 200L));
        assertFalse(gate.waiting(1, 1300L, 200L));
        assertTrue(gate.waiting(1, 2400L, 200L));
    }

    @Test
    public void clearForgetsEverything() {
        HitSelectGate gate = new HitSelectGate();
        gate.attacked(1, 7.0, 1000L);
        gate.hurt(1, 1100L);
        gate.clear();
        assertEquals(0, gate.combo(1100L));
        assertTrue(gate.firstHit(1, 1100L));
        assertFalse(gate.beats(1, 99.0));
    }
}
