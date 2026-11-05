package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** One tick of 1.8's moves, against what the game's own formulas give by hand (2026-11-05). */
public class MoveModelTest {

    private static MoveModel.State ground() {
        MoveModel.State s = new MoveModel.State();
        s.onGround = true;
        s.moveSpeed = 0.1F;
        return s;
    }

    @Test
    public void walkingFromRestIsTheBaseSpeedTimesTheInputScale() {
        double[] move = MoveModel.step(ground(), 0.0F, 1.0F, 0.0F);
        assertEquals(0.0, move[0], 1.0E-6);
        double friction = 0.6 * 0.91;
        assertEquals(0.98 * 0.1 * 0.16277136 / (friction * friction * friction), move[1], 1.0E-4);
    }

    @Test
    public void yawZeroFacesSouthAndStrafeLeftIsEast() {
        assertTrue(MoveModel.step(ground(), 0.0F, 1.0F, 0.0F)[1] > 0.0);
        assertTrue(MoveModel.step(ground(), 0.0F, 0.0F, 1.0F)[0] > 0.0);
        assertTrue("yaw 90 faces west", MoveModel.step(ground(), 90.0F, 1.0F, 0.0F)[0] < 0.0);
    }

    @Test
    public void diagonalKeysGiveTheWholeSpeedAndStraightKeysTheInputScaleOfIt() {
        MoveModel.State s = ground();
        double straight = MoveModel.step(s, 0.0F, 1.0F, 0.0F)[1];
        double[] diagonal = MoveModel.step(s, 0.0F, 1.0F, 1.0F);
        /* moveFlying divides by the input's length only past 1: 0.98 straight stays 0.98, 1.386 diagonal comes to 1. */
        assertEquals(straight / 0.98, Math.hypot(diagonal[0], diagonal[1]), 1.0E-4);
    }

    @Test
    public void inTheAirOnlyTheAirSpeedIsAdded() {
        MoveModel.State s = new MoveModel.State();
        s.motionZ = 0.3;
        assertEquals(0.3 + 0.98 * 0.02, MoveModel.step(s, 0.0F, 1.0F, 0.0F)[1], 1.0E-4);
        s.sprinting = true;
        assertEquals(0.3 + 0.98 * 0.026, MoveModel.step(s, 0.0F, 1.0F, 0.0F)[1], 1.0E-4);
    }

    @Test
    public void aSprintJumpGetsItsBoostAlongTheYaw() {
        MoveModel.State s = ground();
        s.sprinting = true;
        s.moveSpeed = 0.13F;
        double plain = MoveModel.step(s, 0.0F, 0.0F, 0.0F)[1];
        s.jumped = true;
        assertEquals(plain + 0.2, MoveModel.step(s, 0.0F, 0.0F, 0.0F)[1], 1.0E-4);
    }

    @Test
    public void sneakingAndUsingAnItemCutTheInput() {
        MoveModel.State s = ground();
        double full = MoveModel.step(s, 0.0F, 1.0F, 0.0F)[1];
        s.sneaking = true;
        assertEquals(full * 0.3, MoveModel.step(s, 0.0F, 1.0F, 0.0F)[1], 1.0E-4);
        s.sneaking = false;
        s.usingItem = true;
        assertEquals(full * 0.2, MoveModel.step(s, 0.0F, 1.0F, 0.0F)[1], 1.0E-4);
    }

    @Test
    public void tinyMotionIsDropped() {
        MoveModel.State s = ground();
        s.motionX = 0.004;
        assertEquals(0.0, MoveModel.step(s, 0.0F, 0.0F, 0.0F)[0], 0.0);
    }

    @Test
    public void aMoveFitsTheYawItWasMadeAtAndNotOneFarOff() {
        MoveModel.State s = ground();
        s.sprinting = true;
        s.moveSpeed = 0.13F;
        s.motionX = 0.05;
        s.motionZ = 0.1;
        double[] move = MoveModel.step(s, 37.0F, 1.0F, 0.0F);
        assertEquals(0.0, MoveModel.bestError(s, 37.0F, move[0], move[1]), 1.0E-9);
        assertTrue(MoveModel.bestError(s, 37.0F + 22.0F, move[0], move[1]) > 0.02);
    }

    @Test
    public void everyKeyCombinationIsTried() {
        MoveModel.State s = ground();
        double[] back = MoveModel.step(s, 120.0F, -1.0F, 1.0F);
        assertEquals(0.0, MoveModel.bestError(s, 120.0F, back[0], back[1]), 1.0E-9);
    }

    @Test
    public void startsOffAndChangesNothingByDefault() {
        MoveCheck check = new MoveCheck();
        assertFalse(check.isEnabled());
        assertEquals(0.02F, check.threshold.getValue(), 0.0F);
    }
}
