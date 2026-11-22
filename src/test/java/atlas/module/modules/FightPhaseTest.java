package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

public class FightPhaseTest {
    private static final int MEMORY = 25;
    private static final int COMBOED = 2;

    @Test
    public void nobodyNearIsIdleSomeoneNearIsApproach() {
        FightPhase p = new FightPhase();
        assertEquals(FightPhase.Phase.IDLE, p.tick(100, false, MEMORY, COMBOED));
        assertEquals(FightPhase.Phase.APPROACH, p.tick(101, true, MEMORY, COMBOED));
    }

    @Test
    public void ourHitStartsACombo() {
        FightPhase p = new FightPhase();
        p.attackSent(7, 100);
        assertTrue(p.opponentHurt(7, 102));
        assertEquals(FightPhase.Phase.COMBO, p.tick(102, true, MEMORY, COMBOED));
        assertEquals(1, p.ourStreak());
    }

    @Test
    public void aHurtWithoutOurAttackIsNotOurs() {
        FightPhase p = new FightPhase();
        p.attackSent(7, 100);
        assertFalse(p.opponentHurt(8, 101));
        assertFalse("too late to be ours", p.opponentHurt(7, 100 + FightPhase.LAND_WINDOW + 1));
        assertEquals(0, p.ourStreak());
    }

    @Test
    public void oneHitOnUsIsATradeTwoInARowIsComboed() {
        FightPhase p = new FightPhase();
        p.attackSent(7, 100);
        p.opponentHurt(7, 101);
        p.weWereHit(110);
        assertEquals(FightPhase.Phase.TRADE, p.tick(110, true, MEMORY, COMBOED));
        p.weWereHit(121);
        assertEquals(FightPhase.Phase.COMBOED, p.tick(121, true, MEMORY, COMBOED));
        p.attackSent(7, 125);
        p.opponentHurt(7, 126);
        assertEquals("our hit breaks their run", FightPhase.Phase.COMBO, p.tick(126, true, MEMORY, COMBOED));
        assertEquals(0, p.theirStreak());
    }

    @Test
    public void theExchangeEndsAfterMemory() {
        FightPhase p = new FightPhase();
        p.attackSent(7, 100);
        p.opponentHurt(7, 101);
        assertEquals(FightPhase.Phase.COMBO, p.tick(101 + MEMORY, true, MEMORY, COMBOED));
        assertEquals(FightPhase.Phase.APPROACH, p.tick(102 + MEMORY, true, MEMORY, COMBOED));
        assertEquals(0, p.ourStreak());
    }

    @Test
    public void oneAttackCountsOnce() {
        FightPhase p = new FightPhase();
        p.attackSent(7, 100);
        assertTrue(p.opponentHurt(7, 101));
        assertFalse(p.opponentHurt(7, 111));
        assertEquals(1, p.ourStreak());
    }
}
