package atlas.util.shader;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import atlas.util.RenderUtil;
import atlas.util.render.FramebufferCompat;

public class BlurUtils {
    private static Framebuffer stencilFrameBufferBlur = new Framebuffer(1, 1, false);
    private static Framebuffer stencilFrameBufferBloom = new Framebuffer(1, 1, false);

    private static boolean blurMasked;
    private static boolean bloomMasked;

    public static void prepareBlur() {
        if (!FramebufferCompat.available()) {
            blurMasked = true;
            GlStateManager.colorMask(false, false, false, false);
            return;
        }
        blurMasked = false;
        stencilFrameBufferBlur = RenderUtil.createFrameBuffer(stencilFrameBufferBlur);
        stencilFrameBufferBlur.framebufferClear();
        stencilFrameBufferBlur.bindFramebuffer(false);
    }

    public static void prepareBloom() {
        if (!FramebufferCompat.available()) {
            bloomMasked = true;
            GlStateManager.colorMask(false, false, false, false);
            return;
        }
        bloomMasked = false;
        stencilFrameBufferBloom = RenderUtil.createFrameBuffer(stencilFrameBufferBloom);
        stencilFrameBufferBloom.framebufferClear();
        stencilFrameBufferBloom.bindFramebuffer(false);
    }

    public static void blurEnd(int passes, float radius) {
        if (blurMasked) {
            blurMasked = false;
            GlStateManager.colorMask(true, true, true, true);
            return;
        }
        stencilFrameBufferBlur.unbindFramebuffer();
        KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture, passes, radius);
    }

    public static void bloomEnd(int passes, float radius) {
        bloomEnd(passes, radius, 1.0F);
    }

    public static void bloomEnd(int passes, float radius, float strength) {
        if (bloomMasked) {
            bloomMasked = false;
            GlStateManager.colorMask(true, true, true, true);
            return;
        }
        stencilFrameBufferBloom.unbindFramebuffer();
        KawaseBloom.renderBlur(stencilFrameBufferBloom.framebufferTexture, passes, radius, strength);
    }

    public static void prepareRiseBloom() {
        RiseBloomShader.prepareBloom();
    }

    public static void riseBloomEnd(int radius, float compression) {
        RiseBloomShader.bloomEnd(radius, compression);
    }
}
