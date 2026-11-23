package atlas.module.modules;

import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScaffoldReachTest {
    private static final Vec3 EYES = new Vec3(0.5, 65.62, 0.5);

    @Test
    public void reachIsFromTheEyesToTheNearestPointOfTheBlock() {
        assertTrue(Scaffold.eyeReaches(EYES, new BlockPos(0, 63, 0), 4.5));
        assertTrue("the near face counts, not the centre", Scaffold.eyeReaches(EYES, new BlockPos(4, 64, 0), 4.5));
        BlockPos deep = new BlockPos(1, 60, 0);
        double feet = Math.sqrt(1.0 + 3.5 * 3.5 + 0.0);
        assertTrue("from the feet it looked in reach: " + feet, feet <= 4.5);
        assertFalse("from the eyes it is not", Scaffold.eyeReaches(EYES, deep, 4.5));
    }
}
