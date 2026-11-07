package atlas.lb.combat;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * NearMiss (2026-11-08): the near misses Intave PreAttack wants (4 in 100 attacks) and the band their look must be in
 * (off the box grown by 0.05, on the one grown by 0.25), against a player-sized box 3 blocks ahead.
 */
public class NearMissTest {

    /** A player's box, feet at (0, 64, 3). */
    private static final AxisAlignedBB BOX = new AxisAlignedBB(-0.3, 64.0, 2.7, 0.3, 65.8, 3.3);
    private static final Vec3 EYES = new Vec3(0.0, 65.62, 0.0);

    private static Vec3 toward(Vec3 point) {
        return point.subtract(EYES).normalize();
    }

    @Test
    public void theBandIsBetweenTheTwoGrownBoxes() {
        assertFalse("on the box: an attack was owed (AttackRequired)", NearMiss.isNearMiss(EYES, toward(new Vec3(0.0, 65.0, 3.0)), BOX));
        assertFalse("0.03 off the side: inside the 0.05 box", NearMiss.isNearMiss(EYES, toward(new Vec3(0.33, 65.0, 2.7)), BOX));
        assertTrue("0.15 off the side", NearMiss.isNearMiss(EYES, toward(new Vec3(0.45, 65.0, 2.7)), BOX));
        assertFalse("0.4 off: not even on the 0.25 box", NearMiss.isNearMiss(EYES, toward(new Vec3(0.7, 65.0, 2.7)), BOX));
    }

    @Test
    public void tooFarIsNoNearMiss() {
        AxisAlignedBB far = BOX.offset(0.0, 0.0, 2.0);
        assertFalse(NearMiss.isNearMiss(EYES, toward(new Vec3(0.45, 65.0, 4.7)), far));
    }

    @Test
    public void theAimPointPassesItsOwnCheckOnBothSides() {
        NearMiss nearMiss = new NearMiss(new Random(7));
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 50; i++) {
                Vec3 point = nearMiss.aimPoint(EYES, BOX, side);
                assertNotNull(point);
                assertTrue(NearMiss.isNearMiss(EYES, toward(point), BOX));
                assertEquals("on the side asked", side, NearMiss.sideOf(EYES, BOX, toward(point)));
            }
        }
    }

    @Test
    public void noAimPointBeyondReach() {
        assertNull(new NearMiss(new Random(1)).aimPoint(EYES, BOX.offset(0.0, 0.0, 3.0), 1));
    }

    /** Over windows of 100 attacks, at least REQUIRED near misses each, spaced, all of them on immune clicks. */
    @Test
    public void keepsThePaceOnImmuneClicks() {
        NearMiss nearMiss = new NearMiss();
        Random random = new Random(3);
        int lastGap = Integer.MAX_VALUE;
        int sinceMiss = NearMiss.SPACING;
        for (int window = 0; window < 5; window++) {
            int missesThisWindow = 0;
            for (int attack = 0; attack < NearMiss.WINDOW; attack++) {
                boolean immune = random.nextInt(3) != 0;
                if (nearMiss.due(7, immune)) {
                    nearMiss.missed();
                    missesThisWindow++;
                    lastGap = Math.min(lastGap, sinceMiss);
                    sinceMiss = 0;
                }
                nearMiss.attacked();
                sinceMiss++;
            }
            assertTrue("window " + window + ": " + missesThisWindow, missesThisWindow >= NearMiss.REQUIRED);
            assertTrue("not many more than asked", missesThisWindow <= 9);
        }
        assertTrue(lastGap >= NearMiss.SPACING);
    }

    @Test
    public void neverImmuneStillMeetsTheQuota() {
        NearMiss nearMiss = new NearMiss();
        int misses = 0;
        for (int attack = 0; attack < NearMiss.WINDOW - 1; attack++) {
            if (nearMiss.due(7, false)) {
                nearMiss.missed();
                misses++;
            }
            nearMiss.attacked();
        }
        assertTrue(misses >= NearMiss.REQUIRED);
    }
}
