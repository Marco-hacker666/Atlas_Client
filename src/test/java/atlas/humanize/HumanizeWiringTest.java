package atlas.humanize;

import atlas.lb.aiming.AngleSmooth;
import atlas.lb.aiming.AuraRotations;
import atlas.lb.aiming.Rotation;
import org.junit.After;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** What the LiquidBounce aura uses from humanization (2026-10-25). */
public class HumanizeWiringTest {

    @After
    public void unpublish() {
        Humanizer.publish(null, null);
    }

    @Test
    public void speedScaleShortensOrLengthensTheTurn() {
        AuraRotations rotations = new AuraRotations();
        AngleSmooth.Linear linear = new AngleSmooth.Linear();
        linear.horizontalMin = linear.horizontalMax = 20.0F;
        linear.verticalMin = linear.verticalMax = 20.0F;
        rotations.smooth = linear;
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        rotations.speedScale = 0.5F;
        assertEquals(10.0F, rotations.update(new Rotation(0.0F, 0.0F), 0.0).yaw, 1e-4);
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        rotations.speedScale = 1.5F;
        assertEquals(10.0F + 30.0F, rotations.update(new Rotation(0.0F, 0.0F), 0.0).yaw, 1e-4);
    }

    @Test
    public void aFightComesToTheHitRateThroughTheHumanizer() {
        boolean[] applies = new boolean[Humanizer.CONSUMERS];
        applies[Humanizer.CONSUMER_LIQUIDBOUNCE] = true;
        HumanProfile p = HumanProfile.preset(HumanProfile.PRESET_LEGIT);
        p.maxHitsPerSecond = 0.0F;
        Humanizer.publish(p, applies);
        Humanizer humanizer = new Humanizer(Humanizer.CONSUMER_LIQUIDBOUNCE, new Random(21L));
        Accuracy.Situation s = new Accuracy.Situation();
        s.distance = 2.0;
        long now = 0L;
        int clicks = 0;
        int hits = 0;
        int skips = 0;
        for (int i = 0; i < 5000; i++) {
            now += 80L;
            Humanizer.Verdict v = humanizer.click(s, now);
            if (v.land) {
                // one in ten meant hits misses anyway (the look was not on the box)
                if (i % 10 == 0) {
                    humanizer.unlucky(now);
                } else {
                    humanizer.landed(now);
                    hits++;
                }
                clicks++;
            } else {
                humanizer.missed(v.miss, now);
                if (v.miss == Misses.Style.SKIP) {
                    skips++;
                } else {
                    clicks++;
                }
            }
        }
        assertEquals(0.40, hits / (double) clicks, 0.035);
        assertTrue("skips " + skips, skips > 0);
        assertTrue(humanizer.stats().line(now).startsWith("hit "));
    }

    @Test
    public void reactionHoldsTheFirstTicksOfANewTarget() {
        boolean[] applies = new boolean[Humanizer.CONSUMERS];
        applies[Humanizer.CONSUMER_LIQUIDBOUNCE] = true;
        Humanizer.publish(new HumanProfile(), applies);
        Humanizer humanizer = new Humanizer(Humanizer.CONSUMER_LIQUIDBOUNCE, new Random(3L));
        int waited = 0;
        long now = 1000L;
        while (!humanizer.reacted(42, now)) {
            now += 50L;
            waited++;
        }
        assertTrue("ticks waited " + waited, waited >= 3 && waited <= 7); // 180-320 ms
    }
}
