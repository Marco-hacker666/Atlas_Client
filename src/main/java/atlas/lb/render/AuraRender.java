package atlas.lb.render;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Drawing for the LiquidBounce aura's indicators (2026-10-25, LiquidBounce
 * NextGen port, GPL-3.0; KillAuraRangeIndicator, KillAuraNotifyWhenFail's
 * boxes, TargetRenderer's Legacy / Circle / GlowingCircle / Ghost), in plain
 * 1.8 OpenGL. Coordinates are relative to the camera. Colors are ARGB.
 */
public final class AuraRender {
    private AuraRender() {
    }

    public static void begin(boolean depth) {
        GL11.glPushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        if (!depth) {
            GlStateManager.disableDepth();
        }
        GlStateManager.depthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    public static void end() {
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
        GL11.glPopMatrix();
    }

    private static void color(int argb) {
        GL11.glColor4f((argb >> 16 & 255) / 255.0F, (argb >> 8 & 255) / 255.0F, (argb & 255) / 255.0F,
                (argb >>> 24) / 255.0F);
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Linear blend of two ARGB colors. */
    public static int lerp(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = a >>> shift & 255;
            int cb = b >>> shift & 255;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    /** A flat ring (or disc, inner 0) at height y around (x, z), filled, with an optional outline. */
    public static void disc(double x, double y, double z, double inner, double outer, int fill, int outline, int segments) {
        if (alpha(fill) > 0) {
            color(fill);
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i <= segments; i++) {
                double a = Math.PI * 2 * i / segments;
                double c = Math.cos(a);
                double s = Math.sin(a);
                GL11.glVertex3d(x + c * outer, y, z + s * outer);
                GL11.glVertex3d(x + c * inner, y, z + s * inner);
            }
            GL11.glEnd();
        }
        if (alpha(outline) > 0) {
            ring(x, y, z, outer, outline, 1.5F, segments);
        }
    }

    public static void ring(double x, double y, double z, double radius, int argb, float width, int segments) {
        color(argb);
        GL11.glLineWidth(width);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2 * i / segments;
            GL11.glVertex3d(x + Math.cos(a) * radius, y, z + Math.sin(a) * radius);
        }
        GL11.glEnd();
    }

    /** A vertical band fading from {@code bottom} at y to {@code top} at y + height, around (x, z). */
    public static void glow(double x, double y, double z, double radius, double height, int bottom, int top, int segments) {
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            double px = x + Math.cos(a) * radius;
            double pz = z + Math.sin(a) * radius;
            color(bottom);
            GL11.glVertex3d(px, y, pz);
            color(top);
            GL11.glVertex3d(px, y + height, pz);
        }
        GL11.glEnd();
    }

    /** A box from (x0, y0, z0) to (x1, y1, z1), filled and outlined. */
    public static void box(double x0, double y0, double z0, double x1, double y1, double z1, int fill, int outline) {
        if (alpha(fill) > 0) {
            color(fill);
            GL11.glBegin(GL11.GL_QUADS);
            quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            GL11.glEnd();
        }
        if (alpha(outline) > 0) {
            color(outline);
            GL11.glLineWidth(1.5F);
            GL11.glBegin(GL11.GL_LINES);
            double[][] e = {
                    {x0, y0, z0, x1, y0, z0}, {x1, y0, z0, x1, y0, z1}, {x1, y0, z1, x0, y0, z1}, {x0, y0, z1, x0, y0, z0},
                    {x0, y1, z0, x1, y1, z0}, {x1, y1, z0, x1, y1, z1}, {x1, y1, z1, x0, y1, z1}, {x0, y1, z1, x0, y1, z0},
                    {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x1, y0, z1, x1, y1, z1}, {x0, y0, z1, x0, y1, z1}};
            for (double[] l : e) {
                GL11.glVertex3d(l[0], l[1], l[2]);
                GL11.glVertex3d(l[3], l[4], l[5]);
            }
            GL11.glEnd();
        }
    }

    private static void quad(double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        GL11.glVertex3d(ax, ay, az);
        GL11.glVertex3d(bx, by, bz);
        GL11.glVertex3d(cx, cy, cz);
        GL11.glVertex3d(dx, dy, dz);
    }

    /** Ghost: {@code length} points trailing behind a point orbiting the target's middle, fading out. */
    public static void ghost(double x, double y, double z, double radius, double height, float size, int argb,
                             int length, long timeMs) {
        GL11.glPointSize(Math.max(1.0F, size * 10.0F));
        GL11.glBegin(GL11.GL_POINTS);
        for (int i = 0; i < length; i++) {
            double t = (timeMs - i * 25L) / 400.0;
            double px = x + Math.cos(t) * radius;
            double pz = z + Math.sin(t) * radius;
            double py = y + height * (0.5 + 0.5 * Math.sin(t * 0.7));
            int a = (int) (alpha(argb) * (1.0F - i / (float) length));
            color(withAlpha(argb, a));
            GL11.glVertex3d(px, py, pz);
        }
        GL11.glEnd();
        GL11.glPointSize(1.0F);
    }

    /** Hue-cycling color (LiquidBounce's rainbow()), at full alpha. */
    public static int rainbow(long timeMs) {
        return 0xFF000000 | (java.awt.Color.HSBtoRGB((timeMs % 4000L) / 4000.0F, 0.7F, 1.0F) & 0xFFFFFF);
    }
}
