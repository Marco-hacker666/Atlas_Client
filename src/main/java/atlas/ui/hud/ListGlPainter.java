package atlas.ui.hud;

import atlas.font.CFontRenderer;
import atlas.ui.hud.list.ListPainter;
import atlas.util.RenderUtil;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Draws a module list (atlas.ui.hud.list) in the game (2026-11-14): rectangles with RenderUtil -- the rounded ones
 * through its shader -- and text in the HUD's font. It keeps track of whether it is drawing shapes or text, so the
 * GL state is switched only between the two. Call {@link #finish} when done.
 */
public final class ListGlPainter implements ListPainter {
    private static final int NONE = 0;
    private static final int SHAPES = 1;
    private static final int TEXT = 2;

    /** Null: the game's own font. */
    private final CFontRenderer font;
    private final FontRenderer gameFont;
    private int state = NONE;

    public ListGlPainter(CFontRenderer font, FontRenderer gameFont) {
        this.font = font;
        this.gameFont = gameFont;
    }

    @Override
    public void rect(float x1, float y1, float x2, float y2, float radius, int corners, int argb) {
        if (this.state != SHAPES) {
            RenderUtil.enableRenderState();
            this.state = SHAPES;
        }
        if (radius > 0.0F && corners != 0) {
            RenderUtil.drawRoundedRect(x1, y1, x2 - x1, y2 - y1, radius, argb, (corners & TOP_LEFT) != 0,
                    (corners & TOP_RIGHT) != 0, (corners & BOTTOM_LEFT) != 0, (corners & BOTTOM_RIGHT) != 0);
            /* The shader leaves a state of its own: set this one again before the next shape. */
            this.state = NONE;
        } else {
            RenderUtil.drawRect(x1, y1, x2, y2, argb);
        }
    }

    @Override
    public void text(String text, float x, float y, int argb, boolean shadow) {
        if (this.state != TEXT) {
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableDepth();
            this.state = TEXT;
        }
        if (this.font == null) {
            if (shadow) {
                this.gameFont.drawStringWithShadow(text, x, y, argb);
            } else {
                this.gameFont.drawString(text, x, y, argb, false);
            }
        } else if (shadow) {
            this.font.drawStringWithShadow(text, x, y, argb);
        } else {
            this.font.drawString(text, x, y, argb);
        }
    }

    /** Leaves the state RenderUtil.disableRenderState leaves, which the rest of the HUD expects. */
    public void finish() {
        RenderUtil.disableRenderState();
        GlStateManager.resetColor();
        this.state = NONE;
    }
}
