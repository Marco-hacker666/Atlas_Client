package atlas.management;

import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** LagGuard's rules (2026-10-28): when a delay module may hold, and what a correction costs it. */
public class LagGuardRulesTest {

    private static LagGuardRules groundedFor(int ticks) {
        LagGuardRules rules = new LagGuardRules();
        for (int t = 0; t < ticks; t++) {
            rules.tick(t, true);
        }
        return rules;
    }

    @Test
    public void nothingHeldFromAKnockbackUntilBackOnTheGround() {
        LagGuardRules rules = groundedFor(10);
        rules.knockback(10);
        assertEquals("knockback", rules.deny(11, "FakeLag", false));
        assertEquals("knockback", rules.deny(11, "Backtrack", true));
        // In the air for a while, then down two ticks after the eighth.
        for (int t = 11; t < 20; t++) {
            rules.tick(t, t >= 19);
        }
        assertTrue(rules.knockbackWindow());
        rules.tick(20, true);
        assertFalse(rules.knockbackWindow());
        assertNull(rules.deny(21, "FakeLag", false));
    }

    @Test
    public void theWindowEndsAfterFortyTicksWhateverHappens() {
        LagGuardRules rules = new LagGuardRules();
        rules.knockback(0);
        for (int t = 1; t <= 41; t++) {
            rules.tick(t, false);
        }
        assertFalse(rules.knockbackWindow());
    }

    @Test
    public void onlyAWholeStreamRunsThroughTheKnockbackWindow() {
        LagGuardRules rules = groundedFor(10);
        rules.knockback(10);
        // Spike holding only the push: the lone late push is stopped.
        assertEquals("knockback", rules.deny(10, "KnockbackDelay", true));
        rules.stream("KnockbackDelay", true);
        assertNull(rules.deny(10, "KnockbackDelay", true));
        assertNull("and through the pause, ramping itself down", rules.deny(10, "KnockbackDelay", true));
        assertEquals("knockback", rules.deny(10, "Backtrack", true));
        assertEquals("knockback", rules.deny(10, "Freeze", true));
        rules.stream("KnockbackDelay", false);
        assertEquals("knockback", rules.deny(11, "KnockbackDelay", true));
    }

    @Test
    public void aWholeStreamRampsToItsAllowance() {
        LagGuardRules rules = groundedFor(100);
        rules.stream("KnockbackDelay", true);
        assertEquals(LagGuardRules.DEFAULT_CAP_MS, rules.streamAllowance(100, "KnockbackDelay"));
        rules.knockback(100);
        java.util.Map<String, Long> holding = new java.util.HashMap<String, Long>();
        holding.put("KnockbackDelay", 130L);
        assertTrue(rules.setback(105, holding).contains("KnockbackDelay"));
        assertEquals("the pause", 0L, rules.streamAllowance(110, "KnockbackDelay"));
        assertNull(rules.deny(110, "KnockbackDelay", true));
        assertEquals(65L, rules.streamAllowance(200, "KnockbackDelay"));
        // Still in the knockback window: a second strike, and it is done for this world.
        rules.setback(300, holding);
        assertEquals("struck out", rules.deny(500, "KnockbackDelay", true));
        assertEquals(0L, rules.streamAllowance(500, "KnockbackDelay"));
    }

    @Test
    public void aWholeStreamIsStruckOnlyNearOurKnockback() {
        LagGuardRules rules = groundedFor(100);
        rules.stream("KnockbackDelay", true);
        java.util.Map<String, Long> holding = new java.util.HashMap<String, Long>();
        holding.put("KnockbackDelay", 125L);
        assertTrue("someone else's correction mid-fight", rules.setback(100, holding).isEmpty());
        rules.knockback(200);
        assertTrue(rules.setback(210, holding).contains("KnockbackDelay"));
    }

    @Test
    public void aTrainOfCorrectionsStrikesOnce() {
        LagGuardRules rules = groundedFor(100);
        rules.held(100, "Silent Aura", 250L);
        assertTrue(rules.setback(101, null).contains("Silent Aura"));
        assertTrue(rules.setback(103, null).isEmpty());
        assertTrue(rules.setback(106, null).isEmpty());
        assertEquals(1, rules.strikes("Silent Aura"));
        // The pause runs from the last of the train.
        assertEquals("after a correction", rules.deny(150, "FakeLag", false));
        assertNull(rules.deny(167, "FakeLag", false));
    }

    @Test
    public void farOrEarlyPositionPacketsAreTeleports() {
        LagGuardRules rules = new LagGuardRules();
        assertTrue("spawn", rules.teleport(0.5));
        for (int t = 0; t < LagGuardRules.JOIN_TICKS; t++) {
            rules.tick(t, true);
        }
        assertFalse(rules.teleport(2.3));
        assertTrue(rules.teleport(30.0));
        rules.reset();
        assertTrue("a new world", rules.teleport(2.3));
    }

    @Test
    public void afterADigOrPlacementOutgoingWaitsThreeTicks() {
        LagGuardRules rules = groundedFor(5);
        rules.block(5);
        assertEquals("dig/place", rules.deny(6, "FakeLag", false));
        assertNull(rules.deny(6, "Backtrack", true));
        assertNull(rules.deny(8, "FakeLag", false));
    }

    @Test
    public void aCorrectionPausesEveryoneAndStrikesWhoHeld() {
        LagGuardRules rules = groundedFor(5);
        rules.held(100, "FakeLag", 300L);
        rules.held(101, "FakeLag", 360L);
        rules.held(60, "LagRange", 200L);
        Collection<String> struck = rules.setback(110, null);
        assertTrue(struck.contains("FakeLag"));
        assertFalse("held long before", struck.contains("LagRange"));
        assertEquals("after a correction", rules.deny(150, "LagRange", false));
        assertNull(rules.deny(170, "LagRange", false));
        assertEquals(1, rules.strikes("FakeLag"));
        assertEquals(180L, rules.capMs("FakeLag"));
        assertEquals(LagGuardRules.DEFAULT_CAP_MS, rules.capMs("LagRange"));
        // A second strike: no more holding in this world.
        rules.held(300, "FakeLag", 100L);
        rules.setback(305, null);
        assertEquals("struck out", rules.deny(500, "FakeLag", false));
        assertEquals(50L, rules.capMs("FakeLag"));
        rules.reset();
        assertNull(rules.deny(10, "FakeLag", false));
    }

    @Test
    public void fallSafetyAndThePlayersBlinkAreNotGuarded() {
        LagGuardRules rules = groundedFor(5);
        rules.knockback(5);
        assertNull(rules.deny(6, "AntiVoid", false));
        assertNull(rules.deny(6, "NoFall", false));
        assertNull(rules.deny(6, "Scaffold", false));
        assertNull(rules.deny(6, "Blink", false));
        rules.held(6, "AntiVoid", 900L);
        assertTrue(rules.setback(7, null).isEmpty());
    }

    @Test
    public void anIncomingHolderIsStruckOnlyWhenHoldingAtTheCorrection() {
        LagGuardRules rules = groundedFor(5);
        java.util.Map<String, Long> none = new java.util.HashMap<String, Long>();
        assertTrue(rules.setback(100, none).isEmpty());
        java.util.Map<String, Long> now = new java.util.HashMap<String, Long>();
        now.put("Backtrack", 60L);
        Collection<String> struck = rules.setback(200, now);
        assertTrue(struck.contains("Backtrack"));
        assertEquals(50L, rules.capMs("Backtrack"));
    }
}
