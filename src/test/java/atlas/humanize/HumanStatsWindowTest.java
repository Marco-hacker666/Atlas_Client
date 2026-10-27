package atlas.humanize;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 2026-10-26 review fixes: aim counts share the 30 s window; reset starts the accuracy over. */
public class HumanStatsWindowTest {

    @Test
    public void overshootsAndLapsesLeaveWithTheWindow() {
        HumanStats stats = new HumanStats();
        AimError.Adjust adjust = new AimError.Adjust();
        adjust.overshot = true;
        stats.aim(adjust, 1000L);
        stats.lapse(1000L);
        assertTrue(stats.line(2000L), stats.line(2000L).endsWith("over 1 under 0 lapse 1"));
        assertTrue(stats.line(40000L), stats.line(40000L).endsWith("over 0 under 0 lapse 0"));
    }

    @Test
    public void resetStartsTheAccuracyOver() {
        Accuracy accuracy = new Accuracy(new Random(1L));
        HumanProfile p = new HumanProfile();
        Accuracy.Situation s = new Accuracy.Situation();
        s.distance = 2.0;
        for (int i = 0; i < 50; i++) {
            accuracy.record(accuracy.decide(p, s, i * 100L), i * 100L);
        }
        accuracy.reset();
        assertEquals(0.0, accuracy.lastAim(), 0.0);
    }
}
