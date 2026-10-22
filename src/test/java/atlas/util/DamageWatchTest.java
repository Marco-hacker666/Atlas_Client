package atlas.util;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Damage per landed hit, and runs of low ones (2026-09-28). */
public class DamageWatchTest {

    private static final long WINDOW = 500L;

    /** Clock and health of one simulated fight. */
    private static final class Fight {
        final DamageWatch watch = new DamageWatch();
        long now = 10_000L;
        float health = 20.0F;
        DamageWatch.Hit lastHit;
        DamageWatch.Verdict lastVerdict;

        /** One tick of target id at the current health and hurt time. */
        void tick(int id, int hurtTime) {
            this.now += 50L;
            DamageWatch.Hit hit = this.watch.tick(id, this.health, hurtTime, this.now, WINDOW);
            if (hit != null) {
                this.lastHit = hit;
                DamageWatch.Verdict v = this.watch.verdict(hit);
                if (v != null) {
                    this.lastVerdict = v;
                }
            }
        }

        /** Attack, flinch a tick later, health follows a tick after that, then settle. */
        DamageWatch.Hit hit(int id, float damage) {
            this.lastHit = null;
            tick(id, 0);
            this.watch.attack(id, this.now);
            tick(id, 10);
            this.health -= damage;
            for (int t = 9; t >= 0; t--) {
                tick(id, t);
            }
            return this.lastHit;
        }
    }

    @Test
    public void twoHitsInOneScoreWindowAreTwoHitsNotOneBigOne() {
        // 2026-10-15: tab health settles over 24 ticks, room for a combo's next hit.
        Fight f = new Fight();
        f.watch.setPrecision(1.0F);
        f.tick(1, 0);
        f.watch.attack(1, f.now);
        f.tick(1, 10);
        f.health -= 4.0F;
        for (int t = 9; t >= 0; t--) {
            f.tick(1, t);
        }
        f.watch.attack(1, f.now);
        f.tick(1, 10);
        f.health -= 4.0F;
        for (int t = 9; t >= 0; t--) {
            f.tick(1, t);
        }
        for (int i = 0; i < 6; i++) {
            f.tick(1, 0);
        }
        DamageWatch.Hit hit = f.lastHit;
        assertNotNull(hit);
        assertEquals(2, hit.hits);
        assertEquals(8.0F, hit.total, 1.0E-5F);
        assertEquals(4.0F, hit.dealt, 1.0E-5F);
    }

    @Test
    public void aLandedHitIsTheHealthItTookOff() {
        Fight f = new Fight();
        DamageWatch.Hit hit = f.hit(1, 1.5F);
        assertNotNull(hit);
        assertEquals(1.5F, hit.dealt, 1.0E-5F);
        assertTrue("no usual yet", Float.isNaN(hit.usual));
        assertEquals(1, f.watch.resolved());
    }

    @Test
    public void aFlinchWithoutOurAttackIsSomeoneElses() {
        Fight f = new Fight();
        f.tick(1, 0);
        f.tick(1, 10);
        f.health -= 3.0F;
        for (int t = 9; t >= 0; t--) {
            f.tick(1, t);
        }
        assertNull(f.lastHit);
        assertEquals(0, f.watch.resolved());
    }

    @Test
    public void aFlinchLongAfterTheAttackIsNotItsAnswer() {
        Fight f = new Fight();
        f.tick(1, 0);
        f.watch.attack(1, f.now);
        for (int i = 0; i < 12; i++) {
            f.tick(1, 0);
        }
        f.tick(1, 10);
        f.health -= 2.0F;
        for (int t = 9; t >= 0; t--) {
            f.tick(1, t);
        }
        assertNull(f.lastHit);
    }

