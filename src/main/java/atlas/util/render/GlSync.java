package atlas.util.render;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.nio.FloatBuffer;

public final class GlSync {
    private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(16);

    private GlSync() {
    }

    public static void afterPopAttrib() {
        flag(GL11.glIsEnabled(GL11.GL_BLEND), GlStateManager::enableBlend, GlStateManager::disableBlend);
        flag(GL11.glIsEnabled(GL11.GL_TEXTURE_2D), GlStateManager::enableTexture2D, GlStateManager::disableTexture2D);
        flag(GL11.glIsEnabled(GL11.GL_ALPHA_TEST), GlStateManager::enableAlpha, GlStateManager::disableAlpha);
        flag(GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GlStateManager::enableDepth, GlStateManager::disableDepth);
        flag(GL11.glIsEnabled(GL11.GL_CULL_FACE), GlStateManager::enableCull, GlStateManager::disableCull);
        flag(GL11.glIsEnabled(GL11.GL_LIGHTING), GlStateManager::enableLighting, GlStateManager::disableLighting);

        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int other = srcRgb == GL11.GL_ZERO ? GL11.GL_ONE : GL11.GL_ZERO;
        GlStateManager.tryBlendFuncSeparate(other, other, other, other);
        GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);

        int alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        GlStateManager.alphaFunc(alphaFunc == GL11.GL_NEVER ? GL11.GL_ALWAYS : GL11.GL_NEVER, alphaRef);
        GlStateManager.alphaFunc(alphaFunc, alphaRef);

        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GlStateManager.depthMask(!depthMask);
        GlStateManager.depthMask(depthMask);

        int shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        GlStateManager.shadeModel(shade == GL11.GL_FLAT ? GL11.GL_SMOOTH : GL11.GL_FLAT);
        GlStateManager.shadeModel(shade);

        FLOATS.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, FLOATS);
        float r = FLOATS.get(0);
        float g = FLOATS.get(1);
        float b = FLOATS.get(2);
        float a = FLOATS.get(3);
        GlStateManager.color(1.0F - r, g, b, a);
        GlStateManager.color(r, g, b, a);
    }

    private static void flag(boolean on, Runnable enable, Runnable disable) {
        if (on) {
            disable.run();
            enable.run();
        } else {
            enable.run();
            disable.run();
        }
    }
}
