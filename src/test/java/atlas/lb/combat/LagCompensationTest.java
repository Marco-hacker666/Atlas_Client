package atlas.lb.combat;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** LagAware (2026-10-26): where the server has the target when the hit lands, and when a hit holds on both sides. */
public class LagCompensationTest {
    private static final double EPS = 1e-9;

    @Test
    public void leadIsTheRoundTripPlusEasingCapped() {
        assertEquals(0.0, LagCompensation.leadTicks(0, 6), EPS);
        assertEquals(3.0, LagCompensation.leadTicks(100, 6), EPS);
        assertEquals(5.8, LagCompensation.leadTicks(240, 6), EPS);
        assertEquals(6.0, LagCompensation.leadTicks(400, 6), EPS);
    }

    @Test
    public void predictionMovesOnHorizontallyOnly() {
        double[] at = LagCompensation.predict(new double[]{10, 64, 10}, new double[]{0.2, 0.4, -0.1}, 5);
        assertArrayEquals(new double[]{11, 64, 9.5}, at, EPS);
    }

    @Test
    public void velocityIsChangeOverTicks() {
        double[] v = LagCompensation.velocity(new double[][]{{100, 0, 64, 0}, {102, 0.5, 64, 0}, {104, 1.0, 64, -0.4}});
        assertArrayEquals(new double[]{0.25, 0.0, -0.1}, v, EPS);
        assertArrayEquals(new double[]{0, 0, 0}, LagCompensation.velocity(new double[][]{{5, 1, 1, 1}}), EPS);
    }

    @Test
    public void overlapIsTheSharedPartOrNothing() {
        double[] a = {0, 0, 0, 1, 2, 1};
        double[] b = {0.6, 0, 0.2, 1.6, 2, 1.2};
        assertArrayEquals(new double[]{0.6, 0, 0.2, 1, 2, 1}, LagCompensation.overlap(a, b), EPS);
        assertNull(LagCompensation.overlap(a, LagCompensation.offset(a, 2, 0, 0)));
    }

    @Test
    public void aHitOnlyValidOnTheOldPlaceIsHeld() {
        double[] eyes = {0, 1.62, 0};
        // Shown 2.9 away (in reach), but the target is walking away: on the server it is 0.6 further.
        double[] shown = {2.6, 0, -0.4, 3.4, 1.9, 0.4};
        double[] predicted = LagCompensation.offset(shown, 0.6, 0, 0);
        double margin = LagCompensation.margin(240, 0.05);
        assertEquals(0.12, margin, EPS);
        assertFalse(LagCompensation.bothInReach(eyes, shown, predicted, 3.0, margin));
        // Coming closer instead: holds on both sides.
        assertTrue(LagCompensation.bothInReach(eyes, shown, LagCompensation.offset(shown, -0.4, 0, 0), 3.0, margin));
    }
}
