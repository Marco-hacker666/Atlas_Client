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

public class KawaseBlur {
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
                kawaseDown = new ShaderUtils("kawaseDown");
            } catch (Exception e) {
                System.err.println("Failed to initialize kawaseDown shader: " + e.getMessage());
                e.printStackTrace();
            }
        }
        if (kawaseUp == null) {
            try {
                kawaseUp = new ShaderUtils("kawaseUp");
            } catch (Exception e) {
                System.err.println("Failed to initialize kawaseUp shader: " + e.getMessage());
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

    private static void initFrameBuffers(float iterations) {
        for (Framebuffer framebuffer : framebufferList) {
            framebuffer.deleteFramebuffer();
        }
        framebufferList.clear();

        framebufferList.add(framebuffer = RenderUtil.createFrameBuffer(null));

        for (int i = 1; i <= iterations; i++) {
            Framebuffer currentBuffer = new Framebuffer((int) (mc.displayWidth / Math.pow(3, i)), (int) (mc.displayHeight / Math.pow(3, i)), false);
            currentBuffer.setFramebufferFilter(GL_LINEAR);
            GlStateManager.bindTexture(currentBuffer.framebufferTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
            GlStateManager.bindTexture(0);

            framebufferList.add(currentBuffer);
        }
    }

    public static void renderBlur(int stencilFrameBufferTexture, int iterations, float offset) {
        renderBlur(stencilFrameBufferTexture, iterations, offset, null);
    }

    /**
     * @param region where the mask is, window pixels {x0, y0, x1, y1} (see BlurRegion), or null for the whole
     *               screen. With a region every pass only draws the pixels that region needs, so the blur
     *               costs what the element covers instead of the screen; what it draws is the same.
     */
    public static void renderBlur(int stencilFrameBufferTexture, int iterations, float offset, int[] region) {
        initShaders();

        if (kawaseDown == null || kawaseUp == null) {
            System.err.println("KawaseBlur shaders not initialized, skipping blur render");
            /* The mask framebuffer was just unbound: draw on into the game's framebuffer, not the window's. */
            mc.getFramebuffer().bindFramebuffer(true);
            return;
        }

        /* Level i is always displayWidth / 3^i, so one chain serves every pass count; it is only rebuilt for a
           new size or more levels (it used to be rebuilt whenever two callers asked for different counts). */
        if (currentIterations < iterations || framebuffer.framebufferWidth != mc.displayWidth || framebuffer.framebufferHeight != mc.displayHeight) {
            initFrameBuffers(Math.max(iterations, currentIterations));
            currentIterations = Math.max(iterations, currentIterations);
        } else if (iterations != lastIterations) {
            /* Where the chain used to be rebuilt: creating framebuffers turns the depth test on, so keep leaving
               that, and whatever is drawn next sees the state it saw before. */
            GlStateManager.enableDepth();
        }
        lastIterations = iterations;

        int[][] plan = region == null ? null : plan(iterations, offset, region);
        atlas.util.render.GlScissor saved = plan == null ? null : atlas.util.render.GlScissor.save();
        if (saved != null && saved.enabled()) {
            /* Drawn inside someone's scissor clip: the whole-screen path, which that clip applies to as before. */
            plan = null;
            saved = null;
        }
        try {
            if (plan != null) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                atlas.util.render.GlScissor.set(plan[1]);
            }
            renderFBO(framebufferList.get(1), mc.getFramebuffer().framebufferTexture, kawaseDown, offset);

            for (int i = 1; i < iterations; i++) {
                if (plan != null) {
                    atlas.util.render.GlScissor.set(plan[i + 1]);
                }
                renderFBO(framebufferList.get(i + 1), framebufferList.get(i).framebufferTexture, kawaseDown, offset);
            }

            for (int i = iterations; i > 1; i--) {
                if (plan != null) {
                    atlas.util.render.GlScissor.set(plan[i - 1]);
                }
                renderFBO(framebufferList.get(i - 1), framebufferList.get(i).framebufferTexture, kawaseUp, offset);
            }

            if (plan != null) {
                atlas.util.render.GlScissor.set(plan[0]);
            }
            finish(stencilFrameBufferTexture, offset);
        } finally {
            if (saved != null) {
                saved.restore();
            }
        }
    }

    /** The rectangles each pass draws; a region off screen still runs every pass, on no pixels. */
    private static int[][] plan(int iterations, float offset, int[] region) {
        int[] widths = new int[iterations + 1];
        int[] heights = new int[iterations + 1];
        for (int i = 0; i <= iterations; i++) {
            widths[i] = framebufferList.get(i).framebufferWidth;
            heights[i] = framebufferList.get(i).framebufferHeight;
        }
        int[][] plan = BlurRegion.plan(BlurRegion.BLUR, widths, heights, offset, region);
        return plan != null ? plan : BlurRegion.nothing(iterations);
    }

    private static void finish(int stencilFrameBufferTexture, float offset) {
        Framebuffer lastBuffer = framebufferList.get(0);
        lastBuffer.framebufferClear();
        lastBuffer.bindFramebuffer(false);

        kawaseUp.init();
        kawaseUp.setUniformf("offset", offset, offset);
        kawaseUp.setUniformi("inTexture", 0);
        kawaseUp.setUniformi("check", 1);
        kawaseUp.setUniformi("textureToCheck", 16);
        kawaseUp.setUniformf("halfpixel", 1.0f / lastBuffer.framebufferWidth, 1.0f / lastBuffer.framebufferHeight);
        kawaseUp.setUniformf("iResolution", lastBuffer.framebufferWidth, lastBuffer.framebufferHeight);

        RenderUtil.bindTextureOnUnit16(stencilFrameBufferTexture);
        RenderUtil.bindTexture(framebufferList.get(1).framebufferTexture);
        ShaderUtils.drawQuads();
        kawaseUp.unload();

        mc.getFramebuffer().bindFramebuffer(true);
        RenderUtil.bindTexture(framebufferList.get(0).framebufferTexture);
        RenderUtil.setAlphaLimit(0);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        ShaderUtils.drawQuads();
        GlStateManager.bindTexture(0);
        GlStateManager.disableBlend();
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
