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

    /**
     * The saved look of this name as it is saved: the exact name first, then one that differs only in case (a
     * case-sensitive disk can hold both; review, PR #42). Null when there is none.
     */
    public static String find(String name) {
        return match(names(), ThemeFiles.cleanName(name));
    }

    private static String match(List<String> names, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (String saved : names) {
            if (saved.equals(name)) {
                return saved;
            }
        }
        for (String saved : names) {
            if (saved.equalsIgnoreCase(name)) {
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
        int size = names.size();
        if (size == 0) {
            return null;
        }
        int dir = direction >= 0 ? 1 : -1;
        int at = names.indexOf(match(names, current()));
        int first = at < 0 ? (dir > 0 ? 0 : size - 1) : ((at + dir) % size + size) % size;
        /* One that cannot be read is passed over, each tried once (review, PR #42): a broken file must not stop
           the others being reached. */
        for (int tried = 0; tried < size; tried++) {
            String loaded = load(names.get(((first + dir * tried) % size + size) % size));
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

    /**
     * Saves the look as it is now under this name (cleaned as the Theme window cleans it); the name, or null. A
     * look saved before under the same name in another case is written over, keeping its name.
     */
    public static String save(String name) {
        String clean = ThemeFiles.cleanName(name);
        String existing = match(names(), clean);
        if (existing != null) {
            clean = existing;
        }
        if (clean.isEmpty() || !ThemeFiles.save(clean, AtlasClickGui.getInstance().theme())) {
            return null;
        }
        remember(clean);
        return clean;
    }
}
