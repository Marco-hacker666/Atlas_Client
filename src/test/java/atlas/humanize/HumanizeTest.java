package atlas.humanize;

import org.junit.After;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The humanization engine (2026-10-25). */
public class HumanizeTest {

    @After
    public void unpublish() {
        Humanizer.publish(null, null);
    }

    private static Accuracy.Situation close() {
        Accuracy.Situation s = new Accuracy.Situation();
        s.distance = 2.0;
        return s;
    }

    /** Clicks every 80 ms for {@code clicks}; the share that landed. */
    private static double run(Accuracy accuracy, HumanProfile p, Accuracy.Situation s, int clicks) {
        long now = 0L;
        int hits = 0;
        for (int i = 0; i < clicks; i++) {
            now += 80L;
            boolean hit = accuracy.decide(p, s, now);
            accuracy.record(hit, now);
            if (hit) {
                hits++;
            }
        }
        return hits / (double) clicks;
    }

    @Test
    public void theHitRateComesToTheAim() {
        for (int preset : new int[]{HumanProfile.PRESET_LEGIT, HumanProfile.PRESET_GOOD, HumanProfile.PRESET_TRYHARD}) {
            HumanProfile p = HumanProfile.preset(preset);
            p.maxHitsPerSecond = 0.0F;
            double rate = run(new Accuracy(new Random(42L + preset)), p, close(), 6000);
            assertEquals("preset " + preset, p.hitRate / 100.0, rate, 0.03);
        }
    }

    @Test
    public void shortRunsStillVary() {
        HumanProfile p = HumanProfile.preset(HumanProfile.PRESET_LEGIT);
        p.maxHitsPerSecond = 0.0F;
        Accuracy accuracy = new Accuracy(new Random(5L));
        double low = 1.0;
        double high = 0.0;
        long now = 0L;
        for (int block = 0; block < 60; block++) {
            int hits = 0;
            for (int i = 0; i < 20; i++) {
                now += 80L;
                boolean hit = accuracy.decide(p, close(), now);
                accuracy.record(hit, now);
                hits += hit ? 1 : 0;
            }
            low = Math.min(low, hits / 20.0);
            high = Math.max(high, hits / 20.0);
        }
        assertTrue("20-click blocks " + low + ".." + high, high - low >= 0.25);
    }

    @Test
    public void distanceAndBeingHitLowerTheRate() {
        HumanProfile p = HumanProfile.preset(HumanProfile.PRESET_LEGIT);
        p.maxHitsPerSecond = 0.0F;
        p.hitRateSpread = 0.0F;
        p.streaks = false;
        Accuracy.Situation far = new Accuracy.Situation();
        far.distance = 5.0;
        double nearRate = run(new Accuracy(new Random(1L)), p, close(), 4000);
        double farRate = run(new Accuracy(new Random(1L)), p, far, 4000);
        assertEquals(0.40 - 2.5 * 0.08, farRate, 0.04);
        assertTrue(nearRate > farRate + 0.1);
        Accuracy.Situation hurt = close();
        hurt.ticksSinceHurt = 0;
        assertEquals(20.0, new Accuracy(new Random(1L)).situationPenalty(p, hurt, 0L), 1e-9);
    }

    @Test
    public void theRateGuardCapsHitsASecond() {
        HumanProfile p = HumanProfile.preset(HumanProfile.PRESET_TRYHARD);
        p.hitRate = 95.0F;
        p.maxHitsPerSecond = 2.0F;
        Accuracy accuracy = new Accuracy(new Random(3L));
        long now = 0L;
        int inSecond = 0;
        for (int i = 0; i < 20; i++) {
            now += 50L;
            boolean hit = accuracy.decide(p, close(), now);
            accuracy.record(hit, now);
            inSecond += hit ? 1 : 0;
        }
        assertTrue("hits in a second " + inSecond, inSecond <= 2);
    }

    @Test
    public void theFloorForcesAHit() {
        HumanProfile p = HumanProfile.preset(HumanProfile.PRESET_LEGIT);
        p.hitRate = 0.0F;
        p.hitRateSpread = 0.0F;
        p.minHitsPerSecond = 1.0F;
        p.maxHitsPerSecond = 0.0F;
        Accuracy accuracy = new Accuracy(new Random(4L));
        long now = 0L;
        int hits = 0;
        for (int i = 0; i < 100; i++) {
            now += 100L;
            boolean hit = accuracy.decide(p, close(), now);
            accuracy.record(hit, now);
            hits += hit ? 1 : 0;
        }
        assertTrue("forced hits over 10 s: " + hits, hits >= 9);
    }

    @Test
    public void missStylesFollowTheirWeights() {
        HumanProfile p = new HumanProfile();
        Random random = new Random(9L);
        int aim = 0;
        int swing = 0;
        int skip = 0;
        for (int i = 0; i < 10000; i++) {
            Misses.Style s = Misses.pick(p, random);
            if (s == Misses.Style.AIM_OFF) {
                aim++;
            } else if (s == Misses.Style.SWING_ONLY) {
                swing++;
            } else {
                skip++;
            }
        }
        assertEquals(0.70, aim / 10000.0, 0.02);
        assertEquals(0.20, swing / 10000.0, 0.02);
        assertEquals(0.10, skip / 10000.0, 0.02);
        p.missAimOff = 0;
        p.missSwingOnly = 0;
        p.missSkip = 0;
        assertEquals(Misses.Style.AIM_OFF, Misses.pick(p, random));
    }

