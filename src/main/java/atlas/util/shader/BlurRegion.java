package atlas.util.shader;

/**
 * Which pixels a Kawase blur or bloom has to compute so that what it draws is the same as blurring the
 * whole screen. The shaders only read a few texels around each pixel, so a HUD element needs its own
 * rectangle plus the distance the passes reach, not the screen. Pure arithmetic, no GL.
 *
 * <p>Rectangles are {x0, y0, x1, y1} in pixels of the framebuffer they belong to, GL window coordinates
 * (origin bottom left), x1 and y1 exclusive.
 */
public final class BlurRegion {
    public static final int BLUR = 0;
    public static final int BLOOM = 1;

    private BlurRegion() {
    }

    /**
     * A rectangle given in the current GL coordinates (what the mask is drawn with) in window pixels.
     *
     * @param modelview  GL_MODELVIEW_MATRIX, column major
     * @param projection GL_PROJECTION_MATRIX, column major
     * @param viewport   GL_VIEWPORT: x, y, width, height
     * @return {x0, y0, x1, y1}, or null when a corner does not map to the screen (or is behind the eye)
     */
    public static int[] toWindow(float[] modelview, float[] projection, int[] viewport,
                                 float x1, float y1, float x2, float y2) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        float[][] corners = {{x1, y1}, {x2, y1}, {x2, y2}, {x1, y2}};
        for (float[] corner : corners) {
            double[] eye = multiply(modelview, corner[0], corner[1], 0.0, 1.0);
            double[] clip = multiply(projection, eye[0], eye[1], eye[2], eye[3]);
            if (!(clip[3] > 1.0E-9)) {
                return null;
            }
            double ndcX = clip[0] / clip[3];
            double ndcY = clip[1] / clip[3];
            double winX = viewport[0] + (ndcX + 1.0) * 0.5 * viewport[2];
            double winY = viewport[1] + (ndcY + 1.0) * 0.5 * viewport[3];
            if (Double.isNaN(winX) || Double.isNaN(winY) || Double.isInfinite(winX) || Double.isInfinite(winY)) {
                return null;
            }
            minX = Math.min(minX, winX);
            minY = Math.min(minY, winY);
            maxX = Math.max(maxX, winX);
            maxY = Math.max(maxY, winY);
        }
        /* Far past the screen is the same as a million pixels past it, and keeps the int arithmetic (padding,
           margins) from overflowing. */
        return new int[]{(int) Math.floor(limit(minX)), (int) Math.floor(limit(minY)), (int) Math.ceil(limit(maxX)),
                (int) Math.ceil(limit(maxY))};
    }

    private static double limit(double v) {
        return Math.max(-1.0E6, Math.min(1.0E6, v));
    }

    /**
     * Where a mask rectangle lands, in window pixels, with room for the soft edge shapes are drawn with (one
     * unit of the current coordinates plus two pixels each way). What BlurUtils.region uses.
     */
    public static int[] maskRect(float[] modelview, float[] projection, int[] viewport,
                                 float x1, float y1, float x2, float y2) {
        int[] r = toWindow(modelview, projection, viewport, Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2),
                Math.max(y1, y2));
        if (r == null) {
            return null;
        }
        int pad = 2 + (int) Math.ceil(pixelsPerUnit(modelview, projection, viewport));
        return grow(r, pad, pad);
    }

    /** How many window pixels one unit of the current coordinates is (the larger of x and y); 1 if unknown. */
    public static double pixelsPerUnit(float[] modelview, float[] projection, int[] viewport) {
        double[] origin = window(modelview, projection, viewport, 0.0, 0.0);
        double[] alongX = window(modelview, projection, viewport, 1.0, 0.0);
        double[] alongY = window(modelview, projection, viewport, 0.0, 1.0);
        if (origin == null || alongX == null || alongY == null) {
            return 1.0;
        }
        double x = Math.hypot(alongX[0] - origin[0], alongX[1] - origin[1]);
        double y = Math.hypot(alongY[0] - origin[0], alongY[1] - origin[1]);
        double ppu = Math.max(x, y);
        return ppu > 0.0 && ppu < 1.0E4 ? ppu : 1.0;
    }

    private static double[] window(float[] modelview, float[] projection, int[] viewport, double x, double y) {
        double[] eye = multiply(modelview, x, y, 0.0, 1.0);
        double[] clip = multiply(projection, eye[0], eye[1], eye[2], eye[3]);
        if (!(clip[3] > 1.0E-9)) {
            return null;
        }
        return new double[]{viewport[0] + (clip[0] / clip[3] + 1.0) * 0.5 * viewport[2],
                viewport[1] + (clip[1] / clip[3] + 1.0) * 0.5 * viewport[3]};
    }

    /**
     * Whether the full-screen quad the passes draw (ShaderUtils.drawQuads: (0, 0) to (width, height) in the
     * current coordinates, the screen's scaled size) lands on the viewport corner for corner. The passes read
     * the mask and composite their result through that quad's texture coordinates, so only then does each
     * pixel's result stay on that pixel. Under any other transform (an end call made inside a scale) the
     * result lands somewhere else, possibly outside the mask's area, and only the whole-screen path draws it
     * there as before. The tolerance is a thousandth of a pixel: float rounding of an exact overlay is far
     * below it, while any real offset would make the composite blend in texels from outside the area.
     */
    public static boolean fillsViewport(float[] modelview, float[] projection, int[] viewport, double width, double height) {
        double left = viewport[0];
        double bottom = viewport[1];
        double right = viewport[0] + viewport[2];
        double top = viewport[1] + viewport[3];
        double[][] corners = {{0.0, 0.0, left, top}, {width, 0.0, right, top}, {width, height, right, bottom},
                {0.0, height, left, bottom}};
        for (double[] c : corners) {
            double[] at = window(modelview, projection, viewport, c[0], c[1]);
            if (at == null || Math.abs(at[0] - c[2]) > 0.001 || Math.abs(at[1] - c[3]) > 0.001) {
                return false;
            }
        }
        return true;
    }

    /** A plan that draws nothing: every pass still runs (same GL state afterwards), on no pixels. */
    public static int[][] nothing(int levels) {
        int[][] out = new int[levels + 1][];
        for (int i = 0; i <= levels; i++) {
            out[i] = new int[]{0, 0, 0, 0};
        }
        return out;
    }

    private static double[] multiply(float[] m, double x, double y, double z, double w) {
        return new double[]{
                m[0] * x + m[4] * y + m[8] * z + m[12] * w,
                m[1] * x + m[5] * y + m[9] * z + m[13] * w,
                m[2] * x + m[6] * y + m[10] * z + m[14] * w,
                m[3] * x + m[7] * y + m[11] * z + m[15] * w
        };
    }

    public static int[] union(int[] a, int[] b) {
        if (a == null) {
            return b == null ? null : b.clone();
        }
        if (b == null) {
            return a.clone();
        }
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    public static int[] grow(int[] r, int x, int y) {
        return new int[]{r[0] - x, r[1] - y, r[2] + x, r[3] + y};
    }

    /** Clamped to the framebuffer; null when nothing is left. */
    public static int[] clamp(int[] r, int width, int height) {
        int x0 = Math.max(0, r[0]);
        int y0 = Math.max(0, r[1]);
        int x1 = Math.min(width, r[2]);
        int y1 = Math.min(height, r[3]);
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    /**
     * How far, in full-resolution pixels (x, y), the passes read around a pixel they draw: every pass reads
     * up to its offset (down: one output texel each way, up: two) plus one source texel for linear filtering,
     * plus one coarsest texel and two pixels of slack for rounding.
     *
     * @param levelWidths  index 0 is the full width, then each level's framebuffer width
     * @param levelHeights the same for heights
     */
    public static int[] margin(int kind, int[] levelWidths, int[] levelHeights, float offset) {
        return new int[]{axisMargin(kind, levelWidths, offset), axisMargin(kind, levelHeights, offset)};
    }

    private static int axisMargin(int kind, int[] sizes, float offset) {
        int levels = sizes.length - 1;
        double o = Math.abs(offset);
        double[] ratio = new double[levels + 1];
        ratio[0] = 1.0;
        for (int i = 1; i <= levels; i++) {
            ratio[i] = sizes[0] / (double) Math.max(1, sizes[i]);
        }
        double reach = 0.0;
        for (int k = 1; k <= levels; k++) {
            reach += passOffset(kind, o, k) * ratio[k] + ratio[k - 1];
        }
        for (int k = levels; k >= 2; k--) {
            reach += 2.0 * passOffset(kind, o, k) * ratio[k - 1] + ratio[k];
        }
        reach += 2.0 * o + (levels >= 1 ? ratio[1] : 1.0);
        reach += ratio[levels] + 2.0;
        return (int) Math.ceil(reach);
    }

    /** The offset a down pass into level k, or the up pass out of level k, uses (see KawaseBlur, KawaseBloom). */
    private static double passOffset(int kind, double offset, int k) {
        return kind == BLOOM ? offset / Math.pow(1.5, k - 1) : offset;
    }

    /** A full-resolution rectangle in a level's pixels: scaled out to whole texels, one more each way. */
    public static int[] toLevel(int[] r, int width, int height, int levelWidth, int levelHeight) {
        int x0 = (int) Math.floor(r[0] * (double) levelWidth / width) - 1;
        int y0 = (int) Math.floor(r[1] * (double) levelHeight / height) - 1;
        int x1 = (int) Math.ceil(r[2] * (double) levelWidth / width) + 1;
        int y1 = (int) Math.ceil(r[3] * (double) levelHeight / height) + 1;
        int[] clamped = clamp(new int[]{x0, y0, x1, y1}, levelWidth, levelHeight);
        return clamped == null ? new int[]{0, 0, 0, 0} : clamped;
    }

    /**
     * The rectangles to draw: [0] the last pass and the composite (full resolution), [k] level k.
     * A blur only changes pixels under its mask; a bloom also spreads its glow around the mask, so its last
     * pass covers the mask plus the reach and the levels twice the reach.
     *
     * @param declared where the mask is, full-resolution window pixels
     * @return null when nothing on screen can change
     */
    public static int[][] plan(int kind, int[] levelWidths, int[] levelHeights, float offset, int[] declared) {
        int width = levelWidths[0];
        int height = levelHeights[0];
        int levels = levelWidths.length - 1;
        if (declared == null || width <= 0 || height <= 0) {
            return null;
        }
        for (int i = 1; i <= levels; i++) {
            if (levelWidths[i] <= 0 || levelHeights[i] <= 0) {
                return null;
            }
        }
        int[] reach = margin(kind, levelWidths, levelHeights, offset);
        int[] last = kind == BLOOM ? grow(declared, reach[0], reach[1]) : declared;
        int[] chain = kind == BLOOM ? grow(declared, reach[0] * 2, reach[1] * 2) : grow(declared, reach[0], reach[1]);
        int[] lastClamped = clamp(last, width, height);
        int[] chainClamped = clamp(chain, width, height);
        if (lastClamped == null || chainClamped == null) {
            return null;
        }
        int[][] out = new int[levels + 1][];
        out[0] = lastClamped;
        for (int k = 1; k <= levels; k++) {
            out[k] = toLevel(chainClamped, width, height, levelWidths[k], levelHeights[k]);
        }
        return out;
    }
}
