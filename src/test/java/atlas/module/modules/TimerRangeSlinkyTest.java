package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The ground input step TimerRange's Slinky modes predict with (2026-10-19). */
public class TimerRangeSlinkyTest {

    @Test
    public void noInputNoMovement() {
        double[] m = TimerRangeSlinky.movement(0.0F, 0.0F, 0.0F, 0.13F);
        assertEquals(0.0, m[0], 1.0E-9);
        assertEquals(0.0, m[1], 1.0E-9);
    }

    @Test
    public void forwardAtYawZeroGoesToPositiveZ() {
        double[] m = TimerRangeSlinky.movement(0.0F, 1.0F, 0.0F, 0.13F);
        assertEquals(0.0, m[0], 1.0E-6);
        assertEquals(0.98 * 0.13, m[1], 1.0E-6);
    }

    @Test
    public void diagonalInputIsNotFasterThanStraight() {
        double[] m = TimerRangeSlinky.movement(0.0F, 1.0F, 1.0F, 0.13F);
        assertEquals(0.13, Math.hypot(m[0], m[1]), 1.0E-6);
    }
}
