package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

public class GodBridgeLookTest {

    private static final float EPSILON = 1.0E-3F;

    @Test
    public void aStraightRunHoldsOneSideWhereverThePlayerWalks() {
        GodBridgeLook look = new GodBridgeLook();
        float first = look.look(90.0F, 0.2, 0.2)[0];
        for (double x = 0.0; x < 8.0; x += 0.13) {
            assertEquals("no swap mid run", first, look.look(90.0F, x, 0.8)[0], EPSILON);
        }
    }

    @Test
    public void theLookIsBehindTheWalkAtTheGodbridgePitch() {
        GodBridgeLook look = new GodBridgeLook();
        float[] sent = look.look(0.0F, 0.5, 0.5);
        assertEquals(135.0F, Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(sent[0])), 0.5F);
        assertEquals(GodBridgeLook.STRAIGHT_PITCH, sent[1], EPSILON);
    }

    @Test
    public void aDiagonalRunGoesStraightBack() {
        GodBridgeLook look = new GodBridgeLook();
        float[] sent = look.look(45.0F, 0.5, 0.5);
        assertEquals(225.0F, sent[0], 0.5F);
        assertEquals(GodBridgeLook.DIAGONAL_PITCH, sent[1], EPSILON);
    }

    @Test
    public void theLookIsNeverOnTheFortyFiveGrid() {
        GodBridgeLook look = new GodBridgeLook();
        for (float move = -180.0F; move < 180.0F; move += 45.0F) {
            look.release();
            assertFalse(String.valueOf(move), TellyLane.onGrid(look.look(move, 0.5, 0.5)[0]));
        }
    }

    @Test
    public void aMissChangesSideAtMostOnceInTheWait() {
        GodBridgeLook look = new GodBridgeLook();
        float first = look.look(0.0F, 0.5, 0.5)[0];
        look.missed(100);
        float second = look.look(0.0F, 0.5, 0.5)[0];
        assertEquals(90.6F, Math.abs(second - first), 0.01F);
        look.missed(100 + GodBridgeLook.MISS_TICKS - 1);
        assertEquals("too soon", second, look.look(0.0F, 0.5, 0.5)[0], EPSILON);
        look.missed(100 + GodBridgeLook.MISS_TICKS);
        assertEquals(first, look.look(0.0F, 0.5, 0.5)[0], EPSILON);
    }

    @Test
    public void aNewDirectionOrAReleaseWorksTheLookOutAgain() {
        GodBridgeLook look = new GodBridgeLook();
        look.look(0.0F, 0.5, 0.5);
        look.missed(100);
        look.release();
        float[] again = look.look(0.0F, 0.5, 0.5);
        assertEquals(new GodBridgeLook().look(0.0F, 0.5, 0.5)[0], again[0], EPSILON);
        float turned = look.look(90.0F, 0.5, 0.5)[0];
        assertEquals(135.0F, Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(turned - 90.0F)), 0.5F);
    }
}
