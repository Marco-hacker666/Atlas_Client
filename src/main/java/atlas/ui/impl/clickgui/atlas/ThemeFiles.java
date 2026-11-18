package atlas.ui.impl.clickgui.atlas;

import atlas.Atlas;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;
import atlas.module.modules.HUD;
import atlas.property.Property;
import atlas.property.properties.KeyProperty;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ThemeFiles {
    static final File DIR = new File("./config/Atlas/themes");
    static final int VERSION = 1;
    static final int MAX_NAME = 24;

    static final Set<String> HUD_LOOK = hudLook(new HashSet<String>(Arrays.asList(
            "color", "color-speed", "color-saturation", "color-brightness", "custom-color-1", "custom-color-2",
            "custom-color-3", "color-dist", "hud-theme", "background", "bg-alpha", "blur", "blur-radius", "glow",
            "glow-color", "glow-custom-color", "glow-alpha", "glow-radius", "bar", "sidebar-mode", "shadow",
            "Shaders", "suffixes", "separator-mode", "lower-case", "font-mode", "rounded", "corner-radius",
            "padding")));

    private static Set<String> hudLook(Set<String> names) {
        names.add("interface");
        names.addAll(Arrays.asList(HUD.LIST_SETTINGS));
        return Collections.unmodifiableSet(names);
    }

    private ThemeFiles() {
    }

    static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < MAX_NAME; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    static File fileOf(String name) {
        return new File(DIR, cleanName(name) + ".json");
    }

    static List<String> list() {
        List<String> names = new ArrayList<String>();
        File[] files = DIR.listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                names.add(name.substring(0, name.length() - 5));
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static boolean exists(String name) {
        return !cleanName(name).isEmpty() && fileOf(name).isFile();
    }

    static Map<Module, Set<String>> lookModules() {
        Map<Module, Set<String>> out = new LinkedHashMap<Module, Set<String>>();
        if (Atlas.moduleManager == null) {
            return out;
        }
        for (Class<? extends Module> type : ModuleCategories.typesIn(Category.THEME)) {
            Module module = Atlas.moduleManager.modules.get(type);
            if (module != null) {
                out.put(module, null);
            }
        }
        Module hud = Atlas.moduleManager.modules.get(HUD.class);
        if (hud != null) {
            out.put(hud, HUD_LOOK);
        }
        return out;
    }

    static List<Property<?>> propertiesOf(Module module) {
        if (Atlas.propertyManager == null) {
            return Collections.emptyList();
        }
        List<Property<?>> list = Atlas.propertyManager.properties.get(module.getClass());
        return list == null ? Collections.<Property<?>>emptyList() : list;
    }

    static JsonObject capture(AtlasTheme theme) {
        JsonObject look = new JsonObject();
        look.addProperty("version", VERSION);
        JsonObject menu = new JsonObject();
        if (theme != null) {
            for (Property<?> property : theme.all()) {
                if (theme.isLook(property)) {
                    write(property, menu);
                }
            }
        }
        look.add("menu", menu);
        JsonObject modules = new JsonObject();
        for (Map.Entry<Module, Set<String>> entry : lookModules().entrySet()) {
            Module module = entry.getKey();
            JsonObject object = new JsonObject();

            if (entry.getValue() == null) {
                object.addProperty("toggled", module.isEnabled());
            }
            for (Property<?> property : propertiesOf(module)) {
                if (property instanceof KeyProperty) {
                    continue;
                }
                if (entry.getValue() == null || entry.getValue().contains(property.getName())) {
                    write(property, object);
                }
            }
            modules.add(module.getName(), object);
        }
        look.add("modules", modules);
        return look;
    }

    static void apply(JsonObject look, AtlasTheme theme) {
        if (look == null) {
            return;
        }
        boolean wasLoading = atlas.config.Config.loading;
        atlas.config.Config.loading = true;
        try {
            JsonObject menu = object(look, "menu");
            if (menu != null && theme != null) {
                for (Property<?> property : theme.all()) {
                    if (theme.isLook(property) && menu.has(property.getName())) {
                        read(property, menu);
                    }
                }
            }
            JsonObject modules = object(look, "modules");
            if (modules == null) {
                return;
            }
            for (Map.Entry<Module, Set<String>> entry : lookModules().entrySet()) {
                Module module = entry.getKey();
                JsonObject object = object(modules, module.getName());
                if (object == null) {
                    continue;
                }
                for (Property<?> property : propertiesOf(module)) {
                    boolean held = !(property instanceof KeyProperty)
                            && (entry.getValue() == null || entry.getValue().contains(property.getName()));
                    if (held && object.has(property.getName())) {
                        read(property, object);
                    }
                }
                JsonElement toggled = object.get("toggled");
                if (entry.getValue() == null && toggled != null && toggled.isJsonPrimitive()) {
                    boolean on = toggled.getAsBoolean();
                    if (module.isEnabled() != on) {
                        try {
                            module.setEnabled(on);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } finally {
            atlas.config.Config.loading = wasLoading;
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static void write(Property<?> property, JsonObject into) {
        property.write(into);
    }

    static void read(Property<?> property, JsonObject from) {
        try {
            property.read(from);
        } catch (Exception ignored) {
        }
    }

    static boolean save(String name, AtlasTheme theme) {
        String clean = cleanName(name);
        if (clean.isEmpty()) {
            return false;
        }
        JsonObject look = capture(theme);
        look.addProperty("name", clean);
        if (!DIR.isDirectory() && !DIR.mkdirs()) {
            return false;
        }
        File target = fileOf(clean);
        File temp = new File(DIR, clean + ".json.tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(look));
            writer.write(System.lineSeparator());
        } catch (Exception e) {
            return false;
        }
        try {
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            temp.delete();
            return false;
        }
    }

    static JsonObject read(String name) {
        File file = fileOf(name);
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean delete(String name) {
        File file = fileOf(name);
        return file.isFile() && file.delete();
    }
}
