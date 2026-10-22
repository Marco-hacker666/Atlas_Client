package atlas.module.modules;

import net.minecraft.util.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** AimAssist's framed styles: the aim point and the helpers (2026-10-20). */
public class AimAssistSlinkyTest {

    @Test
    public void noiseIsSmoothAndBounded() {
        float last = AimAssistSlinky.noise(0.0F);
        for (int i = 1; i < 2000; i++) {
            float v = AimAssistSlinky.noise(i * 0.01F);
            assertTrue(v >= -1.01F && v <= 1.01F);
            assertTrue("nearby inputs, nearby values", Math.abs(v - last) < 0.1F);
            last = v;
        }
    }

    @Test
    public void withoutCorrectionOrRandomnessTheAimIsTheTargetsEyes() {
        Vec3 eye = new Vec3(0.0, 65.62, 0.0);
        Vec3 target = new Vec3(0.0, 64.0, 3.0);
        Vec3 nearest = new Vec3(0.0, 65.62, 2.6);
        Vec3 aim = AimAssistSlinky.aimPoint(eye, target, nearest, 2.6, 0.0F, 0.0F, 0.0F);
        assertEquals(0.0, aim.xCoord, 1.0E-9);
        assertEquals(65.62, aim.yCoord, 1.0E-9);
        assertEquals(3.0, aim.zCoord, 1.0E-9);
    }

    @Test
    public void anglesPointYawZeroAtPositiveZ() {
        float[] a = AimAssistSlinky.angles(new Vec3(0, 0, 0), new Vec3(0, 0, 5));
        assertEquals(0.0F, a[0], 1.0E-4F);
        assertEquals(0.0F, a[1], 1.0E-4F);
    }
}
