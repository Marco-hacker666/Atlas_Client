package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

public class TellyYawWindowTest {

    @Test
    public void theFirstLookIsNoStep() {
        TellyYawWindow window = new TellyYawWindow();
        window.look(90.0F, 1000L);
        assertEquals(0.0, window.sum(1000L), 1.0E-6);
    }

    @Test
    public void stepsAddUpTheShortWayRound() {
        TellyYawWindow window = new TellyYawWindow();
        window.look(170.0F, 1000L);
        window.look(-170.0F, 1050L);
        window.look(-600.0F, 1100L);
        assertEquals("20 across the seam, then 70 past a whole turn", 90.0, window.sum(1100L), 1.0E-3);
    }

    @Test
    public void stepsLeaveTheWindowAfterFiveSeconds() {
        TellyYawWindow window = new TellyYawWindow();
        window.look(0.0F, 1000L);
        window.look(100.0F, 1000L);
        window.look(150.0F, 3000L);
        assertEquals(150.0, window.sum(1000L + TellyYawWindow.WINDOW_MS), 1.0E-3);
        assertEquals(50.0, window.sum(1001L + TellyYawWindow.WINDOW_MS), 1.0E-3);
        assertEquals(0.0, window.sum(3001L + TellyYawWindow.WINDOW_MS), 1.0E-3);
    }

    @Test
    public void clearForgetsTheLastLookToo() {
        TellyYawWindow window = new TellyYawWindow();
        window.look(0.0F, 1000L);
        window.look(100.0F, 1000L);
        window.clear();
        window.look(10.0F, 1000L);
        assertEquals(0.0, window.sum(1000L), 1.0E-6);
    }

    @Test
    public void aHalfTurnIsCutShortAndNothingElseIs() {
        assertEquals(-45.0F, TellyYawWindow.awayFromHalfTurn(-210.0F, -45.0F, 150.0F), 1.0E-3F);
        assertEquals(-130.0F, TellyYawWindow.awayFromHalfTurn(-280.0F, -105.0F, 150.0F), 1.0E-3F);
        assertEquals("turning the other way", -430.0F, TellyYawWindow.awayFromHalfTurn(-280.0F, -455.0F, 150.0F), 1.0E-3F);
    }

    @Test
    public void theGridIsEveryFortyFiveDegreesAtAnyAngle() {
        for (float yaw : new float[]{0.0F, 45.0F, -45.0F, -450.0F, 765.0F, 90.05F, -135.08F}) {
            assertTrue(String.valueOf(yaw), TellyYawWindow.onGrid(yaw));
        }
        assertFalse(TellyYawWindow.onGrid(47.0F));
        assertFalse(TellyYawWindow.onGrid(-45.3F));
    }

    @Test
    public void aLookComesHomeShortOfTheGrid() {
        assertEquals(-45.5F, TellyYawWindow.offGrid(-45.0F, -210.0F, 0.5F), 1.0E-3F);
        assertEquals(-44.5F, TellyYawWindow.offGrid(-45.0F, 100.0F, 0.5F), 1.0E-3F);
        assertEquals("not on the grid: left alone", 28.35F, TellyYawWindow.offGrid(28.35F, 200.0F, 0.5F), 1.0E-3F);
        assertFalse(TellyYawWindow.onGrid(TellyYawWindow.offGrid(-45.0F, -210.0F, 0.3F)));
    }
}
