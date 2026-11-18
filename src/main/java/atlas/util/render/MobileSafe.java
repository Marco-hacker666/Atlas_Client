package atlas.util.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

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

    public static boolean active() {
        int mode = setting;
        if (mode < 0) {
            mode = readMode(SETTING, MODES);
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

        detectedKnown = renderer != null || android != null;
        if (detectedKnown) {
            System.out.println("[Atlas] mobile-safe: " + (detected ? "detected " : "not detected, ") + reason
                    + " [" + glInfo + "]");
        }
    }

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

    public static boolean deviceDetected() {
        if (!detectedKnown) {
            detect();
        }
        return detected;
    }

    public static int readMode(String key, String[] modes) {
        if (!THEME_FILE.isFile()) {
            return GlesDetect.AUTO;
        }
        try (Reader reader = new FileReader(THEME_FILE)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has(key)) {
                    JsonElement value = object.get(key);

                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        return Math.max(0, Math.min(modes.length - 1, value.getAsInt()));
                    }
                    String name = value.getAsString();
                    for (int i = 0; i < modes.length; i++) {
                        if (modes[i].equalsIgnoreCase(name)) {
                            return i;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return GlesDetect.AUTO;
    }
}
