package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecorderAimTest {

    @Test
    public void straightAheadIsZero() {
        /* yaw 0 looks along +Z */
        float[] err = TellyRecorder.aimError(0.0F, 0.0F, 0, 0, 0, 0, 0, 5);
        assertEquals(0.0F, err[0], 1.0E-4F);
        assertEquals(0.0F, err[1], 1.0E-4F);
    }

    @Test
    public void rightAndBelowArePositive() {
        /* yaw 0 faces +Z; yaw 90 faces -X, so -X is to the right */
        float[] err = TellyRecorder.aimError(0.0F, 0.0F, 0, 0, 0, -5, -5, 0);
        assertEquals(90.0F, err[0], 1.0E-3F);
        assertEquals(45.0F, err[1], 1.0E-3F);
    }

    @Test
    public void yawErrorWrapsTheShortWay() {
        float[] err = TellyRecorder.aimError(170.0F, 0.0F, 0, 0, 0, 0, 0, 5);
        assertEquals(-170.0F, err[0], 1.0E-3F);
        assertEquals(10.0F, TellyRecorder.wrap(370.0F), 1.0E-4F);
        assertEquals(-170.0F, TellyRecorder.wrap(190.0F), 1.0E-4F);
    }

    @Test
    public void everyListedModuleIsNamedOnce() {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String name : TellyRecorder.NOT_OWN) {
            org.junit.Assert.assertTrue(name, seen.add(name));
        }
        org.junit.Assert.assertFalse(seen.contains("Sprint"));
        org.junit.Assert.assertEquals(TellyRecorder.AIM_HEADER.split(",").length, 22);
    }
}
