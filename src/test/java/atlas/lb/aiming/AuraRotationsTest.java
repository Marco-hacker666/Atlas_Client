package atlas.lb.aiming;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The LiquidBounce rotation engine port (2026-10-25). */
public class AuraRotationsTest {
    private static final Rotation CAMERA = new Rotation(0.0F, 0.0F);

    private static AuraRotations linear(float speed) {
        AuraRotations rotations = new AuraRotations(new Random(1L));
        AngleSmooth.Linear linear = new AngleSmooth.Linear();
        linear.horizontalMin = linear.horizontalMax = speed;
        linear.verticalMin = linear.verticalMax = speed;
        rotations.smooth = linear;
        return rotations;
    }

    @Test
    public void nothingRequestedLeavesTheCamera() {
        AuraRotations rotations = linear(30.0F);
        assertNull(rotations.update(CAMERA, 0.0));
        assertFalse(rotations.active());
    }

    @Test
    public void turnsTowardTheRequestAtTheSmootherSpeed() {
        AuraRotations rotations = linear(30.0F);
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        assertEquals(30.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4);
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        assertEquals(60.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4);
    }

    @Test
    public void anExpiredRequestTurnsBackAndIsLetGoNearTheCamera() {
        AuraRotations rotations = linear(30.0F);
        rotations.ticksUntilReset = 1;
        rotations.request(new Rotation(60.0F, 0.0F), 3.0, false);
        assertEquals(30.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4); // the request's one tick
        assertEquals(0.0F, rotations.update(CAMERA, 0.0) == null ? 0.0F : 1.0F, 0.0); // 30 -> 0: within 2, let go
        assertFalse(rotations.active());
    }

    @Test
    public void aRequestLivesTicksUntilReset() {
        AuraRotations rotations = linear(10.0F);
        rotations.ticksUntilReset = 3;
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        assertEquals(10.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4);
        assertEquals(20.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4);
        assertEquals(30.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4);
        assertFalse(rotations.requesting());
        assertEquals(20.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4); // turning back now
        assertTrue(rotations.active());
    }

    @Test
    public void everyStepIsWholeMouseSteps() {
        AuraRotations rotations = linear(180.0F);
        rotations.request(new Rotation(10.07F, 0.0F), 3.0, false);
        Rotation r = rotations.update(CAMERA, 0.15);
        assertNotNull(r);
        assertEquals(10.05F, r.yaw, 1e-3);
    }

    @Test
    public void changeLookLetsGoTheTickAfter() {
        AuraRotations rotations = linear(180.0F);
        rotations.movementCorrection = AuraRotations.CORRECTION_CHANGE_LOOK;
        rotations.ticksUntilReset = 10;
        rotations.request(new Rotation(45.0F, 0.0F), 3.0, false);
        assertNotNull(rotations.update(CAMERA, 0.0));
        assertNull(rotations.update(new Rotation(45.0F, 0.0F), 0.0)); // the request lasts one tick
    }

    @Test
    public void expiringTurnsBackAtOnceInsteadOfAimingOn() {
        AuraRotations rotations = linear(30.0F);
        rotations.ticksUntilReset = 10;
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        assertEquals(30.0F, rotations.update(CAMERA, 0.0).yaw, 1e-4);
        rotations.expire();
        assertEquals(0.0F, rotations.update(CAMERA, 0.0) == null ? 0.0F : 1.0F, 0.0); // back within 2: let go
    }

    @Test
    public void shortStopSometimesBarelyMoves() {
        AuraRotations rotations = linear(30.0F);
        rotations.shortStopOn = true;
        rotations.shortStopRate = 100;
        rotations.request(new Rotation(90.0F, 0.0F), 3.0, false);
        Rotation r = rotations.update(CAMERA, 0.0);
        assertTrue(r.yaw <= 0.1F + 1e-4F);
    }
}
