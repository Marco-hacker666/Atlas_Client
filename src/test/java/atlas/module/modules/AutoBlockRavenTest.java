package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** AutoBlock's Raven mode keeps Raven bS's millisecond-to-tick rules (2026-10-31). */
public class AutoBlockRavenTest {

    @Test
    public void millisecondsRoundUpToTicks() {
        assertEquals(0, AutoBlockRaven.msToTicks(0));
        assertEquals(1, AutoBlockRaven.msToTicks(50));
        assertEquals(3, AutoBlockRaven.msToTicks(150));
        assertEquals(4, AutoBlockRaven.msToTicks(151));
        assertEquals(4, AutoBlockRaven.msToTicks(200));
        assertEquals(10, AutoBlockRaven.msToTicks(500));
    }

    @Test
    public void noExtraHoldUnlessAsked() {
        assertEquals(0, AutoBlockRules.extraHoldTicks(0, 0.99, 0.99));
    }

    @Test
    public void theExtraHoldIsTheShorterDrawAndStaysInRange() {
        assertEquals(0, AutoBlockRules.extraHoldTicks(4, 0.0, 0.99));
        assertEquals(4, AutoBlockRules.extraHoldTicks(4, 0.99, 0.99));
        assertEquals(1, AutoBlockRules.extraHoldTicks(4, 0.25, 0.99));
    }

    @Test
    public void theCooldownCountsFromTheLastBlock() {
        assertFalse("off", AutoBlockRules.coolingDown(0, 10L));
        assertFalse("no block yet", AutoBlockRules.coolingDown(200, -1L));
        assertTrue(AutoBlockRules.coolingDown(200, 199L));
        assertFalse(AutoBlockRules.coolingDown(200, 200L));
    }

    @Test
    public void onlyATargetHeadingAwayIsRunning() {
        assertTrue("straight away", AutoBlockRules.movingAway(0.2, 0.0, 3.0, 0.0));
        assertTrue("59 degrees off", AutoBlockRules.movingAway(0.103, 0.1714, 3.0, 0.0));
        assertFalse("toward us", AutoBlockRules.movingAway(-0.2, 0.0, 3.0, 0.0));
        assertFalse("sideways", AutoBlockRules.movingAway(0.0, 0.2, 3.0, 0.0));
        assertFalse("standing", AutoBlockRules.movingAway(0.02, 0.0, 3.0, 0.0));
        assertFalse("on top of us", AutoBlockRules.movingAway(0.2, 0.0, 0.0, 0.0));
    }

    @Test
    public void theNewRavenSettingsStartOff() {
        BlockHit block = new BlockHit();
        assertFalse("raven-auto is a switch, off by default", block.ravenAuto.getValue());
        assertEquals(Integer.valueOf(0), block.ravenRandomHold.getValue());
        assertEquals(Integer.valueOf(0), block.ravenCooldown.getValue());
        assertFalse(block.ravenReleaseOnHit.getValue());
        assertFalse(block.ravenNoChasing.getValue());
        assertEquals("Keep", block.ravenOutOfRange.getModeString());
        assertFalse(block.ravenForceAttack.getValue());
        assertFalse(block.ravenMobs.getValue());
    }

    @Test
    public void theDamagedTriggerIsOneToTenTicks() {
        assertEquals(4, AutoBlockRaven.hurtTrigger(200));
        assertEquals(1, AutoBlockRaven.hurtTrigger(50));
        assertEquals(10, AutoBlockRaven.hurtTrigger(500));
        assertEquals(1, AutoBlockRaven.hurtTrigger(0));
        assertEquals(3, AutoBlockRaven.hurtTrigger(150));
    }
}
