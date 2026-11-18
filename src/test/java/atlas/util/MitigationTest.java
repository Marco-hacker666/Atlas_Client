package atlas.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class MitigationTest {
    private static final long WINDOW = 500L;

    @Test
    public void vanillaDamageMatchesTheGame() {
        assertEquals(8.0F, DamageModel.expected(7.0F, 0, 0, 0, false, 0, 0)[0], 1.0E-4F);

        assertEquals(11.75F, DamageModel.expected(6.0F, 1, 0, 0, true, 0, 0)[0], 1.0E-4F);

        assertEquals(3.2F, DamageModel.expected(7.0F, 0, 0, 0, false, 15, 0)[0], 1.0E-4F);

        assertEquals(18.4F, DamageModel.expected(7.0F, 0, 1, 0, false, 0, 0)[0], 1.0E-3F);
    }

    @Test
    public void protectionIsARange() {
        assertEquals(2, DamageModel.protectionPoints(2));
        assertEquals(0, DamageModel.protectionPoints(0));
        float[] range = DamageModel.expected(7.0F, 0, 0, 0, false, 0, 8);

        assertEquals(8.0F * 17 / 25.0F, range[0], 1.0E-4F);
        assertEquals(8.0F * 21 / 25.0F, range[1], 1.0E-4F);
    }

    private static DamageWatch.Hit hit(DamageWatch watch, long[] now, float[] health, float expected, float dealt) {
        DamageWatch.Hit result = null;
        now[0] += 50L;
        watch.tick(1, health[0], 0, now[0], WINDOW);
        watch.attack(1, now[0], expected);
        now[0] += 50L;
        watch.tick(1, health[0], 10, now[0], WINDOW);
        health[0] -= dealt;

        for (int t = 9; t >= -20; t--) {
            now[0] += 50L;
            DamageWatch.Hit h = watch.tick(1, health[0], Math.max(0, t), now[0], WINDOW);
            if (h != null) {
                result = h;
            }
        }
        health[0] = 20.0F;
        return result;
    }

    @Test
    public void hitsFarUnderVanillaAreMitigation() {
        DamageWatch watch = new DamageWatch();
        long[] now = {10_000L};
        float[] health = {20.0F};
        hit(watch, now, health, 6.0F, 6.5F);
        assertFalse(watch.mitigation(now[0], 20_000L).active);
        hit(watch, now, health, 6.0F, 1.5F);
        hit(watch, now, health, 6.0F, 2.0F);
        assertFalse("two low of three", watch.mitigation(now[0], 20_000L).active);
        hit(watch, now, health, 6.0F, 1.0F);
        DamageWatch.Mitigation m = watch.mitigation(now[0], 20_000L);
        assertTrue(m.active);
        assertEquals(3, m.low);
        assertEquals(4, m.of);
        assertEquals(1.5F, m.dealt, 1.0E-4F);
        assertFalse("too old", watch.mitigation(now[0] + 30_000L, 20_000L).active);
    }

    @Test
    public void wholeNumberHealthNeedsAWiderMargin() {
        DamageWatch watch = new DamageWatch();
        watch.setPrecision(1.0F);
        long[] now = {10_000L};
        float[] health = {20.0F};

        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, 6.0F, 3.6F);
        }
        assertFalse(watch.mitigation(now[0], 20_000L).active);
        watch.setPrecision(0.0F);
        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, 6.0F, 3.6F);
        }
        assertTrue(watch.mitigation(now[0], 20_000L).active);
    }

    @Test
    public void halfTheDamageIsMitigationEvenWithWholeNumberHealth() {
        DamageWatch watch = new DamageWatch();
        watch.setPrecision(1.0F);
        long[] now = {10_000L};
        float[] health = {20.0F};
        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, 4.0F, 2.0F);
        }
        assertTrue(watch.mitigation(now[0], 20_000L).active);
    }

    @Test
    public void ordinaryHitsAreNotMitigation() {
        DamageWatch watch = new DamageWatch();
        watch.setPrecision(1.0F);
        long[] now = {10_000L};
        float[] health = {20.0F};
        float[] dealt = {4.0F, 3.0F, 4.0F, 3.0F};
        for (float d : dealt) {
            hit(watch, now, health, 4.0F, d);
        }
        assertFalse(watch.mitigation(now[0], 20_000L).active);
    }

    @Test
    public void unknownOrTinyExpectationsAreIgnored() {
        DamageWatch watch = new DamageWatch();
        long[] now = {10_000L};
        float[] health = {20.0F};
        for (int i = 0; i < 4; i++) {
            hit(watch, now, health, Float.NaN, 0.5F);
            hit(watch, now, health, 1.0F, 0.1F);
        }
        assertFalse(watch.mitigation(now[0], 20_000L).active);
        assertEquals(0, watch.mitigation(now[0], 20_000L).of);
    }
}
