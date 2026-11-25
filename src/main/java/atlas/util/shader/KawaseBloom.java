package atlas.util.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import atlas.util.RenderUtil;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class KawaseBloom {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<Framebuffer> framebufferList = new ArrayList<>();
    public static Framebuffer framebuffer = new Framebuffer(1, 1, false);
    private static ShaderUtils kawaseDown;
    private static ShaderUtils kawaseUp;
    private static int currentIterations;
    private static int lastIterations;

    private static void initShaders() {
        if (kawaseDown == null) {
            try {
                kawaseDown = new ShaderUtils("kawaseDownBloom");
            } catch (Exception e) {
                System.err.println("Failed to initialize kawaseDownBloom shader: " + e.getMessage());
                e.printStackTrace();
            }
        }
        if (kawaseUp == null) {
            try {
                kawaseUp = new ShaderUtils("kawaseUpBloom");
            } catch (Exception e) {
                System.err.println("Failed to initialize kawaseUpBloom shader: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static ShaderUtils getKawaseDown() {
        initShaders();
        return kawaseDown;
    }

    private static ShaderUtils getKawaseUp() {
        initShaders();
        return kawaseUp;
    }

    private static void initFramebuffers(float iterations) {
        for (Framebuffer framebuffer : framebufferList) {
            framebuffer.deleteFramebuffer();
        }
        framebufferList.clear();

        framebufferList.add(framebuffer = RenderUtil.createFrameBuffer(null, false));

        for (int i = 1; i <= iterations; i++) {
            Framebuffer currentBuffer = new Framebuffer((int) (mc.displayWidth / Math.pow(2, i)), (int) (mc.displayHeight / Math.pow(2, i)), false);
            currentBuffer.setFramebufferFilter(GL_LINEAR);

            GlStateManager.bindTexture(currentBuffer.framebufferTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
            GlStateManager.bindTexture(0);

            framebufferList.add(currentBuffer);
        }
    }

    public static void renderBlur(int framebufferTexture, int iterations, float offset) {
        renderBlur(framebufferTexture, iterations, offset, 1.0F);
    }

    public static void renderBlur(int framebufferTexture, int iterations, float offset, float strength) {
        renderBlur(framebufferTexture, iterations, offset, strength, null);
    }

    /**
     * @param region where the glow's shapes are, window pixels {x0, y0, x1, y1} (see BlurRegion), or null for
     *               the whole screen. With a region every pass only draws what the shapes and their glow need;
     *               what it draws is the same.
     */
    public static void renderBlur(int framebufferTexture, int iterations, float offset, float strength, int[] region) {
        initShaders();

        if (kawaseDown == null || kawaseUp == null) {
            System.err.println("KawaseBloom shaders not initialized, skipping bloom render");
            /* The mask framebuffer was just unbound: draw on into the game's framebuffer, not the window's. */
            mc.getFramebuffer().bindFramebuffer(true);
            return;
        }

        /* Level i is always displayWidth / 2^i, so one chain serves every pass count (the target HUD's health
           bar asks for 4 and everything else for 3: the chain used to be rebuilt on every such switch). */
        if (currentIterations < iterations || (framebuffer.framebufferWidth != mc.displayWidth || framebuffer.framebufferHeight != mc.displayHeight)) {
            initFramebuffers(Math.max(iterations, currentIterations));
            currentIterations = Math.max(iterations, currentIterations);
        } else if (iterations != lastIterations) {
            /* Where the chain used to be rebuilt: creating framebuffers turns the depth test on, so keep leaving
               that, and whatever is drawn next sees the state it saw before. */
            GlStateManager.enableDepth();
        }
        lastIterations = iterations;

        RenderUtil.setAlphaLimit(0);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_ONE, GL_ONE);

        GlStateManager.clearColor(0, 0, 0, 0);

        int[][] plan = region == null ? null : plan(iterations, offset, region);
        atlas.util.render.GlScissor saved = atlas.util.render.GlScissor.save();
        /* Drawn inside someone's scissor clip: the whole-screen path. Its clip is in screen pixels, which mean
           other places in the smaller levels, so the level passes run unclipped (every level texel fresh, none
           left from an earlier blur) and the clip comes back for the last pass and the composite. */
        boolean clipped = saved.enabled();
        if (clipped) {
            plan = null;
        }
        try {
            if (plan != null) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                atlas.util.render.GlScissor.set(plan[1]);
            } else if (clipped) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            float currentOffset = offset;
            renderFBO(framebufferList.get(1), framebufferTexture, kawaseDown, currentOffset);

            for (int i = 1; i < iterations; i++) {
                if (plan != null) {
                    atlas.util.render.GlScissor.set(plan[i + 1]);
                }
                currentOffset = offset / (float) Math.pow(1.5, i);
                renderFBO(framebufferList.get(i + 1), framebufferList.get(i).framebufferTexture, kawaseDown, currentOffset);
            }

            for (int i = iterations; i > 1; i--) {
                if (plan != null) {
                    atlas.util.render.GlScissor.set(plan[i - 1]);
                }
                currentOffset = offset / (float) Math.pow(1.5, i - 1);
                renderFBO(framebufferList.get(i - 1), framebufferList.get(i).framebufferTexture, kawaseUp, currentOffset);
            }

            if (plan != null) {
                atlas.util.render.GlScissor.set(plan[0]);
            } else if (clipped) {
                saved.restore();
            }
            finish(framebufferTexture, offset, strength);
        } finally {
            saved.restore();
        }
    }

    /** The rectangles each pass draws; shapes off screen still run every pass, on no pixels. */
    private static int[][] plan(int iterations, float offset, int[] region) {
        int[] widths = new int[iterations + 1];
        int[] heights = new int[iterations + 1];
        for (int i = 0; i <= iterations; i++) {
            widths[i] = framebufferList.get(i).framebufferWidth;
            heights[i] = framebufferList.get(i).framebufferHeight;
        }
        int[][] plan = BlurRegion.plan(BlurRegion.BLOOM, widths, heights, offset, region);
        return plan != null ? plan : BlurRegion.nothing(iterations);
    }

    private static void finish(int framebufferTexture, float offset, float strength) {
        Framebuffer lastBuffer = framebufferList.get(0);
        lastBuffer.framebufferClear();
        lastBuffer.bindFramebuffer(false);
        kawaseUp.init();
        kawaseUp.setUniformf("offset", offset, offset);
        kawaseUp.setUniformi("inTexture", 0);
        kawaseUp.setUniformi("check", 1);
        kawaseUp.setUniformi("textureToCheck", 16);
        kawaseUp.setUniformf("strength", Math.max(0.0F, Math.min(1.0F, strength)));
        kawaseUp.setUniformf("halfpixel", 1.0f / lastBuffer.framebufferWidth, 1.0f / lastBuffer.framebufferHeight);
        kawaseUp.setUniformf("iResolution", lastBuffer.framebufferWidth, lastBuffer.framebufferHeight);

        RenderUtil.bindTextureOnUnit16(framebufferTexture);
        RenderUtil.bindTexture(framebufferList.get(1).framebufferTexture);
        ShaderUtils.drawQuads();
        kawaseUp.unload();

        GlStateManager.clearColor(0, 0, 0, 0);
        mc.getFramebuffer().bindFramebuffer(false);
        RenderUtil.bindTexture(framebufferList.get(0).framebufferTexture);
        RenderUtil.setAlphaLimit(0);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        ShaderUtils.drawQuads();
        GlStateManager.bindTexture(0);
        RenderUtil.setAlphaLimit(0);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void renderFBO(Framebuffer framebuffer, int framebufferTexture, ShaderUtils shader, float offset) {
        if (shader == null) return;
        framebuffer.framebufferClear();
        framebuffer.bindFramebuffer(false);
        shader.init();
        RenderUtil.bindTexture(framebufferTexture);
        shader.setUniformf("offset", offset, offset);
        shader.setUniformi("inTexture", 0);
        shader.setUniformi("check", 0);
        shader.setUniformf("halfpixel", 1.0f / framebuffer.framebufferWidth, 1.0f / framebuffer.framebufferHeight);
        shader.setUniformf("iResolution", framebuffer.framebufferWidth, framebuffer.framebufferHeight);
        ShaderUtils.drawQuads();
        shader.unload();
    }
}
