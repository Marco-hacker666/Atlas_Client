package atlas.lb.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuraRulesTest {
    @Test
    public void requirementsAreAllOfTheChosenOnes() {
        assertTrue(AuraRules.requirementsMet(0, false, false, false, false, true));
        int weaponAndClick = AuraRules.REQUIRE_WEAPON | AuraRules.REQUIRE_CLICK;
        assertTrue(AuraRules.requirementsMet(weaponAndClick, true, true, false, false, false));
        assertFalse(AuraRules.requirementsMet(weaponAndClick, false, true, false, false, false));
        assertFalse(AuraRules.requirementsMet(AuraRules.REQUIRE_NOT_BREAKING, true, true, true, true, true));
        assertFalse(AuraRules.requirementsMet(AuraRules.REQUIRE_EMPTY_HAND, true, true, false, true, false));
    }

    @Test
    public void rangesFromVanillaReach() {
        assertEquals(4.0F, AuraRules.interactionRange(1.0F), 0.0F);
        assertEquals(6.5F, AuraRules.scanRange(4.0F, 3.0F, 2.5F), 0.0F);
        assertEquals(8.0F, AuraRules.scanRange(4.0F, 6.0F, 2.0F), 0.0F);
    }

    @Test
    public void smartDoesNotWaitOnTheGroundWithoutAJump() {
        assertTrue(AuraRules.criticalAllows(AuraRules.CRITS_SMART, true, false, 0.0, 0.0F, false, -1));
    }

    @Test
    public void smartWaitsDuringARiseThatComesDownToACrit() {
        assertFalse(AuraRules.criticalAllows(AuraRules.CRITS_SMART, true, true, 0.0, 0.0F, false, 12));

        assertTrue(AuraRules.criticalAllows(AuraRules.CRITS_SMART, true, true, 0.0, 0.0F, false, 2));
    }

    @Test
    public void smartHitsOnceFalling() {
        assertTrue(AuraRules.criticalAllows(AuraRules.CRITS_SMART, false, false, -0.2, 0.3F, false, 5));
        assertFalse(AuraRules.criticalAllows(AuraRules.CRITS_SMART, false, false, -0.01, 0.0F, false, 5));
    }

    @Test
    public void blockedCritsNeverWait() {
        assertTrue(AuraRules.criticalAllows(AuraRules.CRITS_SMART, false, true, 0.3, 0.0F, true, 12));
    }

    @Test
    public void alwaysOnlyOnACrit() {
        assertFalse(AuraRules.criticalAllows(AuraRules.CRITS_ALWAYS, true, false, 0.0, 0.0F, false, -1));
        assertTrue(AuraRules.criticalAllows(AuraRules.CRITS_ALWAYS, false, false, -0.3, 0.5F, false, 3));
        assertTrue(AuraRules.criticalAllows(AuraRules.CRITS_IGNORE, true, false, 0.0, 0.0F, true, -1));
    }
}
