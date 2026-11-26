package atlas.util.shader;

import atlas.util.RenderUtil;
import atlas.util.render.Effects;
import atlas.util.render.FramebufferCompat;
import atlas.util.render.GlScissor;
import atlas.util.render.GpuTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

public class BlurUtils {
    private static Framebuffer stencilFrameBufferBlur = new Framebuffer(1, 1, false);
    private static Framebuffer stencilFrameBufferBloom = new Framebuffer(1, 1, false);

    private static boolean blurMasked;
    private static boolean bloomMasked;

    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);

    private static final int NONE = 0;
    private static final int BLUR = 1;
    private static final int BLOOM = 2;

    private static int drawing = NONE;

    private static int[] declared;
    private static boolean unknown;
    private static String label;
    private static int gpuMark = -1;

    private static final int FULL_CLEAR_EVERY = 30;
    private static int[] blurDirty;
    private static int[] bloomDirty;
    private static int blurClears;
    private static int bloomClears;

    public static void prepareBlur() {
        if (!FramebufferCompat.available() || Effects.liteInGame()) {
            blurMasked = true;
            GlStateManager.colorMask(false, false, false, false);
            return;
        }
        blurMasked = false;
        Effects.drawn();
        begin(BLUR);
        Framebuffer before = stencilFrameBufferBlur;
        stencilFrameBufferBlur = RenderUtil.createFrameBuffer(stencilFrameBufferBlur);
        boolean whole = stencilFrameBufferBlur != before || ++blurClears % FULL_CLEAR_EVERY == 0;
        clear(stencilFrameBufferBlur, whole ? null : blurDirty);
        blurDirty = null;
        stencilFrameBufferBlur.bindFramebuffer(false);
    }

    public static void prepareBloom() {
        if (!FramebufferCompat.available() || Effects.liteInGame()) {
            bloomMasked = true;
            GlStateManager.colorMask(false, false, false, false);
            return;
        }
        bloomMasked = false;
        Effects.drawn();
        begin(BLOOM);
        Framebuffer before = stencilFrameBufferBloom;
        stencilFrameBufferBloom = RenderUtil.createFrameBuffer(stencilFrameBufferBloom);
        boolean whole = stencilFrameBufferBloom != before || ++bloomClears % FULL_CLEAR_EVERY == 0;
        clear(stencilFrameBufferBloom, whole ? null : bloomDirty);
        bloomDirty = null;
        stencilFrameBufferBloom.bindFramebuffer(false);
    }

    public static void region(String name, float x1, float y1, float x2, float y2) {
        if (drawing == NONE) {
            return;
        }
        label = name;
        if (unknown) {
            return;
        }
        int[] r = windowRect(x1, y1, x2, y2);
        if (r == null) {
            unknown = true;
            declared = null;
            return;
        }
        declared = BlurRegion.union(declared, r);
    }

    public static void blurEnd(int passes, float radius) {
        if (blurMasked) {
            blurMasked = false;
            GlStateManager.colorMask(true, true, true, true);
            return;
        }
        int[] known = known();
        int[] area = known != null && fillsScreen(stencilFrameBufferBlur) ? known : null;
        stencilFrameBufferBlur.unbindFramebuffer();
        try {
            KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture, passes, radius, area);
        } finally {
            blurDirty = known;
            end((label == null ? "Other" : label) + " blur");
        }
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
        int[] known = known();
        int[] area = known != null && fillsScreen(stencilFrameBufferBloom) ? known : null;
        stencilFrameBufferBloom.unbindFramebuffer();
        try {
            KawaseBloom.renderBlur(stencilFrameBufferBloom.framebufferTexture, passes, radius, strength, area);
        } finally {
            bloomDirty = known;
            end((label == null ? "Other" : label) + " glow");
        }
    }

    public static void prepareRiseBloom() {
        RiseBloomShader.prepareBloom();
    }

    public static void riseBloomEnd(int radius, float compression) {
        RiseBloomShader.bloomEnd(radius, compression);
    }

    private static void begin(int kind) {
        drawing = kind;
        declared = null;
        unknown = false;
        label = null;
        gpuMark = GpuTimer.begin(kind == BLOOM ? "glow" : "blur");
    }

    private static void clear(Framebuffer mask, int[] dirty) {
        GlScissor saved = GlScissor.save();
        try {
            if (dirty == null) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            } else {
                int[] r = BlurRegion.clamp(dirty, mask.framebufferWidth, mask.framebufferHeight);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GlScissor.set(r == null ? new int[]{0, 0, 0, 0} : r);
            }
            mask.framebufferClear();
        } finally {
            saved.restore();
        }
    }

    private static int[] known() {
        if (declared == null || unknown || label == null || !Effects.fastBlur()) {
            return null;
        }
        return declared;
    }

    private static boolean fillsScreen(Framebuffer mask) {
        float[][] m = matrices();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int[] viewport = {0, 0, mask.framebufferWidth, mask.framebufferHeight};
        return BlurRegion.fillsViewport(m[0], m[1], viewport, sr.getScaledWidth_double(), sr.getScaledHeight_double());
    }

    private static float[][] matrices() {
        MODELVIEW.clear();
        PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        float[] modelview = new float[16];
        float[] projection = new float[16];
        for (int i = 0; i < 16; i++) {
            modelview[i] = MODELVIEW.get(i);
            projection[i] = PROJECTION.get(i);
        }
        return new float[][]{modelview, projection};
    }

    private static void end(String name) {
        GpuTimer.end(gpuMark, name);
        gpuMark = -1;
        drawing = NONE;
        declared = null;
        unknown = false;
        label = null;
    }

    private static int[] windowRect(float x1, float y1, float x2, float y2) {
        Framebuffer mask = drawing == BLOOM ? stencilFrameBufferBloom : stencilFrameBufferBlur;
        float[][] m = matrices();
        int[] viewport = {0, 0, mask.framebufferWidth, mask.framebufferHeight};
        return BlurRegion.maskRect(m[0], m[1], viewport, x1, y1, x2, y2);
    }
}
