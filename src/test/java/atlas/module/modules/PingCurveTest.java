package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

public class PingCurveTest {
    private static PingCurve.Config config(boolean smooth) {
        PingCurve.Config c = new PingCurve.Config();
        c.min = 100;
        c.max = 200;
        c.smooth = smooth;
        c.rampSpeed = 100;
        c.dashMin = 40;
        c.dashMax = 40;
        return c;
    }

    @Test
    public void holdingDrawsAPingInRangeAndRedrawsIt() {
        PingCurve curve = new PingCurve(new Random(3));
        PingCurve.Config c = config(false);
        double first = curve.hold(1000L, c);
        assertTrue(first >= 100 && first <= 200);
        assertEquals("held between redraws", first, curve.hold(1300L, c), 0.0);
        boolean changed = false;
        for (long t = 1400L; t < 6000L; t += 50L) {
            double d = curve.hold(t, c);
            assertTrue(d >= 100 && d <= 200);
            changed |= d != first;
        }
        assertTrue("redrawn every 400-600 ms", changed);
    }

    @Test
    public void aResetFallsToNothingOverTheDashTime() {
        PingCurve curve = new PingCurve(new Random(4));
        PingCurve.Config c = config(false);
        double ping = curve.hold(1000L, c);
        assertEquals(ping, curve.reset(1000L, c), 1e-9);
        assertEquals(ping / 2.0, curve.reset(1020L, c), 1e-6);
        assertEquals("all due", -1.0, curve.reset(1040L, c), 0.0);
        assertEquals(-1.0, curve.reset(1100L, c), 0.0);
    }

    @Test
    public void instantStaysAtNothingUntilTheNextRedraw() {
        PingCurve curve = new PingCurve(new Random(5));
        PingCurve.Config c = config(false);
        curve.hold(1000L, c);
        curve.reset(1100L, c);
        curve.reset(1150L, c);
        assertEquals("redraw not yet due", 0.0, curve.hold(1160L, c), 0.0);
        double later = curve.hold(1700L, c);
        assertTrue("back once the redraw is due", later >= 100 && later <= 200);
    }

    @Test
    public void smoothClimbsBackAtTheRampSpeed() {
        PingCurve curve = new PingCurve(new Random(6));
        PingCurve.Config c = config(true);
        curve.hold(1000L, c);
        curve.reset(1100L, c);
        curve.reset(1150L, c);
        assertEquals(0.0, curve.hold(1150L, c), 1e-9);
        assertEquals("100 ms/s for 0.5 s", 50.0, curve.hold(1650L, c), 1e-6);
        double top = curve.hold(4000L, c);
        assertTrue("stops at the ping drawn", top >= 100 && top <= 200);
    }
}
