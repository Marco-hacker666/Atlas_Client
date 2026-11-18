package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeedMineTest {
    @Test
    public void speedRunsFromVanillaToTheServerMargin() {
        assertEquals(1.0F, SpeedMine.finishAt(0), 1e-6F);
        assertEquals(0.85F, SpeedMine.finishAt(50), 1e-6F);
        assertEquals(0.7F, SpeedMine.finishAt(100), 1e-6F);
        assertEquals(0.7F, SpeedMine.finishAt(150), 1e-6F);
    }
}
