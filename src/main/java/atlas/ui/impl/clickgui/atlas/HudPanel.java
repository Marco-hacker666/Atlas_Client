package atlas.ui.impl.clickgui.atlas;

import atlas.Atlas;
import atlas.module.modules.HUD;
import atlas.module.modules.HUDStyle;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

public final class HudPanel {
    public static final int BACKGROUND = 0x73000000;

    public static final float CARD_RADIUS = 6.0F;

    public static final float SMALL_RADIUS = 3.5F;

    private static final int SHADOW = 0x40000000;
    private static final float SHADOW_SPREAD = 12.0F;
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    private HudPanel() {
    }

    private static HUDStyle style() {
        if (Atlas.moduleManager == null) {
            return null;
        }
        HUDStyle style = (HUDStyle) Atlas.moduleManager.modules.get(HUDStyle.class);
        return style != null && style.isEnabled() ? style : null;
    }

    public static float cardRadius() {
        HUDStyle style = style();
        return style == null ? CARD_RADIUS : style.cardCorners.getValue();
    }

    public static float smallRadius() {
        HUDStyle style = style();
        return style == null ? SMALL_RADIUS : style.smallCorners.getValue();
    }

    public static int background() {
        HUDStyle style = style();
        return style == null ? BACKGROUND : style.panelArgb();
    }

    public static boolean active() {
        if (Atlas.moduleManager == null) {
            return false;
        }
        HUD hud = (HUD) Atlas.moduleManager.modules.get(HUD.class);
        return hud != null && hud.hudTheme.getValue() == 0;
    }

    public static void panel(float x, float y, float x2, float y2) {
        rounded(x, y, x2, y2, smallRadius(), 1.0F, true);
    }

    public static void panel(float x, float y, float x2, float y2, boolean shadow) {
        rounded(x, y, x2, y2, smallRadius(), 1.0F, shadow);
    }

    public static void panel(float x, float y, float x2, float y2, boolean shadow, float fade) {
        rounded(x, y, x2, y2, cardRadius(), fade, shadow);
    }

    public static void row(float x, float y, float x2, float y2) {
        rounded(x, y, x2, y2, smallRadius(), 1.0F, true);
    }

    public static void row(float x, float y, float x2, float y2, float fade) {
        rounded(x, y, x2, y2, smallRadius(), fade, true);
    }

    public static void rounded(float x, float y, float x2, float y2, float radius, float fade, boolean shadow) {
        if (x2 <= x || y2 <= y) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F));
        HUDStyle style = style();
        int fill = style == null ? BACKGROUND : style.panelArgb();
        int shade = style == null ? SHADOW : style.shadowArgb();
        float spread = style == null ? SHADOW_SPREAD : style.shadowSize.getValue();
        int edge = style == null ? 0 : style.outlineArgb();
        int state = saveState();
        float savedAlpha = Liquid.alpha;
        try {
            Liquid.ensureCompiled();
            view();
            Liquid.alpha = Math.max(0.0F, Math.min(1.0F, fade));
            if (shadow && (shade >>> 24) != 0 && spread > 0.0F) {
                Liquid.shadow(x, y, x2, y2, Math.max(r, 2.0F), spread, shade, 0.0F);
            }
            if ((fill >>> 24) != 0) {
                Liquid.rect(x, y, x2, y2, r, fill);
            }
            if ((edge >>> 24) != 0) {
                Liquid.rim(x, y, x2, y2, r, 1.0F, edge, edge);
            }
        } catch (Throwable ignored) {
        } finally {
            Liquid.alpha = savedAlpha;
            restoreState(state);
        }
    }

    public static void fill(float x, float y, float x2, float y2, int argb) {
        fill(x, y, x2, y2, smallRadius(), argb);
    }

    public static void fill(float x, float y, float x2, float y2, float radius, int argb) {
        if (x2 <= x || y2 <= y || (argb >>> 24) == 0) {
            return;
        }
        int state = saveState();
        float savedAlpha = Liquid.alpha;
        try {
            Liquid.ensureCompiled();
            view();
            Liquid.alpha = 1.0F;
            Liquid.rect(x, y, x2, y2, Math.max(0.0F, Math.min(radius, Math.min(x2 - x, y2 - y) / 2.0F)), argb);
        } catch (Throwable ignored) {
        } finally {
            Liquid.alpha = savedAlpha;
            restoreState(state);
        }
    }

    private static void view() {
        Liquid.refreshScale();
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        Liquid.setView(MATRIX.get(0), MATRIX.get(12), MATRIX.get(13));
    }

    private static int saveState() {
        int bits = 0;
        bits |= GL11.glIsEnabled(GL11.GL_TEXTURE_2D) ? 1 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_BLEND) ? 2 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 4 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 8 : 0;
        bits |= GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 16 : 0;
        return bits;
    }

    private static void restoreState(int bits) {
        if ((bits & 1) != 0) {
            GlStateManager.enableTexture2D();
        } else {
            GlStateManager.disableTexture2D();
        }
        if ((bits & 2) != 0) {
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        if ((bits & 4) != 0) {
            GlStateManager.enableAlpha();
        } else {
            GlStateManager.disableAlpha();
        }
        if ((bits & 8) != 0) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        if ((bits & 16) != 0) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