    @Test
    public void anAimedMissPassesTheBoxEdge() {
        HumanProfile p = new HumanProfile();
        Random random = new Random(2L);
        double distance = 3.0;
        for (int i = 0; i < 500; i++) {
            float[] off = Misses.aimOffOffset(p, distance, 0.4, 1.0, random);
            double yaw = Math.abs(off[0]);
            double pitch = Math.abs(off[1]);
            assertTrue((yaw > 0) != (pitch > 0));
            if (yaw > 0) {
                double past = Math.tan(Math.toRadians(yaw)) * distance - 0.4;
                assertTrue("past " + past, past >= p.aimOffMin - 1e-6 && past <= p.aimOffMax + 1e-6);
            } else {
                double past = Math.tan(Math.toRadians(pitch)) * distance - 1.0;
                assertTrue("past " + past, past >= p.aimOffMin - 1e-6 && past <= p.aimOffMax + 1e-6);
            }
        }
    }

    @Test
    public void reactionsByKind() {
        HumanProfile p = new HumanProfile();
        Reaction reaction = new Reaction(new Random(6L));
        assertFalse(reaction.ready(7, 0L, p));
        assertEquals(Reaction.KIND_NEW, reaction.lastKind());
        long first = reaction.lastDrawn();
        assertTrue(first >= 180 && first <= 320);
        assertTrue(reaction.ready(7, first, p));
        reaction.ready(Integer.MIN_VALUE, 1000L, p); // lost
        reaction.ready(7, 2000L, p); // back within 3 s
        assertEquals(Reaction.KIND_REACQUIRE, reaction.lastKind());
        assertTrue(reaction.lastDrawn() >= 120 && reaction.lastDrawn() <= 220);
        reaction.ready(8, 3000L, p);
        assertEquals(Reaction.KIND_SWITCH, reaction.lastKind());
        reaction.ready(Integer.MIN_VALUE, 4000L, p);
        reaction.ready(9, 9000L, p);
        assertEquals(Reaction.KIND_NEW, reaction.lastKind());
        for (int i = 0; i < 1000; i++) {
            long ms = Reaction.draw(100, 300, HumanProfile.SHAPE_LOG_NORMAL, new Random(i));
            assertTrue(ms >= 100 && ms <= 300);
        }
    }

    @Test
    public void aimErrorTremblesOvershootsAndLapses() {
        HumanProfile p = new HumanProfile();
        p.overshootChance = 100.0F;
        AimError error = new AimError(new Random(8L));
        AimError.Adjust first = error.tick(p, 60.0F, 0.0F, 0.0F);
        assertTrue(first.overshot);
        assertTrue("overshoot " + first.yaw, first.yaw >= 60 * 0.05 - 2.0 && first.yaw <= 60 * 0.15 + 2.0);
        AimError.Adjust next = error.tick(p, 50.0F, 0.0F, 0.0F);
        assertFalse(next.overshot); // only at the start of a turn
        double sum = 0;
        int lapses = 0;
        HumanProfile calm = new HumanProfile();
        calm.overshootChance = 0.0F;
        calm.undershootChance = 0.0F;
        calm.lapseChance = 20.0F;
        AimError still = new AimError(new Random(10L));
        boolean was = false;
        for (int i = 0; i < 20000; i++) {
            AimError.Adjust a = still.tick(calm, 0.0F, 0.0F, 0.0F);
            sum += a.yaw * a.yaw;
            assertTrue(a.speed >= calm.speedMin && a.speed <= calm.speedMax);
            if (a.lapsing && !was) {
                lapses++;
            }
            was = a.lapsing;
        }
        double rms = Math.sqrt(sum / 20000);
        assertTrue("tremor rms " + rms, rms > 0.3 && rms < 1.0);
        // 20 % a second over 1000 s (less the ticks spent lapsing): about 150-200
        assertTrue("lapses " + lapses, lapses > 100 && lapses < 260);
    }

    @Test
    public void offOrNotChosenMeansNoProfile() {
        Humanizer humanizer = new Humanizer(Humanizer.CONSUMER_LIQUIDBOUNCE, new Random(1L));
        assertNull(humanizer.profile());
        assertNull(humanizer.click(close(), 0L));
        assertTrue(humanizer.reacted(5, 0L));
        boolean[] applies = new boolean[Humanizer.CONSUMERS];
        applies[Humanizer.CONSUMER_AIMASSIST] = true;
        Humanizer.publish(new HumanProfile(), applies);
        assertNull(humanizer.profile());
        applies[Humanizer.CONSUMER_LIQUIDBOUNCE] = true;
        Humanizer.publish(new HumanProfile(), applies);
        assertNotNull(humanizer.profile());
        assertNotNull(humanizer.click(close(), 0L));
    }

    @Test
    public void statsCountClicksNotSkips() {
        HumanStats stats = new HumanStats();
        stats.hit(0L);
        stats.hit(10L);
        stats.miss(Misses.Style.AIM_OFF, 20L);
        stats.miss(Misses.Style.SKIP, 30L);
        stats.unlucky(40L);
        assertEquals(0.5, stats.hitRate(50L), 1e-9);
        assertTrue(stats.line(50L).startsWith("hit 50% (2/4)"));
        assertEquals(-1.0, stats.hitRate(60000L), 0.0);
    }

    @Test
    public void presetsDiffer() {
        assertEquals(40.0F, HumanProfile.preset(HumanProfile.PRESET_LEGIT).hitRate, 0.0F);
        assertEquals(55.0F, HumanProfile.preset(HumanProfile.PRESET_GOOD).hitRate, 0.0F);
        assertEquals(70.0F, HumanProfile.preset(HumanProfile.PRESET_TRYHARD).hitRate, 0.0F);
        assertTrue(HumanProfile.preset(HumanProfile.PRESET_TRYHARD).reactionNewMax
                < HumanProfile.preset(HumanProfile.PRESET_LEGIT).reactionNewMax);
    }
}
