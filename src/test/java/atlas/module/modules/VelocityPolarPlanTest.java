package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Velocity Polar's decisions (2026-10-26). */
public class VelocityPolarPlanTest {

    private static VelocityPolarPlan always() {
        VelocityPolarPlan p = new VelocityPolarPlan();
        p.counterChance = 100;
        p.jumpChance = 100;
        p.bothChance = 100;
        p.maxRate = 100;
        p.maxStreak = 0;
        p.minIntervalMs = 0L;
        return p;
    }

    @Test
    public void nothingToDoIsNoAnswer() {
        VelocityPolarPlan p = always();
        assertFalse(p.decide(0L, false, false, new Random(1L)).any());
        assertEquals(0, p.answeredInWindow());
    }

    @Test
    public void onlyWhatIsPossibleIsDone() {
        VelocityPolarPlan p = always();
        VelocityPolarPlan.Decision d = p.decide(0L, true, false, new Random(1L));
        assertTrue(d.counter);
        assertFalse(d.jump);
        d = p.decide(1000L, false, true, new Random(1L));
        assertFalse(d.counter);
        assertTrue(d.jump);
        assertEquals(-1, d.counterTick);
    }

    @Test
    public void reactionStaysInItsRange() {
        VelocityPolarPlan p = always();
        p.reactMin = 1;
        p.reactMax = 2;
        Random r = new Random(5L);
        for (int i = 0; i < 200; i++) {
            VelocityPolarPlan.Decision d = p.decide(i * 1000L, true, false, r);
            assertTrue(d.counterTick >= 1 && d.counterTick <= 2);
        }
    }

    @Test
    public void bothChanceUsuallyPicksOne() {
        VelocityPolarPlan p = always();
        p.bothChance = 0;
        Random r = new Random(9L);
        for (int i = 0; i < 200; i++) {
            VelocityPolarPlan.Decision d = p.decide(i * 1000L, true, true, r);
            assertTrue(d.counter ^ d.jump);
        }
    }

    @Test
    public void maxRateCapsTheLastTen() {
        VelocityPolarPlan p = always();
        p.maxRate = 50;
        Random r = new Random(3L);
        int answered = 0;
        for (int i = 0; i < 100; i++) {
            if (p.decide(i * 1000L, true, true, r).any()) {
                answered++;
            }
            assertTrue("window " + p.answeredInWindow(), p.answeredInWindow() <= 5);
        }
        assertTrue("answered " + answered, answered >= 45 && answered <= 55);
    }

    @Test
    public void minIntervalSpacesAnswers() {
        VelocityPolarPlan p = always();
        p.minIntervalMs = 500L;
        Random r = new Random(4L);
        assertTrue(p.decide(0L, true, false, r).any());
        assertFalse(p.decide(300L, true, false, r).any());
        assertTrue(p.decide(600L, true, false, r).any());
    }

    @Test
    public void runsAreBroken() {
        VelocityPolarPlan p = always();
        p.maxStreak = 3;
        Random r = new Random(7L);
        int run = 0;
        int longest = 0;
        for (int i = 0; i < 300; i++) {
            if (p.decide(i * 1000L, true, true, r).any()) {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        assertTrue("longest " + longest, longest <= 3 && longest >= 2);
    }

    @Test
    public void anAnswerThatDidNotHappenIsNotCounted() {
        VelocityPolarPlan p = always();
        assertTrue(p.decide(0L, true, false, new Random(2L)).any());
        assertEquals(1, p.answeredInWindow());
        p.didNotHappen();
        assertEquals(0, p.answeredInWindow());
    }
}
