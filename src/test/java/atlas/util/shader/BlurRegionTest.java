package atlas.util.shader;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BlurRegionTest {
    /** The GUI overlay at 1920x1080, scale 2: ortho(0, 960, 540, 0, 1000, 3000), translate(0, 0, -2000). */
    private static final float[] MODELVIEW = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -2000, 1};
    private static final float[] PROJECTION = {2.0F / 960, 0, 0, 0, 0, -2.0F / 540, 0, 0, 0, 0, -2.0F / 2000, 0,
            -1, 1, -2, 1};
    private static final int[] VIEWPORT = {0, 0, 1920, 1080};

    private static int[] sizes(int full, int base, int levels) {
        int[] out = new int[levels + 1];
        out[0] = full;
        for (int i = 1; i <= levels; i++) {
            out[i] = (int) (full / Math.pow(base, i));
        }
        return out;
    }

    @Test
    public void guiCoordinatesLandInWindowPixelsFromTheBottom() {
        int[] r = BlurRegion.toWindow(MODELVIEW, PROJECTION, VIEWPORT, 10, 20, 110, 70);
        assertEquals(20, r[0], 1);
        assertEquals(1080 - 140, r[1], 1);
        assertEquals(220, r[2], 1);
        assertEquals(1080 - 40, r[3], 1);
        assertEquals(2.0, BlurRegion.pixelsPerUnit(MODELVIEW, PROJECTION, VIEWPORT), 1.0E-4);
    }

    @Test
    public void maskRectLeavesRoomForTheSoftEdge() {
        int[] r = BlurRegion.maskRect(MODELVIEW, PROJECTION, VIEWPORT, 110, 70, 10, 20);
        int[] bare = BlurRegion.toWindow(MODELVIEW, PROJECTION, VIEWPORT, 10, 20, 110, 70);
        int pad = 2 + (int) Math.ceil(BlurRegion.pixelsPerUnit(MODELVIEW, PROJECTION, VIEWPORT));
        assertTrue(pad >= 4);
        assertArrayEquals(new int[]{bare[0] - pad, bare[1] - pad, bare[2] + pad, bare[3] + pad}, r);
    }

    @Test
    public void aScaledMatrixWithZeroDepthScaleStillMaps() {
        float[] mv = MODELVIEW.clone();
        mv[0] = 2.0F;
        mv[5] = 2.0F;
        mv[10] = 0.0F;
        mv[12] = 30.0F;
        int[] r = BlurRegion.toWindow(mv, PROJECTION, VIEWPORT, 0, 0, 10, 10);
        assertEquals(60, r[0], 1);
        assertEquals(40, r[2] - r[0], 1);
        assertEquals(4.0, BlurRegion.pixelsPerUnit(mv, PROJECTION, VIEWPORT), 1.0E-4);
    }

    @Test
    public void theOverlayQuadFillsTheViewportOnlyWithoutAScale() {
        assertTrue(BlurRegion.fillsViewport(MODELVIEW, PROJECTION, VIEWPORT, 960, 540));
        float[] flat = MODELVIEW.clone();
        flat[10] = 0.0F;
        assertTrue("scale(1, 1, 0), the classic list at scale 1", BlurRegion.fillsViewport(flat, PROJECTION, VIEWPORT, 960, 540));
        float[] scaled = flat.clone();
        scaled[0] = 0.8F;
        scaled[5] = 0.8F;
        assertFalse(BlurRegion.fillsViewport(scaled, PROJECTION, VIEWPORT, 960, 540));
        float[] moved = MODELVIEW.clone();
        moved[12] = 3.0F;
        assertFalse(BlurRegion.fillsViewport(moved, PROJECTION, VIEWPORT, 960, 540));
        assertFalse("a different scaled size", BlurRegion.fillsViewport(MODELVIEW, PROJECTION, VIEWPORT, 961, 540));
        float[] nudged = MODELVIEW.clone();
        nudged[13] = 0.01F;
        assertFalse("a fiftieth of a pixel off", BlurRegion.fillsViewport(nudged, PROJECTION, VIEWPORT, 960, 540));
        float[] undone = MODELVIEW.clone();
        undone[13] = 492.0F - 492.0F;
        assertTrue("a translate and its inverse (the chat)", BlurRegion.fillsViewport(undone, PROJECTION, VIEWPORT, 960, 540));
        float[] odd = {2.0F / (float) (1366 / 3.0), 0, 0, 0, 0, -2.0F / 256, 0, 0, 0, 0, -2.0F / 2000, 0, -1, 1, -2, 1};
        assertTrue("a scaled width that is not whole (1366 at scale 3)",
                BlurRegion.fillsViewport(MODELVIEW, odd, new int[]{0, 0, 1366, 768}, (float) (1366 / 3.0), 256));
    }

    @Test
    public void cornersBehindTheEyeAreNotMapped() {
        float[] perspective = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, -1, 0, 0, -0.1F, 0};
        float[] eye = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 5, 1};
        assertNull(BlurRegion.toWindow(eye, perspective, VIEWPORT, -1, -1, 1, 1));
        assertNull(BlurRegion.maskRect(eye, perspective, VIEWPORT, -1, -1, 1, 1));
        float[] ahead = eye.clone();
        ahead[14] = -5.0F;
        assertTrue(BlurRegion.toWindow(ahead, perspective, VIEWPORT, -1, -1, 1, 1) != null);
    }

    @Test
    public void blurDrawsOnlyUnderTheMaskAndReadsAroundIt() {
        int[] w = sizes(1920, 3, 2);
        int[] h = sizes(1080, 3, 2);
        int[] declared = {1700, 500, 1900, 1000};
        int[][] plan = BlurRegion.plan(BlurRegion.BLUR, w, h, 10.0F, declared);
        assertArrayEquals(declared, plan[0]);
        int[] reach = BlurRegion.margin(BlurRegion.BLUR, w, h, 10.0F);
        assertTrue(reach[0] >= 31 + 93 + 69 + 23);
        for (int k = 1; k < plan.length; k++) {
            int[] level = plan[k];
            assertTrue(level[0] * (1920.0 / w[k]) <= declared[0] - reach[0] + 1920.0 / w[k]);
            assertTrue(level[2] * (1920.0 / w[k]) >= Math.min(1920, declared[2] + reach[0]) - 1920.0 / w[k]);
        }
    }

    /** Plans the OpenGL check (Mesa, scissored passes against the whole-screen ones) found pixel-identical. */
    @Test
    public void plansCheckedOnOpenGlStayTheSame() {
        int[][] blur = BlurRegion.plan(BlurRegion.BLUR, sizes(1920, 3, 2), sizes(1080, 3, 2), 10.0F,
                new int[]{1700, 500, 1900, 1000});
        assertArrayEquals(new int[]{1700, 500, 1900, 1000}, blur[0]);
        assertArrayEquals(new int[]{489, 90, 640, 360}, blur[1]);
        assertArrayEquals(new int[]{162, 29, 213, 120}, blur[2]);
        int[][] bloom = BlurRegion.plan(BlurRegion.BLOOM, sizes(1920, 2, 3), sizes(1080, 2, 3), 4.0F,
                new int[]{1700, 500, 1900, 1000});
        assertArrayEquals(new int[]{1603, 403, 1920, 1080}, bloom[0]);
        assertArrayEquals(new int[]{752, 152, 960, 540}, bloom[1]);
        assertArrayEquals(new int[]{375, 75, 480, 270}, bloom[2]);
        assertArrayEquals(new int[]{187, 37, 240, 135}, bloom[3]);
    }

    @Test
    public void glowReachesOutsideItsShapes() {
        int[] w = sizes(1920, 2, 3);
        int[] h = sizes(1080, 2, 3);
        int[] shapes = {800, 400, 900, 450};
        int[][] plan = BlurRegion.plan(BlurRegion.BLOOM, w, h, 4.0F, shapes);
        int[] reach = BlurRegion.margin(BlurRegion.BLOOM, w, h, 4.0F);
        assertArrayEquals(new int[]{800 - reach[0], 400 - reach[1], 900 + reach[0], 450 + reach[1]}, plan[0]);
    }

    @Test
    public void offScreenBlurIsNothingButOffScreenGlowCanReachIn() {
        int[] w = sizes(1920, 3, 2);
        int[] h = sizes(1080, 3, 2);
        assertNull(BlurRegion.plan(BlurRegion.BLUR, w, h, 4.0F, new int[]{-300, -300, -100, -100}));
        int[] bw = sizes(1920, 2, 3);
        int[] bh = sizes(1080, 2, 3);
        int[][] glow = BlurRegion.plan(BlurRegion.BLOOM, bw, bh, 4.0F, new int[]{-40, 100, -2, 200});
        assertTrue(glow != null && glow[0][0] == 0 && glow[0][2] > 0);
    }

    @Test
    public void aLevelTooSmallMeansNoPlan() {
        assertNull(BlurRegion.plan(BlurRegion.BLUR, new int[]{8, 2, 0}, new int[]{8, 2, 0}, 4.0F, new int[]{0, 0, 8, 8}));
    }

    @Test
    public void moreOffsetOrLevelsReachFurther() {
        int[] w2 = sizes(1920, 3, 2);
        int[] h2 = sizes(1080, 3, 2);
        int[] w3 = sizes(1920, 3, 3);
        int[] h3 = sizes(1080, 3, 3);
        assertTrue(BlurRegion.margin(BlurRegion.BLUR, w2, h2, 10.0F)[0] > BlurRegion.margin(BlurRegion.BLUR, w2, h2, 4.0F)[0]);
        assertTrue(BlurRegion.margin(BlurRegion.BLUR, w3, h3, 4.0F)[0] > BlurRegion.margin(BlurRegion.BLUR, w2, h2, 4.0F)[0]);
    }

    @Test
    public void nothingRunsEveryPassOnNoPixels() {
        int[][] none = BlurRegion.nothing(3);
        assertEquals(4, none.length);
        for (int[] r : none) {
            assertArrayEquals(new int[]{0, 0, 0, 0}, r);
        }
    }

    @Test
    public void unionAndClamp() {
        assertArrayEquals(new int[]{0, 5, 30, 40}, BlurRegion.union(new int[]{0, 10, 20, 40}, new int[]{5, 5, 30, 20}));
        assertArrayEquals(new int[]{0, 0, 100, 50}, BlurRegion.clamp(new int[]{-10, -10, 150, 50}, 100, 80));
        assertNull(BlurRegion.clamp(new int[]{120, 0, 150, 50}, 100, 80));
    }
}
