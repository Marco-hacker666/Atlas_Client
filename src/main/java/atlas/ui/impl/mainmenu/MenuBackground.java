package atlas.ui.impl.mainmenu;

import atlas.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

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

    public static void draw(int width, int height, float mouseX, float mouseY) {
        drawCinematic(width, height);
    }

    private static float seconds() {
        return (System.currentTimeMillis() - START) / 1000.0F;
    }

    private static void drawCinematic(int width, int height) {
        drawPanoramaStrip(width, height);
        if (atlas.module.modules.RenderFixes.shouldUseShaders()) {
            atlas.util.shader.BlurUtils.prepareBlur();
            rect(0.0F, 0.0F, width, height, 0xFFFFFFFF);
            atlas.util.shader.BlurUtils.blurEnd(1, 1.0F);
        }

        gradient(0, 0, width, height, 0x50140A24, 0x50140A24, 0x38FF8A3C, 0x38FF8A3C);

        glow(width * 0.72F, height * 0.70F, width * 0.55F, height * 0.60F, 0x34FFB070);

        gradient(0, 0, width * 0.55F, height, 0xB806040A, 0x0006040A, 0xB806040A, 0x0006040A);

        float t = seconds();
        for (int i = 0; i < 46; i++) {
            float speed = 6.0F + (i % 5) * 3.0F;
            float px = ((i * 137.5F + t * (4.0F + i % 3)) % (width + 20)) - 10;
            float py = height - ((i * 61.0F + t * speed) % (height * 0.9F));
            float tw = MathHelper.sin(t * 1.3F + i);
            rect(px, py, px + 1.0F, py + 1.0F, ((int) ((0.3F + 0.45F * tw * tw) * 255)) << 24 | 0xFFDCAA);
        }
    }

    static void rect(float x1, float y1, float x2, float y2, int color) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderUtil.drawRect(x1, y1, x2, y2, color);
        GlStateManager.enableTexture2D();
    }

    private static void glow(float cx, float cy, float rx, float ry, int color) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 1);
        GlStateManager.shadeModel(7425);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int alpha = color >>> 24;
        int rgb = color & 0xFFFFFF;
        int rings = 8;
        for (int r = 0; r < rings; r++) {
            float f0 = (float) r / rings;
            float f1 = (float) (r + 1) / rings;
            int a0 = Math.round(alpha * (float) Math.exp(-4.0 * f0 * f0) * (1.0F - f0));
            int a1 = Math.round(alpha * (float) Math.exp(-4.0 * f1 * f1) * (1.0F - f1));
            wr.begin(5, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= 64; i++) {
                double a = Math.PI * 2.0 * i / 64.0;
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                vertex(wr, cx + cos * rx * f0, cy + sin * ry * f0, a0 << 24 | rgb);
                vertex(wr, cx + cos * rx * f1, cy + sin * ry * f1, a1 << 24 | rgb);
            }
            tessellator.draw();
        }
        GlStateManager.shadeModel(7424);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableTexture2D();
    }

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
