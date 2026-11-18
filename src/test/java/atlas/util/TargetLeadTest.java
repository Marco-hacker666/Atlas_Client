package atlas.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TargetLeadTest {
    @Test
    public void nothingKnownIsNoAnswer() {
        assertNull(new TargetLead().predict(5.0, Double.NaN));
    }

    @Test
    public void aRunnerIsLedTheWholeWay() {
        TargetLead lead = new TargetLead();

        for (int tick = 0; tick < 20; tick++) {
            double x = 0.28 * (tick - tick % 2);
            lead.add(x, 64.0, 0.0, true);
        }
        double[] at = lead.predict(10.0, Double.NaN);
        double last = 0.28 * 18;
        assertEquals(last + 2.8, at[0], 0.3);
        assertEquals(64.0, at[1], 1.0E-9);
    }

    @Test
    public void aStraferIsLedToTheMiddleOfItsSwing() {
        TargetLead lead = new TargetLead();

        double x = 0.0;
        double v = 0.2;
        for (int tick = 0; tick < 20; tick++) {
            lead.add(x, 64.0, 0.0, true);
            x += v;
            if (tick % 4 == 3) {
                v = -v;
            }
        }
        double[] at = lead.predict(15.0, Double.NaN);

        assertTrue("led to " + at[0], Math.abs(at[0] - 0.4) < 1.0);
    }

    @Test
    public void aJumperComesBackDownToTheFloor() {
        TargetLead lead = new TargetLead();
        for (int tick = 0; tick < 10; tick++) {
            lead.add(0.0, 64.0, 0.0, true);
        }

        double y = 64.0;
        double vy = 0.42;
        for (int tick = 0; tick < 2; tick++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            lead.add(0.0, y, 0.0, false);
        }
        double[] soon = lead.predict(2.0, 64.0);
        assertTrue("still rising a little or at the top: " + soon[1], soon[1] > 64.6);
        double[] later = lead.predict(15.0, 64.0);
        assertEquals(64.0, later[1], 1.0E-9);
    }

    private static double feed(TargetLead lead, double x, int ticks, double v) {
        for (int i = 0; i < ticks; i++) {
            if (i % 2 == 1) {
                x += 2.0 * v;
            }
            lead.add(x, 64.0, 0.0, true);
        }
        return x;
    }

    @Test
    public void onceItStopsItIsNotLedOn() {
        TargetLead lead = new TargetLead();
        double x = feed(lead, 0.0, 6, 0.0);
        x = feed(lead, x, 8, 0.2);
        x = feed(lead, x, 6, 0.0);
        assertTrue(lead.stillNow());
        assertEquals(x, lead.predict(15.0, Double.NaN)[0], 1.0E-9);
    }

    @Test
    public void aStopAndGoPlayerIsLedToTheEndOfItsUsualStep() {
        TargetLead lead = new TargetLead();
        double x = feed(lead, 0.0, 6, 0.0);
        for (int i = 0; i < 3; i++) {
            x = feed(lead, x, 4, 0.2);
            x = feed(lead, x, 6, 0.0);
        }
        assertTrue(lead.typicalBurst() > 0 && lead.typicalBurst() <= 6);

        x = feed(lead, x, 2, 0.2);
        double[] at = lead.predict(15.0, Double.NaN);

        assertTrue("led to " + (at[0] - x), at[0] - x < 1.2);
    }

    @Test
    public void aRunnerIsStillLedTheWholeWay() {
        TargetLead lead = new TargetLead();
        double x = feed(lead, 0.0, 20, 0.28);
        assertEquals(-1, lead.typicalBurst());
        assertEquals(x + 2.8, lead.predict(10.0, Double.NaN)[0], 0.4);
    }
}
