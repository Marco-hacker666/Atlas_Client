package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** KillAura's Yuri auto-block modes (2026-10-24). */
public class KillAuraYuriTest {

    private static KillAuraYuri.Plan step(KillAuraYuri y, int mode, double dist, int hurt, long msToClick) {
        return y.step(mode, true, dist, hurt, false, 4, msToClick, false);
    }

    @Test
    public void hypixelBlocksOneTickAfterTheClickAndLetsGoBeforeTheNext() {
        KillAuraYuri y = new KillAuraYuri();
        KillAuraYuri.Plan p = step(y, KillAuraYuri.HYPIXEL, 2.5, 0, 40); // click due within 50 ms: mark
        assertFalse(p.block);
        p = step(y, KillAuraYuri.HYPIXEL, 2.5, 0, 0); // the click tick: still marked, nothing
        assertFalse(p.block);
        p = step(y, KillAuraYuri.HYPIXEL, 2.5, 0, 125); // the tick after: up
        assertTrue(p.block);
        assertTrue(y.blocking);
        p = step(y, KillAuraYuri.HYPIXEL, 2.5, 0, 75); // next tick: let go
        assertTrue(p.release);
        assertFalse(p.block);
        assertFalse(y.blocking);
    }

    @Test
    public void hypixelNeverBlocksBeyondThree() {
        KillAuraYuri y = new KillAuraYuri();
        step(y, KillAuraYuri.HYPIXEL, 4.0, 0, 40);
        KillAuraYuri.Plan p = step(y, KillAuraYuri.HYPIXEL, 4.0, 0, 125);
        assertFalse(p.block);
    }

    @Test
    public void legitHoldsTheKeyOnlyEarlyInTheHurtTimeAndClicksAfterAnUpTick() {
        KillAuraYuri y = new KillAuraYuri();
        KillAuraYuri.Plan p = step(y, KillAuraYuri.LEGIT, 2.0, 8, 0);
        assertEquals(Boolean.TRUE, p.useKey);
        assertFalse(p.mayClick);
        p = step(y, KillAuraYuri.LEGIT, 2.0, 3, 0);
        assertEquals(Boolean.FALSE, p.useKey);
        assertTrue(p.mayClick); // a tick with the key up came first
        p = step(y, KillAuraYuri.LEGIT, 3.5, 8, 0);
        assertEquals(Boolean.FALSE, p.useKey); // beyond 3 blocks
    }

    @Test
    public void ncpLetsGoAtTheStartAndBlocksAfterTheMove() {
        KillAuraYuri y = new KillAuraYuri();
        KillAuraYuri.Plan p = step(y, KillAuraYuri.NCP, 2.0, 0, 0);
        assertFalse(p.release);
        assertTrue(p.blockAfterMove);
        y.blockedAfterMove();
        p = step(y, KillAuraYuri.NCP, 2.0, 0, 0);
        assertTrue(p.release);
        assertTrue(p.blockAfterMove);
    }

    @Test
    public void vanillaBlocksEveryTick() {
        KillAuraYuri y = new KillAuraYuri();
        assertTrue(step(y, KillAuraYuri.VANILLA, 2.0, 0, 0).block);
        assertTrue(step(y, KillAuraYuri.VANILLA, 5.0, 0, 0).block);
        assertFalse(step(y, KillAuraYuri.VANILLA, 5.0, 0, 0).release);
    }

    @Test
    public void outOfRangeOrNotHurtEnoughLetsGo() {
        KillAuraYuri y = new KillAuraYuri();
        step(y, KillAuraYuri.VANILLA, 2.0, 0, 0);
        KillAuraYuri.Plan p = y.step(KillAuraYuri.VANILLA, false, 9.0, 0, false, 4, 0, false);
        assertTrue(p.release);
        assertFalse(y.blocking);

        step(y, KillAuraYuri.VANILLA, 2.0, 0, 0);
        p = y.step(KillAuraYuri.VANILLA, true, 2.0, 2, true, 4, 0, false);
        assertTrue(p.release);
        assertFalse(p.block);
        p = y.step(KillAuraYuri.VANILLA, true, 2.0, 5, true, 4, 0, false);
        assertTrue(p.block);
    }

    @Test
    public void legitLettingGoReleasesTheKeyOnlyIfItHeldIt() {
        KillAuraYuri y = new KillAuraYuri();
        KillAuraYuri.Plan p = y.step(KillAuraYuri.LEGIT, false, 9.0, 0, false, 4, 0, false);
        assertNull(p.useKey);
        step(y, KillAuraYuri.LEGIT, 2.0, 8, 0);
        p = y.step(KillAuraYuri.LEGIT, false, 9.0, 0, false, 4, 0, false);
        assertEquals(Boolean.FALSE, p.useKey);
    }

    @Test
    public void clickRatesAreYurisForHypixelAndLegit() {
        assertEquals(125L, KillAuraYuri.CLICK_MS[KillAuraYuri.HYPIXEL]);
        assertEquals(200L, KillAuraYuri.CLICK_MS[KillAuraYuri.LEGIT]);
        assertEquals(0L, KillAuraYuri.CLICK_MS[KillAuraYuri.NCP]);
    }
}
