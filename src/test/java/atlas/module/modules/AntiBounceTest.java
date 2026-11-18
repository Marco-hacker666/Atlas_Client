package atlas.module.modules;

import org.junit.Test;

import java.util.function.Predicate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AntiBounceTest {
    private static final double[] BOX = {0.2, 0.0, 0.2, 0.8, 1.8, 0.8};

    private static final Predicate<double[]> WALL_UNTIL_X1 = b -> b[0] < 1.0 - 1e-9 && b[3] > 0.0;

    @Test
    public void shortestWayOutWins() {
        double[] way = AntiBounce.escape(BOX, WALL_UNTIL_X1, null, 1.0);
        assertArrayEquals(new double[]{0.8, 0, 0}, way, 1e-6);
    }

    @Test
    public void walkedDirectionFirstOnATie() {
        Predicate<double[]> cell = b -> b[0] < 1 && b[3] > 0 && b[2] < 1 && b[5] > 0 && b[1] < 1.8 && b[4] > 0;
        double[] way = AntiBounce.escape(BOX, cell, new double[]{0, 0, -1}, 1.0);
        assertArrayEquals(new double[]{0, 0, -0.8}, way, 1e-6);
    }

    @Test
    public void noWayOutWithinTheLimit() {
        assertNull(AntiBounce.escape(BOX, b -> true, null, 1.0));
    }

    @Test
    public void ticksBeforeTheStuckAsLogged() {
        assertEquals("3t", AntiBounce.ticksBefore(100, 97));
        assertEquals("-2t", AntiBounce.ticksBefore(100, 102));
        assertEquals("none", AntiBounce.ticksBefore(100, -1000));
        assertEquals("200t", AntiBounce.ticksBefore(300, 100));
    }

    @Test
    public void correctionMoveIsHorizontalAndSignedVertical() {
        assertEquals("h0.50 v+1.10", AntiBounce.moveText(0.3, 1.1, 0.4));
        assertEquals("h0.00 v-0.25", AntiBounce.moveText(0, -0.25, 0));
    }
}
