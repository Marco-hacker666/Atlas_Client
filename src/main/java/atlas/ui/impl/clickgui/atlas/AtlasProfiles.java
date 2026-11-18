package atlas.ui.impl.clickgui.atlas;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import atlas.config.Config;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AtlasProfiles {
    static final File DIR = new File("./config/Atlas/");

    static final class Info {
        final String name;
        final long modified;
        final List<String> enabled;

        Info(String name, long modified, List<String> enabled) {
            this.name = name;
            this.modified = modified;
            this.enabled = enabled;
        }
    }

    private static final Map<String, Info> CACHE = new HashMap<String, Info>();

    private AtlasProfiles() {
    }

    static List<Info> list() {
        List<Info> out = new ArrayList<Info>();
        File[] files = DIR.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            String fileName = file.getName();
            if (!file.isFile() || !fileName.toLowerCase().endsWith(".json")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - 5);
            Info cached = CACHE.get(name);
            if (cached == null || cached.modified != file.lastModified()) {
                cached = new Info(name, file.lastModified(), enabledIn(file));
                CACHE.put(name, cached);
            }
            out.add(cached);
        }
        Collections.sort(out, new Comparator<Info>() {
            @Override
            public int compare(Info a, Info b) {
                return Long.compare(b.modified, a.modified);
            }
        });
        return out;
    }

    private static List<String> enabledIn(File file) {
        List<String> out = new ArrayList<String>();
        Reader reader = null;
        try {
            reader = new FileReader(file);
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject module = entry.getValue().getAsJsonObject();
                    JsonElement toggled = module.get("toggled");
                    if (toggled != null && toggled.isJsonPrimitive() && toggled.getAsBoolean()) {
                        out.add(entry.getKey());
                    }
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
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static String current() {
        return Config.lastConfig == null || Config.lastConfig.equals("!") ? "default" : Config.lastConfig;
    }

    static void save(String name) {
        new Config(name, true).save();
        CACHE.remove(name);
    }

    static void load(String name) {
        new Config(name, false).load();
    }

    static boolean delete(String name) {
        if ("default".equalsIgnoreCase(name)) {
            return false;
        }
        CACHE.remove(name);
        return new File(DIR, name + ".json").delete();
    }

    static String clean(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        while (out.startsWith(".")) {
            out = out.substring(1);
        }
        return out.length() > 32 ? out.substring(0, 32) : out;
    }

    static void openFolder() {
        try {
            java.awt.Desktop.getDesktop().open(DIR.getCanonicalFile());
        } catch (Throwable desktopFailed) {
            try {
                org.lwjgl.Sys.openURL(DIR.getCanonicalFile().toURI().toString());
            } catch (Throwable ignored) {
            }
        }
    }

    static String age(long modified) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - modified) / 1000L);
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }
}
