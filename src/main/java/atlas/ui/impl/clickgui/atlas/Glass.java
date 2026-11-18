package atlas.ui.impl.clickgui.atlas;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

final class Glass {
    private Glass() {
    }

    static final int MATERIAL_TOP = 0xDC161B2E;
    static final int MATERIAL_BOTTOM = 0xEA0B0E14;

    static final int VEIL_TOP = 0xB01A2038;
    static final int VEIL_BOTTOM = 0xC40D1119;

    static final int ACCENT_COOL = 0xFF7FDBFF;
    static final int ACCENT_DEEP = 0xFF0A4D68;
    static final int ACCENT_MINT = 0xFF5FFFC1;
    static final int ACCENT_AMBER = 0xFFFFB347;

    static final int BACKLIGHT = 0xFFB9A7FF;

    private static final int SEGMENTS = 8;

    private static void sweep(float x, float y, float x2, float y2, float radius,
                              int top, int bottom, boolean shaded) {
        float height = Math.max(0.0001F, y2 - y);
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            float cx;
            float cy;
            double from;
            switch (quadrant) {
                case 0: cx = x2 - radius; cy = y + radius; from = 180.0; break;
                case 1: cx = x2 - radius; cy = y2 - radius; from = 90.0; break;
                case 2: cx = x + radius; cy = y2 - radius; from = 0.0; break;
                default: cx = x + radius; cy = y + radius; from = -90.0; break;
            }
            for (int i = 0; i <= SEGMENTS; i++) {
                double angle = Math.toRadians(from - 90.0 * i / (double) SEGMENTS);
                double vx = cx + Math.sin(angle) * radius;
                double vy = cy + Math.cos(angle) * radius;
                if (shaded) {
                    colour(blend(top, bottom, (float) ((vy - y) / height)));
                }
                GL11.glVertex2d(vx, vy);
            }
        }
    }

    static void fill(float x, float y, float x2, float y2, float radius, int colour) {
        fillVertical(x, y, x2, y2, radius, colour, colour);
    }

    static void fillVertical(float x, float y, float x2, float y2, float radius,
                             int top, int bottom) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        radius = Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F));
        begin();
        boolean shaded = top != bottom;
        if (shaded) {
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
        } else {
            colour(top);
        }
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        if (shaded) {
            colour(blend(top, bottom, 0.5F));
        }
        GL11.glVertex2f((x + x2) / 2.0F, (y + y2) / 2.0F);
        sweep(x, y, x2, y2, radius, top, bottom, shaded);

        if (shaded) {
            colour(blend(top, bottom, 0.0F));
        }
        GL11.glVertex2f(x2 - radius, y);
        GL11.glEnd();
        if (shaded) {
            GlStateManager.shadeModel(GL11.GL_FLAT);
        }
        end();
    }

    static void rim(float x, float y, float x2, float y2, float radius, int top, int bottom) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        radius = Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F));
        begin();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        sweep(x + 0.5F, y + 0.5F, x2 - 0.5F, y2 - 0.5F, radius, top, bottom, true);
        GL11.glEnd();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        end();
    }

    static void pane(float x, float y, float x2, float y2, float radius) {
        fillVertical(x, y, x2, y2, radius, MATERIAL_TOP, MATERIAL_BOTTOM);

        fill(x + radius, y + 1.0F, x2 - radius, y + 2.0F, 0.0F, 0x1AFFFFFF);
        fill(x + radius, y2 - 2.0F, x2 - radius, y2 - 1.0F, 0.0F, 0x30000000);
        dispersiveRim(x, y, x2, y2, radius);
        grain(x, y, x2, y2);
    }

    static void dispersiveRim(float x, float y, float x2, float y2, float radius) {
        rim(x - 0.34F, y - 0.34F, x2 - 0.34F, y2 - 0.34F, radius, 0x3300E0FF, 0x0A00E0FF);
        rim(x + 0.34F, y + 0.34F, x2 + 0.34F, y2 + 0.34F, radius, 0x33FF77CC, 0x0AFF77CC);
        rim(x, y, x2, y2, radius, 0x5CFFFFFF, 0x0EFFFFFF);
    }

    static void grain(float x, float y, float x2, float y2) {
        begin();
        GL11.glBegin(GL11.GL_POINTS);
        int seed = 0;
        for (float gy = y + 2.0F; gy < y2 - 2.0F; gy += 3.0F) {
            for (float gx = x + 2.0F; gx < x2 - 2.0F; gx += 3.0F) {
                seed = seed * 1103515245 + 12345;
                int v = (seed >>> 16) & 0xFF;
                if (v < 96) {
                    continue;
                }
                colour(v > 200 ? 0x0EFFFFFF : 0x0A000000);
                GL11.glVertex2f(gx + ((v & 1) == 0 ? 0.0F : 1.0F), gy + ((v & 2) == 0 ? 0.0F : 1.0F));
            }
        }
        GL11.glEnd();
        end();
    }

    static void backlight(float x, float y, float x2, float y2, float radius, int strength) {
        for (int i = 6; i > 0; i--) {
            float spread = i * 2.4F;
            int alpha = Math.max(1, strength / (i * 3));
            fill(x - spread, y - spread, x2 + spread, y2 + spread, radius + spread,
                    (alpha << 24) | (BACKLIGHT & 0x00FFFFFF));
        }
    }

    static void sheet(float x, float y, float x2, float y2, float radius, int alpha) {
        fill(x, y, x2, y2, radius, (alpha << 24) | 0x00FFFFFF);
    }

    static void glow(float x, float y, float x2, float y2, float radius, int colour, int alpha) {
        int strong = (alpha << 24) | (colour & 0x00FFFFFF);
        int weak = ((alpha / 3) << 24) | (colour & 0x00FFFFFF);
        fillVertical(x, y, x2, y2, radius, strong, weak);
    }

    static void shadow(float x, float y, float x2, float y2, float radius, int layers, int strength) {
        for (int i = layers; i > 0; i--) {
            float spread = i * 1.7F;
            int alpha = Math.max(1, (int) (strength * (1.0F - i / (layers + 1.0F)) / layers));
            fill(x - spread, y - spread + 2.0F, x2 + spread, y2 + spread + 2.0F,
                    radius + spread, alpha << 24);
        }
    }

    static void divider(float x, float y, float x2) {
        fill(x, y, x2, y + 0.5F, 0.0F, 0x40000000);
        fill(x, y + 0.5F, x2, y + 1.0F, 0.0F, 0x12FFFFFF);
    }

    static int alpha(int colour, float factor) {
        int a = (int) (((colour >>> 24) & 0xFF) * factor);
        return (Math.max(0, Math.min(255, a)) << 24) | (colour & 0x00FFFFFF);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int aa = (a >>> 24) & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int ab = a & 0xFF;
        int bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24)
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static void colour(int c) {
        GlStateManager.color(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F,
                (c & 0xFF) / 255.0F, ((c >>> 24) & 0xFF) / 255.0F);
    }

    private static void begin() {
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();

        GlStateManager.disableCull();
    }

    private static void end() {
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
