package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static atlas.module.modules.CrabSprintPlan.APPLY;
import static atlas.module.modules.CrabSprintPlan.NONE;
import static atlas.module.modules.CrabSprintPlan.RELEASE;
import static org.junit.Assert.assertEquals;

public class CrabSprintPlanTest {
    private final Random random = new Random(7);

    private int[] run(CrabSprintPlan plan, int ticks, int hitAt, int hurtAt, boolean damaged) {
        int[] out = new int[ticks];
        for (int t = 0; t < ticks; t++) {
            out[t] = plan.tick(t * 0.05, t == hitAt, t == hurtAt ? 10 : 0, 4.0F, 2.0F, false, damaged, true, this.random);
        }
        return out;
    }

    @Test
    public void delayThenStopThenRelease() {
        int[] steps = run(new CrabSprintPlan(), 12, 0, -1, false);
        /* hit on tick 0: 4 ticks of delay, held for 2, given back */
        int[] expected = {NONE, NONE, NONE, NONE, APPLY, APPLY, RELEASE, NONE, NONE, NONE, NONE, NONE};
        for (int t = 0; t < expected.length; t++) {
            assertEquals("tick " + t, expected[t], steps[t]);
        }
    }

    @Test
    public void atMostOneResetEveryHalfSecond() {
        CrabSprintPlan plan = new CrabSprintPlan();
        int resets = 0;
        for (int t = 0; t < 40; t++) {
            /* hitting every tick for two seconds */
            if (plan.tick(t * 0.05, true, 0, 0.0F, 1.0F, false, false, true, this.random) == RELEASE) {
                resets++;
            }
        }
        assertEquals(4, resets);
    }

    @Test
    public void damagedWaitsForAHitOnThePlayer() {
        int[] steps = run(new CrabSprintPlan(), 20, 0, 8, true);
        for (int t = 0; t < 8; t++) {
            assertEquals("tick " + t, NONE, steps[t]);
        }
        /* hurt on tick 8: armed since tick 0, so the delay starts there */
        assertEquals(APPLY, steps[12]);
        assertEquals(RELEASE, steps[14]);
    }

    @Test
    public void noWeaponNoReset() {
        CrabSprintPlan plan = new CrabSprintPlan();
        for (int t = 0; t < 20; t++) {
            assertEquals(NONE, plan.tick(t * 0.05, t == 0, 0, 4.0F, 2.0F, false, false, false, this.random));
        }
    }

    @Test
    public void stoppingMidActionReleases() {
        CrabSprintPlan plan = new CrabSprintPlan();
        run(plan, 5, 0, -1, false);
        assertEquals(RELEASE, plan.stop());
        assertEquals(NONE, plan.stop());
    }

    @Test
    public void randomizeKeepsTheTimesInCrabsBounds() {
        CrabSprintPlan plan = new CrabSprintPlan();
        double now = 0.0;
        for (int round = 0; round < 300; round++) {
            int start = -1;
            int applied = -1;
            int released = -1;
            for (int t = 0; t < 40; t++) {
                now += 0.05;
                int step = plan.tick(now, t == 0, 0, 4.0F, 2.0F, true, false, true, this.random);
                if (t == 0) {
                    start = t;
                }
                if (step == APPLY && applied < 0) {
                    applied = t;
                }
                if (step == RELEASE) {
                    released = t;
                    break;
                }
            }
            int delay = applied - start;
            int hold = released - applied;
            /* delay 4 + 0..2 (and up to a tick of carry-over), stop 2 - 0..1 + sometimes 1..2 */
            org.junit.Assert.assertTrue("delay " + delay, delay >= 3 && delay <= 8);
            org.junit.Assert.assertTrue("hold " + hold, hold >= 1 && hold <= 5);
            now += 1.0;
        }
    }
}
