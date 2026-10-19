package myau.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** The pure maths of the modules rebuilt from Slinky (2026-10-19). */
public class SlinkyPortsTest {

    @Test
    public void fastAccelLimitIsTheGroundTerminalSpeed() {
        /* Walking forward on the ground: moveFlying adds 0.1 per tick and 0.546 of the motion stays. */
        double limit = FastAccel.limit(0.0F, 1.0F, 0.1F, false);
        assertEquals(0.1 / (1.0 - 0.546), limit, 0.001);
        assertEquals(limit + 0.2, FastAccel.limit(0.0F, 1.0F, 0.1F, true), 1.0E-9);
    }

    @Test
    public void fastAccelDiagonalInputIsNormalised() {
        assertEquals(FastAccel.limit(0.0F, 1.0F, 0.1F, false), FastAccel.limit(1.0F, 1.0F, 0.1F, false), 1.0E-6);
    }

    @Test
    public void strafeFullStrengthPointsTheMotionWhereTheKeysAre() {
        /* Yaw 0 faces +Z. Moving +X at 0.2, W held at 100 %: all of it goes to +Z. */
        double[] motion = Strafe.redirect(0.2, 0.0, 0.0F, 1.0F, 0.0F, 1.0F, false);
        assertEquals(0.0, motion[0], 1.0E-9);
        assertEquals(0.2, motion[1], 1.0E-9);
    }

    @Test
    public void strafeZeroStrengthChangesNothing() {
        double[] motion = Strafe.redirect(0.2, 0.1, 37.0F, 1.0F, 1.0F, 0.0F, false);
        assertEquals(0.2, motion[0], 1.0E-12);
        assertEquals(0.1, motion[1], 1.0E-12);
    }

    @Test
    public void instantStopTapsBackAgainstForwardMotion() {
        /* Yaw 0, sliding forward (+Z) at 0.1: a straight back tap is the best stop. */
        assertEquals(0, InstantStop.stoppingDirection(0.0, 0.1, 0.0F, 0.1F));
    }

    @Test
    public void instantStopDoesNothingWhenStill() {
        assertEquals(InstantStop.NONE, InstantStop.stoppingDirection(0.0, 0.0, 0.0F, 0.1F));
    }
}
