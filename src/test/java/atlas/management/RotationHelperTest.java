package atlas.management;

import org.junit.Test;

import static org.junit.Assert.*;

/** The shared server look's turn limit (2026-10-31). */
public class RotationHelperTest {

    @Test
    public void aSmallTurnGoesAllTheWay() {
        float[] r = RotationHelper.limitTurn(10.0F, 5.0F, 30.0F, 15.0F, 90.0F);
        assertEquals(30.0F, r[0], 1.0E-4F);
        assertEquals(15.0F, r[1], 1.0E-4F);
    }

    @Test
    public void aBigTurnIsCappedAlongTheSameDirection() {
        float[] r = RotationHelper.limitTurn(0.0F, 0.0F, 180.0F - 1.0F, 0.0F, 90.0F);
        assertEquals(90.0F, r[0], 1.0E-3F);
        float[] diagonal = RotationHelper.limitTurn(0.0F, 0.0F, 120.0F, 90.0F, 90.0F);
        double size = Math.sqrt(diagonal[0] * diagonal[0] + diagonal[1] * diagonal[1]);
        assertEquals(90.0, size, 1.0E-3);
        assertEquals(120.0 / 90.0, diagonal[0] / diagonal[1], 1.0E-3);
    }

    @Test
    public void yawGoesTheShortWayRound() {
        float[] r = RotationHelper.limitTurn(170.0F, 0.0F, -170.0F, 0.0F, 90.0F);
        assertEquals(190.0F, r[0], 1.0E-3F);
    }

    @Test
    public void pitchStaysInRange() {
        float[] r = RotationHelper.limitTurn(0.0F, 80.0F, 0.0F, 140.0F, 90.0F);
        assertEquals(90.0F, r[1], 1.0E-4F);
        float[] up = RotationHelper.limitTurn(0.0F, -80.0F, 0.0F, -170.0F, 90.0F);
        assertEquals(-90.0F, up[1], 1.0E-4F);
    }

    @Test
    public void aModulesOwnCapClampsEachAxis() {
        float[] r = RotationHelper.clampTurn(0.0F, 0.0F, 120.0F, 70.0F, 45.0F);
        assertEquals(45.0F, r[0], 1.0E-4F);
        assertEquals(45.0F, r[1], 1.0E-4F);
        float[] small = RotationHelper.clampTurn(0.0F, 0.0F, 10.0F, -5.0F, 45.0F);
        assertEquals(10.0F, small[0], 1.0E-4F);
        assertEquals(-5.0F, small[1], 1.0E-4F);
    }

    @Test
    public void noLimitLeavesTheTurnWhole() {
        float[] r = RotationHelper.clampTurn(0.0F, 0.0F, 170.0F, 85.0F, RotationHelper.NO_LIMIT);
        assertEquals(170.0F, r[0], 1.0E-4F);
        assertEquals(85.0F, r[1], 1.0E-4F);
        float[] round = RotationHelper.clampTurn(170.0F, 0.0F, -170.0F, 0.0F, RotationHelper.NO_LIMIT);
        assertEquals(190.0F, round[0], 1.0E-3F);
    }

    @Test
    public void noTurnIsNoTurn() {
        float[] r = RotationHelper.limitTurn(42.0F, -7.0F, 42.0F, -7.0F, 90.0F);
        assertEquals(42.0F, r[0], 0.0F);
        assertEquals(-7.0F, r[1], 0.0F);
    }
}
