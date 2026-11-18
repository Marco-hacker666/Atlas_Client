package atlas.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;

public final class UiMode {
    private static final File THEME_FILE = new File("./config/Atlas/atlas-theme.json");
    private static final String KEY = "appearance-mode";

    private static final int NEUTRAL_SPREAD = 40;

    private static boolean light;
    private static boolean loaded;

    private UiMode() {
    }

    public static boolean isLight() {
        if (!loaded) {
            load();
        }
        return light;
    }

    public static void setLight(boolean value) {
        light = value;
        loaded = true;
    }

    public static int adapt(int argb) {
        if (!isLight()) {
            return argb;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min > NEUTRAL_SPREAD) {
            return argb;
        }
        return (argb & 0xFF000000) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
    }

    public static Color adapt(Color colour) {
        if (colour == null || !isLight()) {
            return colour;
        }
        int argb = adapt(colour.getRGB());
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, colour.getAlpha());
    }

    private static void load() {
        loaded = true;
        if (!THEME_FILE.exists()) {
            return;
        }
        Reader reader = null;
        try {
            reader = new FileReader(THEME_FILE);
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has(KEY)) {
                    light = "Light".equalsIgnoreCase(object.get(KEY).getAsString());
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
