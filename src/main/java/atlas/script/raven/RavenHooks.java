package atlas.script.raven;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * What the Raven API (static, as Raven's is) leaves for the script dispatcher to apply (2026-11-02): the look a
 * script asked for this tick, whether it wants the movement fix, and the world's view matrices for
 * render.worldToScreen. Not part of the API scripts see.
 */
public final class RavenHooks {
    private RavenHooks() {
    }

    /** client.setRotations / setYaw / setPitch this tick; taken (and cleared) at the tick's look. */
    public static volatile Float wantedYaw;
    public static volatile Float wantedPitch;
    /** client.enableMovementFix: the look moves the keys with it. */
    public static volatile boolean movementFix;
    /** The look scripts got last tick, for getServerYaw / getServerPitch; null when they asked for none. */
    public static volatile Float appliedYaw;
    public static volatile Float appliedPitch;
    /** A script has turned Raven's SafeWalk off: BridgeAssist, which is that SafeWalk's edge crouch, is paused. */
    public static volatile boolean edgeCrouchHeld;
    /** Whether Atlas's own SafeWalk was on when that happened, to go back to. */
    public static volatile boolean safeWalkWasOn;

    static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    static volatile boolean matrices;

    /** At the world's render (Render3DEvent), before any script draws: the matrices worldToScreen projects with. */
    public static void captureMatrices() {
        MODELVIEW.clear();
        PROJECTION.clear();
        VIEWPORT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        matrices = true;
    }

    /** A new world: nothing carried over. */
    public static void reset() {
        wantedYaw = null;
        wantedPitch = null;
        appliedYaw = null;
        appliedPitch = null;
    }
}
