package atlas.lb.clicking;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** LiquidBounce NextGen's ClickPlanTest, ported (2026-10-24). */
public class ClickPlanTest {

    private static ClickPlan humanPlan(int min, int max, int maxPerTick) {
        ClickPlan plan = new ClickPlan(new HumanClickTiming(), new Random(1337L));
        plan.setCps(min, max);
        plan.setMaxPerTick(maxPerTick);
        return plan;
    }

    /** Ticks {@code ticks} times, 50 ms apart, using every press; presses consumed per tick. */
    private static int[] run(ClickPlan plan, int ticks, IntConsumer onTick) {
        int[] consumed = new int[ticks];
        for (int tick = 0; tick < ticks; tick++) {
            plan.tick(tick * ClickPlan.TICK_MS);
            consumed[tick] = plan.clicksAt(0);
            onTick.accept(tick);
            plan.consume(() -> true, () -> true);
        }
        return consumed;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    @Test
    public void pressesAreBatchedIntoTheTickThatFollowsThem() {
        ClickPlan plan = new ClickPlan((recent, comboMs, min, max, random) -> 30L);
        plan.setMaxPerTick(2);
        int[] consumed = run(plan, 6, tick -> { });
        assertEquals("[1, 1, 2, 2, 1, 2]", Arrays.toString(consumed));
    }

    @Test
    public void lookaheadEqualsConsumption() {
        ClickPlan plan = humanPlan(8, 20, 2);
        int ticks = 2000;
        int ahead = 10;
        List<List<Integer>> predicted = new ArrayList<List<Integer>>();
        for (int i = 0; i < ticks + ahead; i++) {
            predicted.add(new ArrayList<Integer>());
        }
        int[] consumed = run(plan, ticks, tick -> {
            for (int n = 1; n <= ahead; n++) {
                predicted.get(tick + n).add(plan.clicksAt(n));
            }
        });
        for (int tick = 0; tick < ticks; tick++) {
            for (int prediction : predicted.get(tick)) {
                assertEquals("tick " + tick, consumed[tick], prediction);
            }
        }
    }

    @Test
    public void meanCpsStaysInsideTheRange() {
        int[][] ranges = {{8, 10}, {11, 14}, {16, 20}};
        for (int[] range : ranges) {
            int ticks = 20 * 60 * 10;
            double cps = sum(run(humanPlan(range[0], range[1], 2), ticks, tick -> { })) / (ticks / 20.0);
            assertTrue(cps + " outside " + range[0] + ".." + range[1], cps >= range[0] && cps <= range[1]);
        }
    }

    private static double cps(ClickPlan plan, int from, int until) {
        int clicks = 0;
        for (int tick = from; tick < until; tick++) {
            plan.tick(tick * ClickPlan.TICK_MS);
            clicks += plan.consume(() -> true, () -> true);
        }
        return clicks / ((until - from) / 20.0);
    }

    @Test
    public void settingsChangedMidComboApplyRightAway() {
        ClickPlan plan = humanPlan(11, 14, 1);
        cps(plan, 0, 200);
        plan.setCps(4, 5);
        double slow = cps(plan, 200, 400);
        assertTrue(slow + " cps after lowering the range", slow <= 5.5);

        plan.setCps(25, 30);
        cps(plan, 400, 420);
        double capped = cps(plan, 420, 620);
        assertTrue(capped + " cps with one press per tick", capped <= 20.0);

        plan.setMaxPerTick(3);
        cps(plan, 620, 640);
        double fast = cps(plan, 640, 840);
        assertTrue(fast + " cps after raising the cap", fast >= 24.0);
    }

    @Test
    public void constantClicksAtTheTopOfTheRangeEvenly() {
        ClickPlan plan = new ClickPlan(ConstantClickTiming.INSTANCE, new Random(1L));
        plan.setCps(10, 20);
        int[] consumed = run(plan, 20 * 60, tick -> { });
        for (int i = 1; i < consumed.length; i++) {
            assertEquals("tick " + i, 1, consumed[i]);
        }
    }

    @Test
    public void pressesPerTickNeverExceedTheCap() {
        for (int cap = 1; cap <= 3; cap++) {
            ClickPlan plan = humanPlan(25, 30, cap);
            long now = 0L;
            for (int tick = 0; tick < 5000; tick++) {
                /* A late tick collapses several windows into one. */
                now += tick % 97 == 0 ? 3 * ClickPlan.TICK_MS : ClickPlan.TICK_MS;
                plan.tick(now);
                for (int n = 0; n <= 20; n++) {
                    assertTrue("tick " + tick + " + " + n, plan.clicksAt(n) <= cap);
                }
                plan.consume(() -> true, () -> true);
            }
        }
    }

    @Test
    public void pressesDuringMissTimeAreDropped() {
        ClickPlan plan = humanPlan(11, 14, 2);
        int[] missTime = {0};
        int[] executed = {0};
        int ticks = 2000;
        int[] consumed = new int[ticks];
        for (int tick = 0; tick < ticks; tick++) {
            plan.tick(tick * ClickPlan.TICK_MS);
            consumed[tick] = plan.clicksAt(0);
            /* An air click sets missTime as vanilla's clickMouse on a miss. */
            int clicks = plan.consume(() -> missTime[0] <= 0, () -> {
                missTime[0] = 10;
                return true;
            });
            assertTrue(clicks <= 1);
            executed[0] += clicks;
            if (missTime[0] > 0) {
                missTime[0]--;
            }
        }
        assertTrue(executed[0] + " clicks through miss time", executed[0] <= ticks / 10 + 1);
        assertTrue("dropped presses must not be delayed", sum(consumed) / (ticks / 20.0) >= 11.0);
    }

    @Test
    public void askingAheadBeforeTheFirstTickIsSafe() {
        ClickPlan plan = humanPlan(11, 14, 2);
        for (int n = 0; n <= 20; n++) {
            assertEquals(0, plan.clicksAt(n));
        }
        assertEquals(0, plan.consume(() -> true, () -> true));
        Clicker clicker = new Clicker(new Random(1L));
        assertEquals(20, clicker.ticksUntilClick());
        assertTrue(!clicker.isClickTick());
        /* And the first real tick still plans normally. */
        plan.tick(0L);
        boolean any = false;
        for (int n = 0; n <= 20; n++) {
            any |= plan.clicksAt(n) > 0;
        }
        assertTrue(any);
    }

    @Test
    public void unusedPressesEndTheCombo() {
        ClickPlan plan = humanPlan(11, 14, 2);
        run(plan, 100, tick -> { });
        assertTrue(plan.comboMs() > 4000);
        long now = 100 * ClickPlan.TICK_MS;
        for (int i = 0; i < 10; i++) {
            plan.tick(now);
            now += ClickPlan.TICK_MS;
        }
        assertTrue("combo is " + plan.comboMs() + " ms", plan.comboMs() < 250);
    }
}
