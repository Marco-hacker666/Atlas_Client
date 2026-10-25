package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AutoBlock's Yuri mode (2026-10-24). */
public class BlockHitYuriTest {

    @Test
    public void aHitBlocksAfterTheComboDelayAndLetsGoAfterTheBlockDelay() {
        BlockHitYuri y = new BlockHitYuri();
        assertTrue(y.hit(true, 2.5, 3.0, 0.5, 100, 1000));
        assertEquals(BlockHitYuri.Act.NONE, y.tick(1020, false, true, 30, 30));
        assertEquals(BlockHitYuri.Act.PRESS, y.tick(1050, false, true, 30, 30));
        assertTrue(y.blocking);
        assertEquals(BlockHitYuri.Act.NONE, y.tick(1070, false, true, 30, 30));
        assertEquals(BlockHitYuri.Act.RELEASE, y.tick(1100, false, true, 30, 30));
        assertFalse(y.blocking);
    }

    @Test
    public void aTickThatSentAnActionWaits() {
        BlockHitYuri y = new BlockHitYuri();
        y.hit(true, 2.5, 3.0, 0.5, 100, 1000);
        assertEquals(BlockHitYuri.Act.NONE, y.tick(1050, true, true, 30, 30));
        assertEquals(BlockHitYuri.Act.PRESS, y.tick(1100, false, true, 30, 30));
    }

    @Test
    public void withoutForwardTheComboIsDropped() {
        BlockHitYuri y = new BlockHitYuri();
        y.hit(true, 2.5, 3.0, 0.5, 100, 1000);
        assertEquals(BlockHitYuri.Act.NONE, y.tick(1050, false, false, 30, 30));
        assertEquals(BlockHitYuri.Act.NONE, y.tick(1100, false, true, 30, 30));
    }

    @Test
    public void rangeSwordAndChanceGateTheStart() {
        BlockHitYuri y = new BlockHitYuri();
        assertFalse(y.hit(true, 3.5, 3.0, 0.5, 100, 0));
        assertFalse(y.hit(false, 2.0, 3.0, 0.5, 100, 0));
        assertFalse(y.hit(true, 2.0, 3.0, 0.7, 50, 0));
        assertTrue(y.hit(true, 2.0, 3.0, 0.4, 50, 0));
    }

    @Test
    public void hitsWhileBlockingStartNothing() {
        BlockHitYuri y = new BlockHitYuri();
        y.hit(true, 2.0, 3.0, 0.1, 100, 0);
        y.tick(50, false, true, 30, 300);
        assertFalse(y.hit(true, 2.0, 3.0, 0.1, 100, 60));
    }
}
