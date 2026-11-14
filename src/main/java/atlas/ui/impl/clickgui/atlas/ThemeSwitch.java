package atlas.ui.impl.clickgui.atlas;

import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Saved looks put on without opening the Theme window (2026-11-14, owner: "switch the whole theme in one go"): by
 * name, or the next or previous one A to Z, from the .theme command and the Theme module's keys. The looks are the
 * Theme window's (ThemeFiles); the one put on or saved last is remembered across launches, so the next one is next
 * after it.
 */
public final class ThemeSwitch {
    private static final File CURRENT = new File("./config/Atlas/theme-current.txt");
    private static String current;
    private static boolean currentRead;

    private ThemeSwitch() {
    }

    /** The saved looks, A to Z. */
    public static List<String> names() {
        return ThemeFiles.list();
    }

    /** The look put on or saved last, or null. */
    public static synchronized String current() {
        if (!currentRead) {
            currentRead = true;
            try {
                if (CURRENT.isFile()) {
                    String name = new String(Files.readAllBytes(CURRENT.toPath()), StandardCharsets.UTF_8).trim();
                    current = name.isEmpty() ? null : name;
                }
            } catch (Exception ignored) {
                current = null;
            }
        }
        return current;
    }

    /** Called when a look is put on or saved, here or in the Theme window. */
    static synchronized void remember(String name) {
        current = name;
        currentRead = true;
        try {
            File dir = CURRENT.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.write(CURRENT.toPath(), (name == null ? "" : name).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Only which look comes next; the look itself is saved.
        }
    }

    /** The saved look of this name, any case, as it is saved; null when there is none. */
    public static String find(String name) {
        String clean = ThemeFiles.cleanName(name);
        for (String saved : names()) {
            if (saved.equalsIgnoreCase(clean)) {
                return saved;
            }
        }
        return null;
    }

    /** Puts on the saved look of this name; its name as saved, or null when there is none or it cannot be read. */
    public static String load(String name) {
        String found = find(name);
        JsonObject look = found == null ? null : ThemeFiles.read(found);
        if (look == null) {
            return null;
        }
        AtlasTheme theme = AtlasClickGui.getInstance().theme();
        ThemeFiles.apply(look, theme);
        /* The menu's part is saved with the menu (atlas-theme.json); the modules' with the config, as always. */
        theme.save();
        remember(found);
        return found;
    }

    /**
     * Puts on the saved look after (1) or before (-1) the current one, A to Z and round again; the first or the last
     * when none is current. Its name, or null when none is saved.
     */
    public static String step(int direction) {
        List<String> names = names();
        if (names.isEmpty()) {
            return null;
        }
        String now = current();
        int at = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(now == null ? "" : now)) {
                at = i;
                break;
            }
        }
        int next = at < 0 ? (direction >= 0 ? 0 : names.size() - 1)
                : ((at + (direction >= 0 ? 1 : -1)) % names.size() + names.size()) % names.size();
        return load(names.get(next));
    }

    /** Saves the look as it is now under this name (cleaned as the Theme window cleans it); the name, or null. */
    public static String save(String name) {
        String clean = ThemeFiles.cleanName(name);
        if (clean.isEmpty() || !ThemeFiles.save(clean, AtlasClickGui.getInstance().theme())) {
            return null;
        }
        remember(clean);
        return clean;
    }
}
