package atlas.util.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * How long the GPU spends on a frame and on parts of it (Atlas's 2D and 3D passes, each blur and glow), from
 * GL timestamp queries. Every fourth frame is measured (reading results back costs a driver call each), and a
 * measured frame is read when its slot comes round again, sixteen frames later, and only if the GPU is done
 * with it, so nothing ever waits on the GPU; a frame not ready by then is skipped. Off where the driver has no
 * timer queries (OpenGL below 3.3 without ARB_timer_query, the macOS legacy context, phones) or rejects them,
 * for good after any error, and while RenderFixes > gpu-timing is off.
 *
 * <p>Render thread only.
 */
public final class GpuTimer {
    private static final int FRAMES = 4;
    private static final int MARKS = 96;
    private static final int SAMPLE_EVERY = 4;
    private static final long RECENT_MS = 3000L;

    private static int state;
    private static String why = "not started";
    private static boolean core;
    private static int[][] ids;
    private static String[][] names;
    private static int[][] ends;
    private static int[] used;
    private static boolean[] pending;
    private static int slot = -1;
    private static boolean inFrame;
    private static int frameMark = -1;
    private static int frameCount;
    private static volatile BooleanSupplier allowed = () -> true;

    private static final IntBuffer INT = BufferUtils.createIntBuffer(16);
    private static final LongBuffer LONG = BufferUtils.createLongBuffer(16);

    private static final GpuStats WINDOW = new GpuStats();
    private static final GpuStats RECENT = new GpuStats();
    private static final GpuStats PROBE = new GpuStats();
    private static GpuStats lastRecent = new GpuStats();
    private static long recentSince;

    private GpuTimer() {
    }

    /** The setting that turns timing off (RenderFixes > gpu-timing), read at the start of every frame. */
    public static void setSwitch(BooleanSupplier on) {
        allowed = on == null ? () -> true : on;
    }

    private static boolean allowed() {
        try {
            return allowed.getAsBoolean();
        } catch (Throwable t) {
            return true;
        }
    }

    /** At the start of a frame (EntityRenderer.updateCameraAndRender). */
    public static void frameStart() {
        if (!allowed()) {
            frameEnd();
            return;
        }
        if (state == 0) {
            start();
        }
        if (state != 1) {
            return;
        }
        try {
            if (inFrame) {
                frameEnd();
            }
            if (frameCount++ % SAMPLE_EVERY != 0) {
                return;
            }
            slot = (slot + 1) % FRAMES;
            if (pending[slot]) {
                resolve(slot);
            }
            used[slot] = 0;
            pending[slot] = false;
            inFrame = true;
            frameMark = mark(GpuStats.FRAME);
        } catch (Throwable t) {
            off(t);
        }
    }

    /** At the end of a frame. */
    public static void frameEnd() {
        if (state != 1 || !inFrame) {
            return;
        }
        try {
            end(frameMark, null);
        } finally {
            inFrame = false;
            frameMark = -1;
        }
    }

    /**
     * Starts timing a part of the frame. Parts can nest and overlap.
     *
     * @return a token for {@link #end}, or -1 when not timing
     */
    public static int begin(String name) {
        if (state != 1 || !inFrame) {
            return -1;
        }
        try {
            int index = mark(name);
            return index < 0 ? -1 : slot * MARKS + index;
        } catch (Throwable t) {
            off(t);
            return -1;
        }
    }

    /** Ends a part; the name, if given, replaces the one given to begin. */
    public static void end(int token, String name) {
        if (state != 1 || !inFrame || token < 0 || token / MARKS != slot) {
            return;
        }
        try {
            int begin = token % MARKS;
            int end = mark(null);
            if (end < 0) {
                return;
            }
            ends[slot][begin] = end;
            if (name != null) {
                names[slot][begin] = name;
            }
        } catch (Throwable t) {
            off(t);
        }
    }

    public static boolean on() {
        return state == 1 && allowed();
    }

    public static String describe() {
        if (!allowed()) {
            return "off (RenderFixes > gpu-timing)";
        }
        return state == 1 ? "on (" + (core ? "GL 3.3" : "ARB_timer_query") + ")" : "off (" + why + ")";
    }

    /** Since the last perf window; resets it. */
    public static GpuStats takeWindow() {
        GpuStats out = WINDOW.copy();
        WINDOW.reset();
        return out;
    }

    /** The last few seconds. */
    public static GpuStats recent() {
        return lastRecent.copy();
    }

    /** A measurement the caller starts and reads (the .perf comparison). */
    public static void resetProbe() {
        PROBE.reset();
    }

    public static GpuStats probe() {
        return PROBE.copy();
    }

    private static void start() {
        try {
            if (MobileSafe.active()) {
                disable("phone or translation layer");
                return;
            }
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null || !caps.OpenGL15) {
                disable("no OpenGL 1.5 queries");
                return;
            }
            if (caps.OpenGL33) {
                core = true;
            } else if (caps.GL_ARB_timer_query) {
                core = false;
            } else {
                disable("the driver has no timer queries");
                return;
            }
            drainErrors();
            INT.clear();
            GL15.glGetQuery(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS, INT);
            if (GL11.glGetError() != GL11.GL_NO_ERROR || INT.get(0) <= 0) {
                drainErrors();
                disable("the driver's timestamps have no bits");
                return;
            }
            IntBuffer all = BufferUtils.createIntBuffer(FRAMES * MARKS);
            GL15.glGenQueries(all);
            /* One timestamp and an availability check (neither waits for the GPU): a driver that takes the
               calls but fails them would otherwise put a GL error in the log (or OptiFine's chat) on every
               measured frame. */
            int probe = all.get(0);
            if (core) {
                GL33.glQueryCounter(probe, GL33.GL_TIMESTAMP);
            } else {
                ARBTimerQuery.glQueryCounter(probe, ARBTimerQuery.GL_TIMESTAMP);
            }
            INT.clear();
            GL15.glGetQueryObject(probe, GL15.GL_QUERY_RESULT_AVAILABLE, INT);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                drainErrors();
                all.rewind();
                GL15.glDeleteQueries(all);
                drainErrors();
                disable("the driver rejected timer queries (GL error " + error + ")");
                return;
            }
            ids = new int[FRAMES][MARKS];
            names = new String[FRAMES][MARKS];
            ends = new int[FRAMES][MARKS];
            for (int f = 0; f < FRAMES; f++) {
                for (int m = 0; m < MARKS; m++) {
                    ids[f][m] = all.get(f * MARKS + m);
                }
            }
            used = new int[FRAMES];
            pending = new boolean[FRAMES];
            recentSince = System.currentTimeMillis();
            state = 1;
            why = "";
        } catch (Throwable t) {
            off(t);
        }
    }

    private static int mark(String name) {
        int index = used[slot];
        if (index >= MARKS) {
            return -1;
        }
        int id = ids[slot][index];
        if (core) {
            GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
        } else {
            ARBTimerQuery.glQueryCounter(id, ARBTimerQuery.GL_TIMESTAMP);
        }
        names[slot][index] = name;
        ends[slot][index] = -1;
        used[slot] = index + 1;
        pending[slot] = true;
        return index;
    }

    /** Reads a finished frame, if the GPU is done with it; otherwise counts it as skipped. Never waits. */
    private static void resolve(int frame) {
        int count = used[frame];
        pending[frame] = false;
        if (count <= 0) {
            return;
        }
        INT.clear();
        GL15.glGetQueryObject(ids[frame][count - 1], GL15.GL_QUERY_RESULT_AVAILABLE, INT);
        if (INT.get(0) == 0) {
            WINDOW.dropped();
            RECENT.dropped();
            PROBE.dropped();
            return;
        }
        long[] stamps = new long[count];
        for (int i = 0; i < count; i++) {
            LONG.clear();
            if (core) {
                GL33.glGetQueryObject(ids[frame][i], GL15.GL_QUERY_RESULT, LONG);
            } else {
                ARBTimerQuery.glGetQueryObject(ids[frame][i], GL15.GL_QUERY_RESULT, LONG);
            }
            stamps[i] = LONG.get(0);
        }
        Map<String, Long> nanos = new LinkedHashMap<String, Long>();
        for (int i = 0; i < count; i++) {
            int end = ends[frame][i];
            String name = names[frame][i];
            if (end <= i || end >= count || name == null) {
                continue;
            }
            long took = stamps[end] - stamps[i];
            if (took < 0L || took > 10_000_000_000L) {
                continue;
            }
            Long before = nanos.get(name);
            nanos.put(name, before == null ? took : before + took);
        }
        WINDOW.frame(nanos);
        RECENT.frame(nanos);
        PROBE.frame(nanos);
        long now = System.currentTimeMillis();
        if (now - recentSince >= RECENT_MS) {
            lastRecent = RECENT.copy();
            RECENT.reset();
            recentSince = now;
        }
    }

    /** Clears errors left by earlier GL calls (not ours) so the check in start sees only its own. */
    private static void drainErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // keep reading: each call returns and clears one error flag
        }
    }

    private static void disable(String reason) {
        state = -1;
        why = reason;
    }

    private static void off(Throwable t) {
        state = -1;
        inFrame = false;
        why = "stopped after an error: " + t.getClass().getSimpleName();
        System.err.println("[Atlas] GPU timer turned off: " + t);
    }
}
