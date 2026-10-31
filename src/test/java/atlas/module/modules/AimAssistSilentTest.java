package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** AimAssist SILENT's turn, Raven bS's smoothRotation (2026-10-31). */
public class AimAssistSilentTest {

    @Test
    public void speedThirtyIsThereAtOnce() {
        float[] r = AimAssist.ravenSmooth(0.0F, 0.0F, 120.0F, 20.0F, 30, 50);
        assertEquals(120.0F, r[0], 1.0E-4F);
        assertEquals(20.0F, r[1], 1.0E-4F);
    }

    @Test
    public void speedTenTurnsAboutTwentyDegreesATick() {
        float[] r = AimAssist.ravenSmooth(0.0F, 0.0F, 90.0F, 0.0F, 10, 0);
        assertEquals(20.0F, r[0], 1.0E-3F);
        assertEquals(0.0F, r[1], 1.0E-4F);
    }

    @Test
    public void neverPassesTheTarget() {
        for (int speed = 1; speed < 30; speed++) {
            for (int i = 0; i < 50; i++) {
                float[] r = AimAssist.ravenSmooth(10.0F, 0.0F, 13.0F, 2.0F, speed, 100);
                assertTrue(r[0] <= 13.0F + 1.0E-4F && r[0] >= 10.0F - 1.0E-4F);
                assertTrue(r[1] <= 2.0F + 1.0E-4F && r[1] >= -1.0E-4F);
            }
        }
    }

    @Test
    public void turnsTheShortWayAndKeepsPitchInRange() {
        float[] r = AimAssist.ravenSmooth(170.0F, 80.0F, -170.0F, 120.0F, 10, 0);
        assertTrue("goes past 180, not back through 0", r[0] > 170.0F);
        assertTrue(r[1] <= 90.0F);
        float[] still = AimAssist.ravenSmooth(5.0F, 5.0F, 50.0F, 50.0F, 0, 0);
        assertEquals(5.0F, still[0], 1.0E-6F);
    }
}
