package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

public class SlinkyPortsTest {
    @Test
    public void fastAccelLimitIsTheGroundTerminalSpeed() {
        double limit = FastAccel.limit(0.0F, 1.0F, 0.1F, false);
        assertEquals(0.1 / (1.0 - 0.546), limit, 0.001);
        assertEquals(limit + 0.2, FastAccel.limit(0.0F, 1.0F, 0.1F, true), 1.0E-9);
    }

    @Test
    public void fastAccelDiagonalInputIsNormalised() {
        assertEquals(FastAccel.limit(0.0F, 1.0F, 0.1F, false), FastAccel.limit(1.0F, 1.0F, 0.1F, false), 1.0E-6);
    }

    @Test
    public void instantStopTapsBackAgainstForwardMotion() {
        assertEquals(0, InstantStop.stoppingDirection(0.0, 0.1, 0.0F, 0.1F));
    }

    @Test
    public void instantStopDoesNothingWhenStill() {
        assertEquals(InstantStop.NONE, InstantStop.stoppingDirection(0.0, 0.0, 0.0F, 0.1F));
    }
}
