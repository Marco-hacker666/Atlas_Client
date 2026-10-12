package myau.ui.impl.mainmenu;

import myau.util.RenderUtil;
import myau.util.shader.ShaderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The two main-menu backgrounds (2026-10-12; they replace the six of
 * BackgroundRenderer and its picker, removed at the owner's request).
 *
 * AURORA: one fragment shader -- a night sky, twinkling stars, four
 * curtains of aurora (green, blue, purple, teal) drifting on sines and
 * noise, leaning a little with the pointer, dark toward the horizon.
 *
 * CINEMATIC: vanilla's title panorama turning slowly, blurred (when shaders
 * are allowed), graded to a sunrise -- dusk purple above, orange glow
 * below, a sun bloom, dark on the left where the menu is -- with drifting
 * motes of light and letterbox bars.
 *
 * The choice is kept in config/Myau/menu-style.txt. SetupScreen draws the
 * same background as the menu.
 */
public final class MenuBackground {
    public static final int AURORA = 0;
    public static final int CINEMATIC = 1;
    private static final File FILE = new File("./config/Myau/menu-style.txt");
    private static final long START = System.currentTimeMillis();

    private static int style = -1;
    private static ShaderUtil aurora;
    private static boolean auroraFailed;

    private static final ResourceLocation[] PANORAMA = {
            new ResourceLocation("textures/gui/title/background/panorama_0.png"),
            new ResourceLocation("textures/gui/title/background/panorama_1.png"),
            new ResourceLocation("textures/gui/title/background/panorama_2.png"),
            new ResourceLocation("textures/gui/title/background/panorama_3.png"),
            new ResourceLocation("textures/gui/title/background/panorama_4.png"),
            new ResourceLocation("textures/gui/title/background/panorama_5.png")
    };

