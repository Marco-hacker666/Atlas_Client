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
        /* Sprinting along x, the server sending a position every other tick. */
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
        /* A-D every 4 ticks at 0.2 a tick: it swings between 0 and 0.8. */
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
        /* A straight line from its last speed would put it 3 blocks off; the middle of the swing is 0.4. */
        assertTrue("led to " + at[0], Math.abs(at[0] - 0.4) < 1.0);
    }

    @Test
    public void aJumperComesBackDownToTheFloor() {
        TargetLead lead = new TargetLead();
        for (int tick = 0; tick < 10; tick++) {
            lead.add(0.0, 64.0, 0.0, true);
        }
        /* The jump: 0.42 up, then the game's fall. */
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
}