    @Test
    public void threeLowHitsInARowAreCalledOnce() {
        Fight f = new Fight();
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            f.hit(1, 2.0F);
        }
        assertNull(f.lastVerdict);
        f.health = 20.0F;
        f.hit(1, 0.4F);
        f.hit(1, 0.3F);
        assertNull("two are not a run", f.lastVerdict);
        f.hit(1, 0.5F);
        assertNotNull(f.lastVerdict);
        assertEquals(3, f.lastVerdict.hits);
        assertEquals(0.4F, f.lastVerdict.dealt, 1.0E-4F);
        assertEquals(2.0F, f.lastVerdict.usual, 1.0E-4F);
        f.lastVerdict = null;
        f.hit(1, 0.4F);
        assertNull("the run was reported; a new one starts", f.lastVerdict);
    }

    @Test
    public void aNormalHitBreaksTheRun() {
        Fight f = new Fight();
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            f.hit(1, 2.0F);
        }
        f.health = 20.0F;
        f.hit(1, 0.3F);
        f.hit(1, 0.3F);
        f.hit(1, 1.9F);
        f.hit(1, 0.3F);
        assertNull(f.lastVerdict);
    }

    @Test
    public void aHitThatLandsForNothingIsLow() {
        Fight f = new Fight();
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            f.hit(1, 2.0F);
        }
        f.health = 20.0F;
        f.hit(1, 0.0F);
        f.hit(1, 0.0F);
        f.hit(1, 0.0F);
        assertNotNull(f.lastVerdict);
        assertEquals(0.0F, f.lastVerdict.dealt, 0.0F);
    }

    @Test
    public void aServerThatHidesHealthIsNotAccused() {
        Fight f = new Fight();
        for (int i = 0; i < 12; i++) {
            f.hit(1, 0.0F);
        }
        assertTrue(f.watch.healthHidden());
        assertNull(f.lastVerdict);
    }

    @Test
    public void theKillingHitIsNotCounted() {
        Fight f = new Fight();
        f.health = 1.0F;
        assertNull(f.hit(1, 1.0F));
        assertEquals(0, f.watch.resolved());
    }

    @Test
    public void eachTargetIsJudgedByItsOwnHitsOnceItHasEnough() {
        Fight f = new Fight();
        /* A lightly armoured target first: big hits set the session's usual. */
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            f.hit(1, 4.0F);
        }
        /* A heavily armoured one: its own hits are small, and that is normal for it. */
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            f.hit(2, 1.0F);
        }
        f.lastVerdict = null;
        f.health = 20.0F;
        f.hit(2, 0.9F);
        f.hit(2, 1.1F);
        f.hit(2, 1.0F);
        assertNull("normal for this target", f.lastVerdict);
    }

    @Test
    public void aNewServerStartsOver() {
        Fight f = new Fight();
        for (int i = 0; i < 12; i++) {
            f.hit(1, 0.0F);
        }
        assertTrue(f.watch.healthHidden());
        f.watch.reset();
        assertFalse(f.watch.healthHidden());
        assertEquals(0, f.watch.resolved());
        assertTrue(f.watch.watched().isEmpty());
    }

    @Test
    public void fourDroppedHitsInARowAreCalledOncePerRun() {
        DamageWatch watch = new DamageWatch();
        assertEquals(0, watch.dropped(1000L));
        assertEquals(0, watch.dropped(1100L));
        assertEquals(0, watch.dropped(1200L));
        assertEquals(DamageWatch.DROP_STREAK, watch.dropped(1300L));
        assertEquals("the same run is not called again", 0, watch.dropped(1400L));
        watch.landed();
        assertEquals(0, watch.dropped(1500L));
    }

    @Test
    public void aLandedHitOrALongGapBreaksADroppedRun() {
        DamageWatch watch = new DamageWatch();
        watch.dropped(1000L);
        watch.dropped(1100L);
        watch.dropped(1200L);
        watch.landed();
        assertEquals(0, watch.dropped(1300L));
        DamageWatch gap = new DamageWatch();
        gap.dropped(1000L);
        gap.dropped(1100L);
        gap.dropped(1200L);
        assertEquals("over 3 s later: a new run", 0, gap.dropped(5000L));
    }

    /** One landed hit made with a given weapon (basis) and known minimum (NaN for none). */
    private static DamageWatch.Hit hitWith(Fight f, int id, float damage, float expected, String basis) {
        f.lastHit = null;
        f.tick(id, 0);
        f.watch.attack(id, f.now, expected, basis);
        f.tick(id, 10);
        f.health -= damage;
        for (int t = 9; t >= 0 && f.lastHit == null; t--) {
            f.tick(id, t);
        }
        return f.lastHit;
    }

    @Test
    public void changingToAWeakerWeaponIsNotLowDamage() {
        Fight f = new Fight();
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            hitWith(f, 1, 7.0F, Float.NaN, "diamond_sword");
        }
        f.health = 20.0F;
        hitWith(f, 1, 1.0F, Float.NaN, "hand");
        hitWith(f, 1, 1.0F, Float.NaN, "hand");
        hitWith(f, 1, 1.0F, Float.NaN, "hand");
        assertNull("a bare hand is judged against bare-hand hits only", f.lastVerdict);
    }

    @Test
    public void aBetterArmouredOpponentIsNotLowDamage() {
        Fight f = new Fight();
        /* Hits worth about 1.2x their vanilla minimum, on a light target... */
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            hitWith(f, 1, 6.0F, 5.0F, "iron_sword");
        }
        /* ...then a heavy one: much less health, but the same share of its minimum. */
        f.health = 20.0F;
        hitWith(f, 2, 2.4F, 2.0F, "iron_sword");
        hitWith(f, 2, 2.4F, 2.0F, "iron_sword");
        hitWith(f, 2, 2.4F, 2.0F, "iron_sword");
        assertNull("judged against what vanilla lets the hit do", f.lastVerdict);
    }

    @Test
    public void aRealCutIsStillCalledWithKnownMinimums() {
        Fight f = new Fight();
        for (int i = 0; i < 6; i++) {
            f.health = 20.0F;
            hitWith(f, 1, 6.0F, 5.0F, "iron_sword");
        }
        f.health = 20.0F;
        hitWith(f, 1, 0.5F, 5.0F, "iron_sword");
        hitWith(f, 1, 0.5F, 5.0F, "iron_sword");
        hitWith(f, 1, 0.5F, 5.0F, "iron_sword");
        assertNotNull(f.lastVerdict);
        assertEquals(6.0F, f.lastVerdict.usual, 1.0E-3F);
    }

    @Test
    public void blockingAndResistanceLowerTheMinimum() {
        float plain = DamageModel.expected(6.0F, 0, 0, 0, false, 0, 0)[0];
        float blocked = DamageModel.expected(6.0F, 0, 0, 0, false, 0, 0, true, 0)[0];
        float resisted = DamageModel.expected(6.0F, 0, 0, 0, false, 0, 0, false, 1)[0];
        assertEquals(7.0F, plain, 1.0E-4F);
        assertEquals(4.0F, blocked, 1.0E-4F);
        assertEquals(5.6F, resisted, 1.0E-4F);
    }

    @Test
    public void medianOfOddAndEven() {
        assertEquals(2.0F, DamageWatch.median(new ArrayDeque<Float>(Arrays.asList(3.0F, 1.0F, 2.0F))), 0.0F);
        assertEquals(2.5F, DamageWatch.median(new ArrayDeque<Float>(Arrays.asList(4.0F, 1.0F, 2.0F, 3.0F))), 0.0F);
    }
}