    private static final String AURORA_SHADER =
            "#version 120\n"
            + "uniform float time;\n"
            + "uniform vec2 resolution;\n"
            + "uniform vec2 mouse;\n"
            + "float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
            + "float noise(vec2 p){ vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);\n"
            + "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y); }\n"
            + "float fbm(vec2 p){ float v = 0.0; float a = 0.5; for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; } return v; }\n"
            + "vec3 curtain(vec2 uv, float x, float fi, vec3 c){\n"
            + "  float t = time;\n"
            + "  float y0 = 0.63 - 0.055 * fi + (mouse.y - 0.5) * 0.03;\n"
            + "  float cv = y0 + 0.075 * sin(x * 1.6 + t * 0.16 * (1.0 + fi * 0.35) + fi * 1.7)\n"
            + "    + 0.03 * sin(x * 4.1 - t * 0.23 + fi * 2.1) + 0.05 * (fbm(vec2(x * 1.3 + t * 0.04, fi * 3.0)) - 0.5);\n"
            + "  float d = uv.y - cv;\n"
            + "  float band = exp(-max(d, 0.0) * 7.5) * smoothstep(-0.02, 0.0, d);\n"
            + "  float rays = 0.45 + 0.55 * fbm(vec2(x * 16.0 + fi * 7.0, t * 0.12 + fi));\n"
            + "  return c * band * rays;\n"
            + "}\n"
            + "void main(){\n"
            + "  vec2 uv = gl_FragCoord.xy / resolution.xy;\n"
            + "  float asp = resolution.x / resolution.y;\n"
            + "  vec3 col = mix(vec3(0.028, 0.080, 0.150), vec3(0.006, 0.010, 0.036), smoothstep(0.0, 1.0, uv.y));\n"
            + "  vec2 sp = floor(gl_FragCoord.xy / 2.0);\n"
            + "  float h = hash(sp);\n"
            + "  float star = step(0.9975, h) * (0.45 + 0.55 * sin(time * 1.7 + h * 80.0));\n"
            + "  col += star * vec3(0.85, 0.9, 1.0) * smoothstep(0.25, 0.7, uv.y);\n"
            + "  float x = uv.x * asp;\n"
            + "  vec3 a = vec3(0.0);\n"
            + "  a += curtain(uv, x + (mouse.x - 0.5) * 0.05, 0.0, vec3(0.20, 1.00, 0.70)) * 0.62;\n"
            + "  a += curtain(uv, x + (mouse.x - 0.5) * 0.08, 1.0, vec3(0.30, 0.50, 1.00)) * 0.45;\n"
            + "  a += curtain(uv, x + (mouse.x - 0.5) * 0.11, 2.0, vec3(0.70, 0.32, 1.00)) * 0.34;\n"
            + "  a += curtain(uv, x + (mouse.x - 0.5) * 0.14, 3.0, vec3(0.15, 0.90, 0.85)) * 0.26;\n"
            + "  col += a;\n"
            + "  col *= mix(0.30, 1.0, smoothstep(0.0, 0.38, uv.y));\n"
            + "  vec2 v = (uv - 0.5) * vec2(1.0, 1.25);\n"
            + "  col *= 1.0 - 0.42 * dot(v, v);\n"
            + "  gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    private MenuBackground() {
    }

    public static int style() {
        if (style < 0) {
            style = AURORA;
            try {
                if (FILE.isFile()) {
                    String s = new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8).trim();
                    style = "cinematic".equalsIgnoreCase(s) ? CINEMATIC : AURORA;
                }
            } catch (Exception ignored) {
            }
        }
        return style;
    }

    public static void setStyle(int next) {
        style = next == CINEMATIC ? CINEMATIC : AURORA;
        try {
            FILE.getParentFile().mkdirs();
            Files.write(FILE.toPath(), (style == CINEMATIC ? "cinematic" : "aurora").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** The background for a screen of {@code width} x {@code height} GUI pixels. */
    public static void draw(int width, int height, float mouseX, float mouseY) {
        if (style() == CINEMATIC) {
            drawCinematic(width, height);
        } else {
            drawAurora(width, height, mouseX, mouseY);
        }
    }

    private static float seconds() {
        return (System.currentTimeMillis() - START) / 1000.0F;
    }

    // ---- aurora --------------------------------------------------------

    private static void drawAurora(int width, int height, float mouseX, float mouseY) {
        if (aurora == null && !auroraFailed) {
            try {
                aurora = new ShaderUtil(AURORA_SHADER);
                if (aurora.getProgramID() == 0) {
                    aurora = null;
                    auroraFailed = true;
                }
            } catch (Exception e) {
                aurora = null;
                auroraFailed = true;
            }
        }
        if (aurora == null) {
            RenderUtil.drawGradientRect(0, 0, width, height, 0xFF05081A, 0xFF071526);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        try {
            aurora.init();
            aurora.setUniformf("time", seconds());
            aurora.setUniformf("resolution", (float) mc.displayWidth, (float) mc.displayHeight);
            aurora.setUniformf("mouse", width <= 0 ? 0.5F : mouseX / width, height <= 0 ? 0.5F : 1.0F - mouseY / height);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            wr.begin(7, DefaultVertexFormats.POSITION);
            wr.pos(0.0D, height, 0.0D).endVertex();
            wr.pos(width, height, 0.0D).endVertex();
            wr.pos(width, 0.0D, 0.0D).endVertex();
            wr.pos(0.0D, 0.0D, 0.0D).endVertex();
            tessellator.draw();
        } finally {
            aurora.unload();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
        }
    }

    // ---- cinematic -----------------------------------------------------

    private static void drawCinematic(int width, int height) {
        drawPanoramaStrip(width, height);
        if (myau.module.modules.RenderFixes.shouldUseShaders()) {
            myau.util.shader.BlurUtils.prepareBlur();
            rect(0.0F, 0.0F, width, height, 0xFFFFFFFF);
            myau.util.shader.BlurUtils.blurEnd(2, 2.5F);
        }
        /* Dusk above, a warm glow below. */
        gradient(0, 0, width, height, 0x90140A24, 0x90140A24, 0x60FF8A3C, 0x60FF8A3C);
        /* The sun, low and to the right: one smooth radial glow. */
        glow(width * 0.70F, height * 0.62F, height * 0.75F, height * 0.55F, 0x70FFC888);
        glow(width * 0.70F, height * 0.62F, height * 0.16F, height * 0.13F, 0x60FFF0D0);
        /* Dark on the left, where the menu reads. */
        gradient(0, 0, width * 0.62F, height, 0xD806040A, 0x0006040A, 0xD806040A, 0x0006040A);
        /* Motes of light drifting up. */
        float t = seconds();
        for (int i = 0; i < 46; i++) {
            float speed = 6.0F + (i % 5) * 3.0F;
            float px = ((i * 137.5F + t * (4.0F + i % 3)) % (width + 20)) - 10;
            float py = height - ((i * 61.0F + t * speed) % (height * 0.9F));
            float tw = MathHelper.sin(t * 1.3F + i);
            rect(px, py, px + 1.0F, py + 1.0F, ((int) ((0.3F + 0.45F * tw * tw) * 255)) << 24 | 0xFFDCAA);
        }
        /* Letterbox. */
        float bar = height * 0.08F;
        rect(0.0F, 0.0F, width, bar, 0xFF000000);
        rect(0.0F, height - bar, width, height, 0xFF000000);
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
