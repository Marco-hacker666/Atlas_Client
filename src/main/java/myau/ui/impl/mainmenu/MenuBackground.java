package myau.ui.impl.mainmenu;

import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * The main-menu background (2026-10-12; it replaces the six of
 * BackgroundRenderer and its picker, removed at the owner's request).
 *
 * Vanilla's title panorama sliding slowly, blurred (when shaders are
 * allowed), graded to a sunrise -- dusk purple above, orange glow below, a
 * sun bloom, dark on the left where the menu is -- with drifting motes of
 * light. SetupScreen draws the same background as the menu.
 *
 * 2026-10-13: the AURORA look (a fragment shader) and the pill that chose
 * between the two were removed at the owner's request; CINEMATIC is the
 * only one, and config/Myau/menu-style.txt is no longer read.
 */
public final class MenuBackground {
    private static final long START = System.currentTimeMillis();

    private static final ResourceLocation[] PANORAMA = {
            new ResourceLocation("textures/gui/title/background/panorama_0.png"),
            new ResourceLocation("textures/gui/title/background/panorama_1.png"),
            new ResourceLocation("textures/gui/title/background/panorama_2.png"),
            new ResourceLocation("textures/gui/title/background/panorama_3.png"),
            new ResourceLocation("textures/gui/title/background/panorama_4.png"),
            new ResourceLocation("textures/gui/title/background/panorama_5.png")
    };

    private MenuBackground() {
    }

    /** The background for a screen of {@code width} x {@code height} GUI pixels. */
    public static void draw(int width, int height, float mouseX, float mouseY) {
        drawCinematic(width, height);
    }

    private static float seconds() {
        return (System.currentTimeMillis() - START) / 1000.0F;
    }

    // ---- cinematic -----------------------------------------------------

    private static void drawCinematic(int width, int height) {
        drawPanoramaStrip(width, height);
        if (myau.module.modules.RenderFixes.shouldUseShaders()) {
            myau.util.shader.BlurUtils.prepareBlur();
            rect(0.0F, 0.0F, width, height, 0xFFFFFFFF);
            myau.util.shader.BlurUtils.blurEnd(1, 1.0F);
        }
        /* Dusk above, a warm glow below. */
        gradient(0, 0, width, height, 0x50140A24, 0x50140A24, 0x38FF8A3C, 0x38FF8A3C);
        /* The sun, low and to the right: one smooth radial glow. */
        glow(width * 0.70F, height * 0.62F, height * 0.75F, height * 0.55F, 0x48FFC888);
        glow(width * 0.70F, height * 0.62F, height * 0.16F, height * 0.13F, 0x60FFF0D0);
        /* Dark on the left, where the menu reads. */
        gradient(0, 0, width * 0.55F, height, 0xB806040A, 0x0006040A, 0xB806040A, 0x0006040A);
        /* Motes of light drifting up. */
        float t = seconds();
        for (int i = 0; i < 46; i++) {
            float speed = 6.0F + (i % 5) * 3.0F;
            float px = ((i * 137.5F + t * (4.0F + i % 3)) % (width + 20)) - 10;
            float py = height - ((i * 61.0F + t * speed) % (height * 0.9F));
            float tw = MathHelper.sin(t * 1.3F + i);
            rect(px, py, px + 1.0F, py + 1.0F, ((int) ((0.3F + 0.45F * tw * tw) * 255)) << 24 | 0xFFDCAA);
        }
        /* No letterbox bars (owner, 2026-10-12): the picture runs to the edges. */
    }

    /** A filled rectangle, with texturing off (RenderUtil.drawRect leaves it as it finds it). */
    static void rect(float x1, float y1, float x2, float y2, int color) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderUtil.drawRect(x1, y1, x2, y2, color);
        GlStateManager.enableTexture2D();
    }

    /** An elliptical glow: {@code color} at the centre fading to nothing at the rim. */
    private static void glow(float cx, float cy, float rx, float ry, int color) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 1);
        GlStateManager.shadeModel(7425);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(6, DefaultVertexFormats.POSITION_COLOR);
        vertex(wr, cx, cy, color);
        for (int i = 0; i <= 64; i++) {
            double a = Math.PI * 2.0 * i / 64.0;
            vertex(wr, (float) (cx + Math.cos(a) * rx), (float) (cy + Math.sin(a) * ry), color & 0xFFFFFF);
        }
        tessellator.draw();
        GlStateManager.shadeModel(7424);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableTexture2D();
    }

    /**
     * Vanilla's title panorama as a flat strip: the four side faces in a
     * row (each one's edges meet the next one's), tall enough to fill the
     * screen, sliding slowly to the left forever. Flat and 2D, so nothing
     * about the GUI's projection or depth state can hide it.
     */
    private static void drawPanoramaStrip(int width, int height) {
        Minecraft mc = Minecraft.getMinecraft();
        float size = Math.max(height * 1.25F, width / 2.0F);
        float strip = size * 4.0F;
        float offset = (seconds() * 6.0F) % strip;
        float top = (height - size) / 2.0F - height * 0.06F;
        GlStateManager.enableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int first = (int) Math.floor(offset / size);
        for (int k = 0; k * size - (offset - first * size) < width; k++) {
            int face = (first + k) % 4;
            float x = k * size - (offset - first * size);
            mc.getTextureManager().bindTexture(PANORAMA[face]);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            /* Clamped, and half a texel in: with wrapping, linear filtering
               pulled the far edge's sky into each border as a white line. */
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            double e = 0.5D / 256.0D;
            wr.begin(7, DefaultVertexFormats.POSITION_TEX);
            wr.pos(x, top + size, 0.0D).tex(e, 1.0D - e).endVertex();
            wr.pos(x + size + 0.5F, top + size, 0.0D).tex(1.0D - e, 1.0D - e).endVertex();
            wr.pos(x + size + 0.5F, top, 0.0D).tex(1.0D - e, e).endVertex();
            wr.pos(x, top, 0.0D).tex(e, e).endVertex();
            tessellator.draw();
        }
        GlStateManager.enableAlpha();
    }

    /** A quad with a colour at each corner: top-left, top-right, bottom-left, bottom-right. */
    private static void gradient(float x1, float y1, float x2, float y2, int tl, int tr, int bl, int br) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.shadeModel(7425);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        vertex(wr, x2, y1, tr);
        vertex(wr, x1, y1, tl);
        vertex(wr, x1, y2, bl);
        vertex(wr, x2, y2, br);
        tessellator.draw();
        GlStateManager.shadeModel(7424);
        GlStateManager.enableTexture2D();
    }

    private static void vertex(WorldRenderer wr, float x, float y, int c) {
        wr.pos(x, y, 0.0D).color(c >> 16 & 255, c >> 8 & 255, c & 255, c >>> 24).endVertex();
    }

}
