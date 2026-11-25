package atlas.lb.combat;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReachDriftTest {
    @Test
    public void offMeansTheSetRange() {
        ReachDrift d = new ReachDrift(new Random(1));
        Object t = new Object();
        for (double dist = 5.0; dist > 1.0; dist -= 0.1) {
            d.update(3.0F, 0.0F, t, dist);
            assertEquals(0.0F, d.cut(), 0.0F);
        }
    }

    @Test
    public void onlyEverShorterAndWithinTheDrift() {
        ReachDrift d = new ReachDrift(new Random(2));
        Object t = new Object();
        for (int i = 0; i < 2000; i++) {
            d.update(3.0F, 0.3F, t, 2.0 + (i % 40) * 0.05);
            assertTrue(d.cut() >= 0.0F && d.cut() <= 0.3F);
        }
    }

    @Test
    public void theCutHoldsUntilTheTargetCrossesIt() {
        ReachDrift d = new ReachDrift(new Random(3));
        Object t = new Object();
        d.update(3.0F, 0.4F, t, 4.0);
        float first = d.cut();
        for (double dist = 4.0; dist > 3.0; dist -= 0.05) {
            d.update(3.0F, 0.4F, t, dist);
            assertEquals("still outside even the longest reach", first, d.cut(), 0.0F);
        }
        double edge = 3.0 - first;
        d.update(3.0F, 0.4F, t, edge - 0.01);
        // crossing in draws a new cut (the same value again is possible but not with this seed)
        assertTrue(first != d.cut());
    }

    @Test
    public void eachTargetAndEachCrossingGetsItsOwnEdge() {
        ReachDrift d = new ReachDrift(new Random(4));
        Set<Float> cuts = new HashSet<Float>();
        for (int i = 0; i < 50; i++) {
            d.update(3.0F, 0.3F, new Object(), 3.5);
            cuts.add(d.cut());
        }
        assertTrue(cuts.size() > 40);
    }
}
