package atlas.util.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;

public final class GlScissor {
    private static final IntBuffer BOX = BufferUtils.createIntBuffer(16);

    private final boolean enabled;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    private GlScissor(boolean enabled, int x, int y, int width, int height) {
        this.enabled = enabled;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static GlScissor save() {
        boolean enabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        BOX.clear();
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, BOX);
        return new GlScissor(enabled, BOX.get(0), BOX.get(1), BOX.get(2), BOX.get(3));
    }

    public boolean enabled() {
        return this.enabled;
    }

    public static void set(int[] r) {
        GL11.glScissor(r[0], r[1], Math.max(0, r[2] - r[0]), Math.max(0, r[3] - r[1]));
    }

    public void restore() {
        GL11.glScissor(this.x, this.y, Math.max(0, this.width), Math.max(0, this.height));
        if (this.enabled) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }
}
