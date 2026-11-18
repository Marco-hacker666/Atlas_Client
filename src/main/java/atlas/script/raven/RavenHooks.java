package atlas.script.raven;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class RavenHooks {
    private RavenHooks() {
    }

    public static volatile Float wantedYaw;
    public static volatile Float wantedPitch;

    public static volatile boolean movementFix;

    public static volatile Float appliedYaw;
    public static volatile Float appliedPitch;

    public static volatile boolean edgeCrouchHeld;

    public static volatile boolean safeWalkWasOn;

    static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    static volatile boolean matrices;

    public static void captureMatrices() {
        MODELVIEW.clear();
        PROJECTION.clear();
        VIEWPORT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        matrices = true;
    }

    public static void reset() {
        wantedYaw = null;
        wantedPitch = null;
        appliedYaw = null;
        appliedPitch = null;
    }
}
