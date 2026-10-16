package myau.util.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Rounded rectangles as plain geometry, for mobile-safe mode and for a driver
 * whose rounded-rect shader did not compile (2026-10-15).
 *
 * One convex outline, drawn as a single triangle fan through the vertex
 * arrays WorldRenderer uses (what gl4es maps most directly onto GLES), with
 * few segments a corner: a phone's GUI scale hides the facets, and the
 * vertex count is what costs on a translation layer.
 *
 * Corner radii are given top-left, top-right, bottom-right, bottom-left as
 * they appear on screen (y grows downward); 0 is a square corner.
 */
public final class RoundRectGeometry {
    private RoundRectGeometry() {
    }

    /** Segments for one corner of this radius: 2 to 8. */
    public static int segments(float radius) {
        if (radius <= 0.0F) {
            return 0;
        }
        return Math.max(2, Math.min(8, (int) Math.ceil(radius * 0.75F)));
    }

    /** The largest radius that fits: half the shorter side. */
    public static float clampRadius(float radius, float width, float height) {
        return Math.max(0.0F, Math.min(radius, Math.min(width, height) * 0.5F));
    }

    /**
     * The outline as x,y pairs, clockwise on screen from the top-left corner.
     * A square corner is one point; a round one is segments + 1 points.
     */
    public static float[] outline(float x, float y, float w, float h,
                                  float rTL, float rTR, float rBR, float rBL) {
        rTL = clampRadius(rTL, w, h);
        rTR = clampRadius(rTR, w, h);
        rBR = clampRadius(rBR, w, h);
        rBL = clampRadius(rBL, w, h);
        int n = count(rTL) + count(rTR) + count(rBR) + count(rBL);
        float[] out = new float[n * 2];
        int i = 0;
        /* Angles in screen space (y down): 180 is left, 270 is up. */
        i = corner(out, i, x + rTL, y + rTL, rTL, 180.0F);
        i = corner(out, i, x + w - rTR, y + rTR, rTR, 270.0F);
        i = corner(out, i, x + w - rBR, y + h - rBR, rBR, 0.0F);
        corner(out, i, x + rBL, y + h - rBL, rBL, 90.0F);
        return out;
    }

    private static int count(float r) {
        return r <= 0.0F ? 1 : segments(r) + 1;
    }

    private static int corner(float[] out, int i, float cx, float cy, float r, float startDeg) {
        if (r <= 0.0F) {
            /* Square: with no radius the centre is the corner point itself. */
            out[i++] = cx;
            out[i++] = cy;
            return i;
        }
        int seg = segments(r);
        for (int s = 0; s <= seg; s++) {
            double a = Math.toRadians(startDeg + 90.0F * s / seg);
            out[i++] = cx + (float) Math.cos(a) * r;
            out[i++] = cy + (float) Math.sin(a) * r;
        }
        return i;
    }

    /** A filled rounded rectangle in one colour. */
    public static void fill(float x, float y, float w, float h,
                            float rTL, float rTR, float rBR, float rBL, int color) {
        fill(x, y, w, h, rTL, rTR, rBR, rBL, color, color, color, color);
    }

    /** A filled rounded rectangle, its colour blended between the four corners' colours. */
    public static void fill(float x, float y, float w, float h,
                            float rTL, float rTR, float rBR, float rBL,
                            int cTL, int cTR, int cBR, int cBL) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        float[] p = outline(x, y, w, h, rTL, rTR, rBR, rBL);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        begin();
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        vertex(wr, x + w * 0.5F, y + h * 0.5F, 0.5F, 0.5F, cTL, cTR, cBR, cBL);
        for (int i = 0; i < p.length; i += 2) {
            vertex(wr, p[i], p[i + 1], (p[i] - x) / w, (p[i + 1] - y) / h, cTL, cTR, cBR, cBL);
        }
        vertex(wr, p[0], p[1], (p[0] - x) / w, (p[1] - y) / h, cTL, cTR, cBR, cBL);
        tessellator.draw();
        end();
    }

    /** The rounded outline as a line loop of the given width. */
    public static void stroke(float x, float y, float w, float h, float radius, float lineWidth, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        float[] p = outline(x, y, w, h, radius, radius, radius, radius);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        begin();
        GL11.glLineWidth(Math.max(1.0F, lineWidth));
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < p.length; i += 2) {
            vertex(wr, p[i], p[i + 1], 0.0F, 0.0F, color, color, color, color);
        }
        tessellator.draw();
        GL11.glLineWidth(1.0F);
        end();
    }

    private static void begin() {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
    }

    private static void end() {
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void vertex(WorldRenderer wr, float px, float py, float u, float v,
                               int cTL, int cTR, int cBR, int cBL) {
        int c = blend(cTL, cTR, cBR, cBL, u, v);
        wr.pos(px, py, 0.0D).color(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, c >>> 24).endVertex();
    }

    /** Bilinear blend of four ARGB corner colours at (u, v) in [0, 1], v downward. */
    public static int blend(int cTL, int cTR, int cBR, int cBL, float u, float v) {
        if (cTL == cTR && cTR == cBR && cBR == cBL) {
            return cTL;
        }
        u = Math.max(0.0F, Math.min(1.0F, u));
        v = Math.max(0.0F, Math.min(1.0F, v));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            float top = (cTL >>> shift & 0xFF) * (1.0F - u) + (cTR >>> shift & 0xFF) * u;
            float bottom = (cBL >>> shift & 0xFF) * (1.0F - u) + (cBR >>> shift & 0xFF) * u;
            int value = Math.round(top * (1.0F - v) + bottom * v);
            out |= (Math.max(0, Math.min(255, value)) & 0xFF) << shift;
        }
        return out;
    }
}
