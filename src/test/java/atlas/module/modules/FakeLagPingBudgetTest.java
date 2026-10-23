package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** FakeLag's ping budget: ping plus delay kept under the budget (2026-10-23). */
public class FakeLagPingBudgetTest {

    @Test
    public void theDelayIsCutToWhatThePingLeaves() {
        assertEquals(150, FakeLag.budgetedDelay(154, 100, 250));
        assertEquals(50, FakeLag.budgetedDelay(154, 200, 250));
        assertEquals(0, FakeLag.budgetedDelay(154, 300, 250));
    }

    @Test
    public void aLowPingLeavesTheDelayAsSet() {
        assertEquals(154, FakeLag.budgetedDelay(154, 40, 250));
    }

    @Test
    public void offOrNoReadingLeavesTheDelayAsSet() {
        assertEquals(154, FakeLag.budgetedDelay(154, 300, 0));
        assertEquals(154, FakeLag.budgetedDelay(154, -1, 250));
    }

    @Test
    public void holdingIsRuledOutOnlyWhenTheBudgetLeavesTooLittle() {
        assertTrue(FakeLag.pingRulesOut(154, 220, 250));
        assertFalse(FakeLag.pingRulesOut(154, 200, 250));
        assertFalse(FakeLag.pingRulesOut(154, -1, 250));
        assertFalse(FakeLag.pingRulesOut(154, 300, 0));
        // A small delay chosen on purpose is not the budget's doing.
        assertFalse(FakeLag.pingRulesOut(30, 100, 250));
    }
}
