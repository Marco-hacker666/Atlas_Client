package atlas.ui.impl.clickgui.atlas;

import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class ThemeSwitch {
    private static final File CURRENT = new File("./config/Atlas/theme-current.txt");
    private static String current;
    private static boolean currentRead;

    private ThemeSwitch() {
    }

    public static List<String> names() {
        return ThemeFiles.list();
    }

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
        }
    }

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

    public static String load(String name) {
        String found = find(name);
        JsonObject look = found == null ? null : ThemeFiles.read(found);
        if (look == null) {
            return null;
        }
        AtlasTheme theme = AtlasClickGui.getInstance().theme();
        ThemeFiles.apply(look, theme);

        theme.save();
        remember(found);
        return found;
    }

    public static String step(int direction) {
        List<String> names = names();
        int size = names.size();
        if (size == 0) {
            return null;
        }
        int dir = direction >= 0 ? 1 : -1;
        int at = names.indexOf(match(names, current()));
        int first = at < 0 ? (dir > 0 ? 0 : size - 1) : ((at + dir) % size + size) % size;

        for (int tried = 0; tried < size; tried++) {
            String loaded = load(names.get(((first + dir * tried) % size + size) % size));
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

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
