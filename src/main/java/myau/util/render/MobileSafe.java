package myau.util.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/**
 * Mobile-safe rendering (2026-10-15): for phones running the game through an
 * OpenGL-to-GLES translation layer (gl4es, Krypton Wrapper; see GlesDetect).
 *
 * While active:
 * - FramebufferCompat.available() is false, so every screen-copy effect (blur,
 *   bloom, the Atlas glass, TargetHUD blur) takes its existing plain fallback;
 * - rounded rectangles are drawn as one triangle fan instead of an SDF shader
 *   (RoundRectGeometry), the Atlas shapes use their old geometry (Liquid);
 * - shader-only extras with no plain form are skipped: the ShadowShader soft
 *   shadow, the HUD glow/blur mask passes, TargetHUD's stencil masks and its
 *   depth-stencil swap of the game's framebuffer;
 * - ESP OUTLINE (a shader over an extra framebuffer) draws as 3D boxes.
 * Off, nothing changes: every check below returns false first.
 *
 * The setting ("mobile-safe": Auto / On / Off, Client Settings > Performance)
 * lives in config/Atlas/atlas-theme.json. Read from the file on first use
 * (the menu may never be opened), pushed by AtlasTheme after that -- the same
 * way as LogUploader's switch.
 */
public final class MobileSafe {
    public static final String SETTING = "mobile-safe";
    public static final String[] MODES = {"Auto", "On", "Off"};
    private static final File THEME_FILE = new File("./config/Atlas/atlas-theme.json");

    private static volatile int setting = -1;
    private static volatile boolean detected;
    private static volatile boolean detectedKnown;
    private static volatile String reason = "not checked yet";
    private static volatile String glInfo = "";

    private MobileSafe() {
    }

    /** True when the plain, framebuffer-free, shader-free drawing should be used. */
    public static boolean active() {
        int mode = setting;
        if (mode < 0) {
            mode = readSetting();
            setting = mode;
        }
        if (mode == GlesDetect.ON) {
            return true;
        }
        if (mode == GlesDetect.OFF) {
            return false;
        }
        if (!detectedKnown) {
            detect();
        }
        return detected;
    }

    public static void setSetting(int mode) {
        setting = Math.max(0, Math.min(MODES.length - 1, mode));
    }

    /**
     * Looks once at the GL strings and the platform. Must run on the thread
     * holding the GL context (the client thread); called at start-up from
     * Myau.init and otherwise from the first render that asks.
     */
    public static synchronized void detect() {
        if (detectedKnown) {
            return;
        }
        String vendor = gl(GL11.GL_VENDOR);
        String renderer = gl(GL11.GL_RENDERER);
        String version = gl(GL11.GL_VERSION);
        String glsl = gl(GL20.GL_SHADING_LANGUAGE_VERSION);
        String layer = GlesDetect.translationLayer(vendor, renderer, version, glsl);
        String android = GlesDetect.android(System::getenv, System::getProperty, path -> new File(path).exists());
        glInfo = renderer + " | " + version;
        if (layer != null) {
            detected = true;
            reason = layer + (android != null ? ", Android (" + android + ")" : "");
        } else if (android != null) {
            detected = true;
            reason = "Android (" + android + ")";
        } else {
            detected = false;
            reason = "desktop GL";
        }
        /* Without a context every string is null: look again next time. */
        detectedKnown = renderer != null || android != null;
        if (detectedKnown) {
            System.out.println("[Atlas] mobile-safe: " + (detected ? "detected " : "not detected, ") + reason
                    + " [" + glInfo + "]");
        }
    }

    /** For the bug report: "on (auto: gl4es)", "off (setting)", ... */
    public static String describe() {
        boolean on = active();
        int mode = setting;
        String why = mode == GlesDetect.AUTO ? "auto: " + reason : "setting " + MODES[Math.max(0, mode)];
        return (on ? "on" : "off") + " (" + why + ")";
    }

    private static String gl(int name) {
        try {
            return GL11.glGetString(name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int readSetting() {
        if (!THEME_FILE.isFile()) {
            return GlesDetect.AUTO;
        }
        try (Reader reader = new FileReader(THEME_FILE)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has(SETTING)) {
                    JsonElement value = object.get(SETTING);
                    /* Saved as the mode's name or its index, whichever the theme file uses. */
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        return Math.max(0, Math.min(MODES.length - 1, value.getAsInt()));
                    }
                    String name = value.getAsString();
                    for (int i = 0; i < MODES.length; i++) {
                        if (MODES[i].equalsIgnoreCase(name)) {
                            return i;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable: the default.
        }
        return GlesDetect.AUTO;
    }
}
