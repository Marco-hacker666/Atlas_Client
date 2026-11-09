package atlas.util;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DriftNoiseTest {

    @Test
    public void staysBetweenZeroAndOne() {
        DriftNoise noise = new DriftNoise(5.0);
        Random random = new Random(1);
        for (double t = 0.0; t < 600.0; t += 0.05) {
            float v = noise.value(t, random);
            assertTrue("value " + v + " at " + t, v >= 0.0F && v < 1.0F);
        }
    }

    @Test
    public void driftsInsteadOfJumping() {
        DriftNoise noise = new DriftNoise(5.0);
        Random random = new Random(2);
        float last = noise.value(0.0, random);
        for (double t = 0.05; t < 600.0; t += 0.05) {
            float v = noise.value(t, random);
            /* a tick moves it at most 1/100 of a 5 s slide, plus nothing at the hand-over */
            assertTrue("jump of " + Math.abs(v - last) + " at " + t, Math.abs(v - last) <= 0.0105F);
            last = v;
        }
    }

    @Test
    public void reachesEachTargetAndMovesOnByAtLeastTheMinimumStep() {
        DriftNoise noise = new DriftNoise(1.0);
        Random random = new Random(3);
        float start = noise.value(0.0, random);
        float end = noise.value(1.0, random);
        assertTrue(Math.abs(end - start) >= DriftNoise.MIN_STEP);
        /* the next slide starts where the last one ended */
        assertEquals(end, noise.value(1.0000001, random), 1.0E-4F);
    }
}
