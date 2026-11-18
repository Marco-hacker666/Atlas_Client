package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SnapBridgeTest {
    @Test
    public void takeOffIsTheGroundOfAJumpRun() {
        assertTrue(SnapBridge.forward(SnapBridge.JUMP, true, true));
        assertFalse("in the air", SnapBridge.forward(SnapBridge.JUMP, false, true));
        assertFalse("no W", SnapBridge.forward(SnapBridge.JUMP, true, false));
        assertFalse("Ground style", SnapBridge.forward(SnapBridge.GROUND, true, true));
    }

    @Test
    public void theTakeOffYawIsTheWalkOrFortyFiveOff() {
        assertEquals(30.0F, SnapBridge.takeOffYaw(SnapBridge.TAKEOFF_FORWARD, 30.0F, 1), 1e-4);
        assertEquals(75.0F, SnapBridge.takeOffYaw(SnapBridge.TAKEOFF_DIAGONAL, 30.0F, 1), 1e-4);
        assertEquals(-15.0F, SnapBridge.takeOffYaw(SnapBridge.TAKEOFF_DIAGONAL, 30.0F, -1), 1e-4);
        assertEquals(-155.0F, SnapBridge.takeOffYaw(SnapBridge.TAKEOFF_DIAGONAL, 160.0F, 1), 1e-4);
    }

    @Test
    public void theJumpWaitsForALookThatSprints() {
        assertTrue(SnapBridge.aligned(10.0F, 0.0F));
        assertTrue(SnapBridge.aligned(45.2F, 0.0F));
        assertFalse(SnapBridge.aligned(60.0F, 0.0F));
        assertFalse("the back look", SnapBridge.aligned(180.0F, 0.0F));
        assertTrue("across the wrap", SnapBridge.aligned(-170.0F, 170.0F));
    }

    @Test
    public void bigTurnsTakeTwoTicksSmallOnesTheSmoothStep() {
        assertEquals(45.0F, SnapBridge.smoothCap(20.0F, 45.0F), 1e-4);
        assertEquals(90.0F, SnapBridge.smoothCap(170.0F, 45.0F), 1e-4);
        assertEquals(120.0F, SnapBridge.smoothCap(170.0F, 120.0F), 1e-4);
    }

    @Test
    public void theAirLookIsBackDiagonal() {
        assertEquals(-135.0F, SnapBridge.backDiagonal(0.0F, 1), 1e-4);
        assertEquals(135.0F, SnapBridge.backDiagonal(0.0F, -1), 1e-4);
    }

    @Test
    public void landingSoonFromTheTrace() {
        assertTrue(SnapBridge.landingSoon(144.785, -0.304, 144.0, 3));
        assertFalse(SnapBridge.landingSoon(144.785, -0.304, 144.0, 2));
        assertFalse("rising", SnapBridge.landingSoon(145.0, 0.165, 144.0, 3));
        assertFalse("high above", SnapBridge.landingSoon(145.249, -0.078, 144.0, 3));
    }

    @Test
    public void theWindowKeepsFiveSeconds() {
        SnapBridge.YawWindow w = new SnapBridge.YawWindow();
        for (int i = 0; i < SnapBridge.WINDOW_TICKS; i++) {
            w.add(10.0F);
        }
        assertEquals(1000.0F, w.sum(), 1e-3);
        for (int i = 0; i < SnapBridge.WINDOW_TICKS; i++) {
            w.add(0.0F);
        }
        assertEquals(0.0F, w.sum(), 1e-3);
    }

    @Test
    public void onlyJumpWithWJumpsByItself() {
        assertTrue(SnapBridge.autoJump(SnapBridge.JUMP, true));
        assertFalse(SnapBridge.autoJump(SnapBridge.JUMP, false));
        assertFalse(SnapBridge.autoJump(SnapBridge.GROUND, true));
    }
}
