package atlas.ui.impl.clickgui.glide;

import atlas.util.RenderUtil;
import atlas.util.font.FontManager;
import atlas.util.font.impl.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Text and small drawing helpers shared by the Glide screen and its settings
 * rows: the style's fonts (with the vanilla font as fallback), trimming and
 * wrapping, a clip stack, and gradients the vanilla screen lacks.
 */
final class GlideDraw {
    /** Open clip rectangles, innermost last: {x1, y1, x2, y2}. */
    private static final List<float[]> CLIPS = new ArrayList<float[]>();

    private GlideDraw() {
    }

    static FontRenderer titleFont() {
        return FontManager.productSansMedium != null ? FontManager.productSansMedium : FontManager.productSans20;
    }

    static FontRenderer rowFont() {
        return FontManager.productSans18;
    }

    static FontRenderer smallFont() {
        return FontManager.productSans16;
    }

    /** {@code s} centred in a band {@code height} tall starting at {@code top}. */
    static void text(FontRenderer font, String s, float x, float top, float height, int color) {
        if ((color >>> 24) < 4) {
            return;
        }
        if (font == null) {
            Minecraft.getMinecraft().fontRendererObj.drawString(s, x, top + (height - 8.0F) / 2.0F, color, false);
            return;
        }
        font.drawString(s, x, top + (height - font.getHeight()) / 2.0F + 1.0F, color);
    }

    static void textRight(FontRenderer font, String s, float right, float top, float height, int color) {
        text(font, s, right - width(font, s), top, height, color);
    }

    static float width(FontRenderer font, String s) {
        return font == null ? Minecraft.getMinecraft().fontRendererObj.getStringWidth(s) : (float) font.getStringWidth(s);
    }

    static float lineHeight(FontRenderer font) {
        return font == null ? 10.0F : (float) font.getHeight() + 2.0F;
    }

    static String trim(FontRenderer font, String s, float max) {
        if (width(font, s) <= max) {
            return s;
        }
        while (s.length() > 1 && width(font, s + "..") > max) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "..";
    }

    /** The end of {@code s} that fits in {@code max}: a field being typed in shows where the caret is. */
    static String tail(FontRenderer font, String s, float max) {
        while (s.length() > 1 && width(font, s) > max) {
            s = s.substring(1);
        }
        return s;
    }

    static List<String> wrap(FontRenderer font, String s, float max) {
        List<String> out = new ArrayList<String>();
        for (String paragraph : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width(font, next) > max) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    static String keyName(int key) {
        if (key <= 0) {
            return "None";
        }
        String name = Keyboard.getKeyName(key);
        return name == null ? "#" + key : name;
    }

    // ---- clipping ------------------------------------------------------

    /** Clips to this rectangle, inside whatever clip is already open. */
    static void pushClip(float x1, float y1, float x2, float y2) {
        if (!CLIPS.isEmpty()) {
            float[] outer = CLIPS.get(CLIPS.size() - 1);
            x1 = Math.max(x1, outer[0]);
            y1 = Math.max(y1, outer[1]);
            x2 = Math.min(x2, outer[2]);
            y2 = Math.min(y2, outer[3]);
        }
        float[] clip = {x1, y1, Math.max(x1, x2), Math.max(y1, y2)};
        CLIPS.add(clip);
        apply(clip);
    }

    static void popClip() {
        if (!CLIPS.isEmpty()) {
            CLIPS.remove(CLIPS.size() - 1);
        }
        if (CLIPS.isEmpty()) {
            RenderUtil.releaseScissor();
        } else {
            apply(CLIPS.get(CLIPS.size() - 1));
        }
    }

    /** The innermost clip's top and bottom, or the whole screen's. */
    static float clipTop() {
        return CLIPS.isEmpty() ? -1.0E6F : CLIPS.get(CLIPS.size() - 1)[1];
    }

    static float clipBottom() {
        return CLIPS.isEmpty() ? 1.0E6F : CLIPS.get(CLIPS.size() - 1)[3];
    }

    /** Drops every clip (a frame that threw half-way must not leave one open). */
    static void resetClips() {
        CLIPS.clear();
        RenderUtil.releaseScissor();
    }

    private static void apply(float[] clip) {
        if (clip[2] - clip[0] < 0.5F || clip[3] - clip[1] < 0.5F) {
            /* Nothing may draw: a 1x1 clip off the corner of the screen. */
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, 0, 0);
            return;
        }
        RenderUtil.scissor(clip[0], clip[1], clip[2] - clip[0], clip[3] - clip[1]);
    }

    // ---- gradients -----------------------------------------------------

    /** A rectangle shading from {@code left} to {@code right} (ARGB). */
    static void gradientH(float x1, float y1, float x2, float y2, int left, int right) {
        quad(x1, y1, x2, y2, left, right, right, left);
    }

    /** A rectangle shading from {@code top} to {@code bottom} (ARGB). */
    static void gradientV(float x1, float y1, float x2, float y2, int top, int bottom) {
        quad(x1, y1, x2, y2, top, top, bottom, bottom);
    }

    /** Corner colours in the order top-left, top-right, bottom-right, bottom-left. */
    private static void quad(float x1, float y1, float x2, float y2, int tl, int tr, int br, int bl) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertex(buffer, x2, y1, tr);
        vertex(buffer, x1, y1, tl);
        vertex(buffer, x1, y2, bl);
        vertex(buffer, x2, y2, br);
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private static void vertex(WorldRenderer buffer, float x, float y, int c) {
        buffer.pos(x, y, 0.0D).color(c >> 16 & 255, c >> 8 & 255, c & 255, c >>> 24).endVertex();
    }
}
