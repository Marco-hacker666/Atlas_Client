package atlas.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/** AutoClicker's Human randomization (2026-10-25). */
public class HumanClicksTest {

    /** Clicks for {@code seconds} of a continuous fight; the gaps. */
    private static List<Long> fight(HumanClicks clicks, int min, int max, int seconds, long start) {
        List<Long> gaps = new ArrayList<Long>();
        long now = start;
        while (now - start < seconds * 1000L) {
            long gap = clicks.next(min, max, now);
            gaps.add(gap);
            now += gap;
        }
        return gaps;
    }

    private static double mean(List<Long> gaps) {
        double sum = 0;
        for (long g : gaps) {
            sum += g;
        }
        return sum / gaps.size();
    }

    @Test
    public void meanCpsStaysInsideTheRange() {
        int[][] ranges = {{8, 12}, {10, 14}, {14, 18}};
        for (int[] r : ranges) {
            List<Long> gaps = fight(new HumanClicks(new Random(7L)), r[0], r[1], 600, 0L);
            double cps = 1000.0 / mean(gaps);
            assertTrue(cps + " outside " + r[0] + ".." + r[1], cps >= r[0] - 0.3 && cps <= r[1] + 0.3);
        }
    }

    @Test
    public void noGapBeatsTheCap() {
        List<Long> gaps = fight(new HumanClicks(new Random(3L)), 10, 14, 600, 0L);
        long floor = (long) Math.ceil(1000.0 / 16.0);
        for (long g : gaps) {
            assertTrue("gap " + g, g >= floor && g <= 1000L);
        }
    }

    @Test
    public void gapsAreSkewedAndVaried() {
        List<Long> gaps = fight(new HumanClicks(new Random(11L)), 10, 14, 600, 0L);
        double m = mean(gaps);
        double var = 0;
        double third = 0;
        for (long g : gaps) {
            var += (g - m) * (g - m);
            third += (g - m) * (g - m) * (g - m);
        }
        var /= gaps.size();
        third /= gaps.size();
        double cv = Math.sqrt(var) / m;
        double skew = third / Math.pow(var, 1.5);
        assertTrue("coefficient of variation " + cv, cv > 0.12 && cv < 0.45);
        assertTrue("skew " + skew, skew > 0.2); // right-skewed, as a hand
    }

    @Test
    public void theCpsWandersInsteadOfSittingStill() {
        HumanClicks clicks = new HumanClicks(new Random(5L));
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        long now = 0L;
        for (int i = 0; i < 3000; i++) {
            now += clicks.next(8, 14, now);
            if (now > 3000L) {
                low = Math.min(low, clicks.cps());
                high = Math.max(high, clicks.cps());
            }
        }
        assertTrue("CPS walked " + low + ".." + high, high - low > 2.0);
    }

    @Test
    public void aFightStartsNearTheTop() {
        HumanClicks clicks = new HumanClicks(new Random(9L));
        long now = 100000L;
        double early = 0;
        for (int i = 0; i < 8; i++) {
            long gap = clicks.next(8, 14, now);
            early += gap;
            now += gap;
        }
        double cps = 1000.0 / (early / 8);
        assertTrue("opening CPS " + cps, cps > 11.0);
    }
}
