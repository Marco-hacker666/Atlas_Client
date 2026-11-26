package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MoonWalkTest {
    private static final double FRICTION = 0.546;
    private static final double SIDE_PUSH = 0.098 / Math.sqrt(2.0);

    @Test
    public void walkingSouthItSwaysEitherSideOfTheMiddleAndStaysOverTheRow() {
        MoonWalk moon = new MoonWalk(new Random(7));
        MoonWalk.Settings s = new MoonWalk.Settings();
        double x = 0.5;
        double motionX = 0.0;
        double min = 1.0;
        double max = 0.0;
        int turns = 0;
        float last = 0.0F;
        for (int i = 0; i < 400; i++) {
            float key = moon.strafe(0.0F, x, 0.5, motionX, 0.0, s);
            assertTrue("a key, never a fraction: " + key, key == 0.0F || key == 1.0F || key == -1.0F);
            if (key != 0.0F && last != 0.0F && key != last) {
                turns++;
            }
            if (key != 0.0F) {
                last = key;
            }
            motionX = motionX * FRICTION + key * SIDE_PUSH;
            x += motionX;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        assertTrue("it sways: " + turns, turns > 20);
        assertTrue("left of the middle and right of it: " + min + " " + max, min < 0.49 && max > 0.51);
        assertTrue("the centre stays near the row's middle: " + min + " " + max, min > 0.3 && max < 0.7);
    }

    @Test
    public void walkingWestTheKeyFollowsTheCamera() {
        MoonWalk moon = new MoonWalk(new Random(7));
        MoonWalk.Settings s = new MoonWalk.Settings();
        double z = 0.5;
        double motionZ = 0.0;
        double min = 1.0;
        double max = 0.0;
        for (int i = 0; i < 200; i++) {
            float key = moon.strafe(90.0F, 0.5, z, 0.0, motionZ, s);
            motionZ = motionZ * FRICTION + key * SIDE_PUSH;
            z += motionZ;
            min = Math.min(min, z);
            max = Math.max(max, z);
        }
        assertTrue("the sway is in z and within the row: " + min + " " + max, min > 0.3 && max < 0.7 && max - min > 0.05);
    }

    @Test
    public void noKeyOnADiagonalOrAwayFromTheRowsMiddle() {
        MoonWalk moon = new MoonWalk(new Random(7));
        MoonWalk.Settings s = new MoonWalk.Settings();
        assertEquals(0.0F, moon.strafe(45.0F, 0.5, 0.5, 0.0, 0.0, s), 0.0F);
        assertEquals(0.0F, moon.strafe(30.0F, 0.5, 0.5, 0.0, 0.0, s), 0.0F);
        assertEquals("near the row's side: perhaps another row", 0.0F, moon.strafe(0.0F, 0.1, 0.5, 0.0, 0.0, s), 0.0F);
        assertEquals(0.0F, moon.strafe(0.0F, 0.9, 0.5, 0.0, 0.0, s), 0.0F);
        assertTrue("10 degrees off the axis is still straight", MoonWalk.straight(190.0F));
    }

    @Test
    public void aMiddleOffToOneSideStillSwaysAndNeverLeavesTheActiveBand() {
        MoonWalk moon = new MoonWalk(new Random(3));
        MoonWalk.Settings s = new MoonWalk.Settings();
        s.middleMin = 0.3;
        s.middleMax = 0.3;
        s.widthMin = 0.2;
        s.widthMax = 0.2;
        double x = 0.5;
        double motionX = 0.0;
        int keys = 0;
        for (int i = 0; i < 400; i++) {
            float key = moon.strafe(0.0F, x, 0.5, motionX, 0.0, s);
            if (i > 50 && key != 0.0F) {
                keys++;
            }
            motionX = motionX * FRICTION + key * SIDE_PUSH;
            x += motionX;
            assertTrue("inside the active band: " + x, Math.abs(x - 0.5) <= MoonWalk.ACTIVE);
        }
        assertTrue("still swaying late on: " + keys, keys > 50);
    }

    @Test
    public void theMiddleAndWidthStayInsideTheirCaps() {
        MoonWalk moon = new MoonWalk(new Random(1));
        MoonWalk.Settings s = new MoonWalk.Settings();
        s.middleMin = 0.0;
        s.middleMax = 1.0;
        s.widthMin = 0.0;
        s.widthMax = 1.0;
        s.redrawMin = 1;
        s.redrawMax = 1;
        for (int i = 0; i < 200; i++) {
            moon.strafe(0.0F, 0.5, 0.5, 0.0, 0.0, s);
            assertTrue(Math.abs(moon.middle() - 0.5) <= MoonWalk.MIDDLE_LIMIT + 1.0E-9);
            assertTrue(moon.width() <= MoonWalk.WIDTH_CAP + 1.0E-9);
        }
    }

    private static boolean holds(float[] keys, float key) {
        for (float k : keys) {
            if (k == key) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void keysAlwaysHoldTheKeyStrafePressesAndChangeNothing() {
        for (long seed = 0; seed < 20; seed++) {
            MoonWalk moon = new MoonWalk(new Random(seed));
            MoonWalk.Settings s = new MoonWalk.Settings();
            s.redrawMin = 2;
            s.redrawMax = 6;
            s.middleMin = 0.4;
            s.middleMax = 0.6;
            double x = 0.5;
            double motionX = 0.0;
            int single = 0;
            for (int i = 0; i < 400; i++) {
                double middle = moon.middle();
                double width = moon.width();
                float[] keys = moon.keys(0.0F, x, 0.5, motionX, 0.0, s);
                assertEquals("keys leave the middle", middle, moon.middle(), 0.0);
                assertEquals("keys leave the width", width, moon.width(), 0.0);
                assertTrue(keys.length >= 1 && keys.length <= 3);
                float key = moon.strafe(0.0F, x, 0.5, motionX, 0.0, s);
                // through redraws of the middle and new widths after turns
                assertTrue("seed " + seed + " tick " + i + ": " + key + " not in " + java.util.Arrays.toString(keys),
                        holds(keys, key));
                if (keys.length == 1) {
                    single++;
                }
                motionX = motionX * FRICTION + key * SIDE_PUSH;
                x += motionX;
            }
            assertTrue("mostly one key: " + single, single > 200);
        }
    }

    @Test
    public void aPendingDrawGivesEveryKeyItCouldLeadTo() {
        // a fresh sway at 0.56 with no slide: a width under 0.06 turns it back (-1), a wider one keeps +1
        float[] keys = new MoonWalk(new Random(1)).keys(0.0F, 0.56, 0.5, 0.0, 0.0, new MoonWalk.Settings());
        assertTrue(holds(keys, 1.0F));
        assertTrue(holds(keys, -1.0F));
    }

    @Test
    public void withNothingDrawnItIsTheOneKeyStrafePresses() {
        MoonWalk moon = new MoonWalk(new Random(3));
        MoonWalk.Settings s = new MoonWalk.Settings();
        s.redrawMin = 1000;
        s.redrawMax = 1000;
        moon.strafe(0.0F, 0.5, 0.5, 0.0, 0.0, s);
        float[] keys = moon.keys(0.0F, 0.5, 0.5, 0.0, 0.0, s);
        assertEquals(1, keys.length);
        assertEquals(keys[0], moon.strafe(0.0F, 0.5, 0.5, 0.0, 0.0, s), 0.0F);
    }
}
