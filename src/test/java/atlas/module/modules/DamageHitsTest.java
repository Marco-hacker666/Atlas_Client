package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** AttackOnDamage's count and clock, shared by both aura engines (2026-11-05). */
public class DamageHitsTest {

    @Test
    public void knockbackGivesTheClicksUntilTheyAreSpent() {
        DamageHits hits = new DamageHits();
        assertFalse(hits.due(1000L));
        hits.knockedBack(2, 1000L);
        assertTrue(hits.due(1000L));
        hits.spend();
        assertTrue(hits.due(1050L));
        hits.spend();
        assertFalse(hits.due(1100L));
    }

    @Test
    public void theyLapse() {
        DamageHits hits = new DamageHits();
        hits.knockedBack(4, 1000L);
        assertTrue(hits.due(1000L + DamageHits.LAPSE_MS));
        assertFalse(hits.due(1001L + DamageHits.LAPSE_MS));
    }

    @Test
    public void knockbackSoonAfterIsTheSameHit() {
        DamageHits hits = new DamageHits();
        hits.knockedBack(1, 1000L);
        hits.spend();
        hits.knockedBack(1, 1000L + DamageHits.GAP_MS);
        assertFalse(hits.due(1000L + DamageHits.GAP_MS));
        hits.knockedBack(1, 1001L + 2 * DamageHits.GAP_MS);
        assertTrue(hits.due(1001L + 2 * DamageHits.GAP_MS));
    }

    @Test
    public void spendingNothingAndClearingAreSafe() {
        DamageHits hits = new DamageHits();
        hits.spend();
        assertFalse(hits.due(1000L));
        hits.knockedBack(3, 1000L);
        hits.clear();
        assertFalse(hits.due(1000L));
    }
}
