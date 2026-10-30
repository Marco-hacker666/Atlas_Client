package atlas.config;

import com.google.gson.*;
import atlas.Atlas;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.util.ChatUtil;
import atlas.property.Property;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.util.ArrayList;

public class Config {
    public static Minecraft mc = Minecraft.getMinecraft();
    public static Gson gson = new GsonBuilder().setPrettyPrinting().create();
    public String name;
    public File file;

    public static String lastConfig;

    public Config(String name, boolean newConfig) {
        this.name = name;
        if (name == null || name.equals("!") || name.equals("default")) {
            this.name = "default";
        }
        if (!validName(this.name)) {
            /* 2026-10-30 (audit): ".config save ../../launcher_profiles" wrote outside the folder, and lastConfig
               then sent every exit save there. Such a name is refused; load and save say so. */
            this.file = null;
            return;
        }
        lastConfig = name == null ? "default" : name;
        this.file = new File("./config/Atlas/", String.format("%s.json", this.name));
        try {
            file.getParentFile().mkdirs();
            if (newConfig) {
                ((IAccessorMinecraft) mc).getLogger().info(String.format("Created: %s", this.file.getName()));
            }
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().error(e.getMessage());
        }
    }

    /**
     * A profile name that stays one plain file in ./config/Atlas/: no folder separators or other characters
     * Windows refuses in a name, no "..", no leading dot, no trailing dot or space, no device name (CON, NUL...).
     * Anything else stays allowed, "default (1)" among them.
     */
    public static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 64 && !name.contains("..")
                && !name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") && !name.startsWith(".") && !name.endsWith(".")
                && !name.endsWith(" ") && !name.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
    }

    private boolean refuse() {
        if (this.file != null) {
            return false;
        }
        ChatUtil.sendFormatted(String.format("%sInvalid config name (&c&o%s&r): letters, digits, space, - _ . only&r",
                Atlas.clientName, this.name));
        return true;
    }

    private static File backupOf(File file) {
        return new File(file.getParentFile(), file.getName() + ".bak");
    }

    /** The file's JSON object, or null when it is missing, empty or broken. */
    private static JsonObject readObject(File file) {
        if (!file.isFile() || file.length() == 0L) {
            return null;
        }
        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file),
                java.nio.charset.StandardCharsets.UTF_8))) {
            JsonElement parsed = new JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True while a config is being read, so property callbacks can tell a load from a user's change. */
    public static volatile boolean loading;

    public void load() {
        if (this.refuse()) {
            return;
        }
        loading = true;
        try {
            loadNow();
        } finally {
            loading = false;
        }
    }

    private void loadNow() {
        try {

            if (!file.exists()) {
                ChatUtil.sendFormatted(String.format("%sConfig file not found (&c&o%s&r). Creating default config...&r", Atlas.clientName, file.getName()));
                save();
                return;
            }

            /* 2026-10-30 (audit): read as UTF-8 and closed (the reader used to stay open, the file locked until GC);
               a file left empty or half written by a crash during a save falls back to the copy the save before it
               kept. */
            JsonObject jsonObject = readObject(file);
            if (jsonObject == null) {
                JsonObject backup = readObject(backupOf(file));
                if (backup == null) {
                    ChatUtil.sendFormatted(String.format("%sInvalid config format (&c&o%s&r)&r", Atlas.clientName, file.getName()));
                    return;
                }
                ChatUtil.sendFormatted(String.format("%s&e%s was damaged; loaded the backup (&o%s&e).&r",
                        Atlas.clientName, file.getName(), backupOf(file).getName()));
                jsonObject = backup;
            }
            for (Module module : Atlas.moduleManager.modules.values()) {
                JsonElement moduleObj = jsonObject.get(module.getName());
                if (moduleObj == null) {
                    /* Saved before a rename: under the old name, which is
                       the class name (KillAura, BlockHit). */
                    moduleObj = jsonObject.get(module.getClass().getSimpleName());
                }
                if (moduleObj != null && moduleObj.isJsonObject()) {
                    JsonObject object = moduleObj.getAsJsonObject();

                    ArrayList<Property<?>> list = Atlas.propertyManager.properties.get(module.getClass());
                    if (list != null) {
                        for (Property<?> property : list) {
                            if (object.has(property.getName())) {
                                try {
                                    property.read(object);
                                } catch (Exception e) {
                                    ((IAccessorMinecraft) mc).getLogger().warn(String.format("Failed to load property %s for module %s", property.getName(), module.getName()));
                                }
                            }
                        }
                    }

                    if (object.has("toggled")) {
                        JsonElement toggled = object.get("toggled");
                        if (toggled != null && toggled.isJsonPrimitive()) {
                            /* One module's onEnabled throwing (commonly: it
                               touched mc.thePlayer during the startup load)
                               used to abort this whole loop, so every module
                               after it silently kept its defaults. */
                            try {
                                module.setEnabled(toggled.getAsBoolean());
                            } catch (Exception e) {
                                ((IAccessorMinecraft) mc).getLogger().warn(String.format("Failed to toggle module %s: %s", module.getName(), e));
                            }
                        }
                    }

                    if (object.has("key")) {
                        JsonElement key = object.get("key");
                        if (key != null && key.isJsonPrimitive()) {
                            module.setKey(key.getAsInt());
                        }
                    }

                    if (object.has("hidden")) {
                        JsonElement hidden = object.get("hidden");
                        if (hidden != null && hidden.isJsonPrimitive()) {
                            module.setHidden(hidden.getAsBoolean());
                        }
                    }
                }
                /* Modules merged into this one, saved before the merge under their own names. */
                atlas.module.ModuleBundle.readLegacy(module, jsonObject,
                        moduleObj != null && moduleObj.isJsonObject() ? moduleObj.getAsJsonObject() : null);
            }
            ChatUtil.sendFormatted(String.format("%sConfig has been loaded (&a&o%s&r)&r", Atlas.clientName, file.getName()));
        } catch (JsonSyntaxException e) {
            ChatUtil.sendFormatted(String.format("%sConfig has invalid JSON syntax (&c&o%s&r)&r", Atlas.clientName, file.getName()));
            ((IAccessorMinecraft) mc).getLogger().error("JSON Syntax Error: " + e.getMessage());
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().error("Error loading config: " + e.getMessage());
            ChatUtil.sendFormatted(String.format("%sConfig couldn't be loaded (&c&o%s&r)&r", Atlas.clientName, file.getName()));
        }
    }

    public void save() {
        if (this.refuse()) {
            return;
        }
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            JsonObject object = snapshot();

            /* 2026-10-30 (audit): FileWriter emptied the file and then wrote it, so a crash or a kill in between
               left an empty profile, every setting at its default on the next start, and the exit save then wrote
               the defaults over it. Now it is written whole to a temporary file, the previous file is kept as .bak,
               and the new one is moved into place in one step. UTF-8 whatever the system's code page. */
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(gson.toJson(object));
                writer.write(System.lineSeparator());
            }
            if (readObject(file) != null) {
                java.nio.file.Files.copy(file.toPath(), backupOf(file).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ChatUtil.sendFormatted(String.format("%sConfig has been saved (&a&o%s&r)&r", Atlas.clientName, file.getName()));
        } catch (IOException e) {
            ((IAccessorMinecraft) mc).getLogger().error("Error saving config: " + e.getMessage());
            ChatUtil.sendFormatted(String.format("%sConfig couldn't be saved (&c&o%s&r)&r", Atlas.clientName, file.getName()));
        }
    }

    /**
     * Whether saving now would write something different from what the
     * profile's file holds (2026-10-23, the menu's "unsaved changes"). Compared
     * value by value, as text, over the entries both have: a setting the file
     * does not mention was never saved and loads as its default, and numbers
     * read back from a file print as they were written. False when the file
     * cannot be read: nothing to warn about then.
     */
    public static boolean differsFromFile(String name) {
        String clean = name == null || "!".equals(name) ? "default" : name;
        if (!validName(clean)) {
            return false;
        }
        JsonObject saved = readObject(new File("./config/Atlas/", String.format("%s.json", clean)));
        if (saved == null) {
            return false;
        }
        return differs(snapshot(), saved);
    }

    /** {@code now} differs from {@code saved} in a value both have (one level of modules, one of settings). */
    public static boolean differs(JsonObject now, JsonObject saved) {
        for (java.util.Map.Entry<String, JsonElement> module : now.entrySet()) {
            JsonElement before = saved.get(module.getKey());
            if (before == null || !before.isJsonObject() || !module.getValue().isJsonObject()) {
                continue;
            }
            JsonObject old = before.getAsJsonObject();
            for (java.util.Map.Entry<String, JsonElement> value : module.getValue().getAsJsonObject().entrySet()) {
                JsonElement was = old.get(value.getKey());
                if (was != null && !was.toString().equals(value.getValue().toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Everything save() writes, without writing it. */
    public static JsonObject snapshot() {
        JsonObject object = new JsonObject();
        for (Module module : Atlas.moduleManager.modules.values()) {
            JsonObject moduleObject = new JsonObject();
            moduleObject.addProperty("toggled", module.isEnabled());
            moduleObject.addProperty("key", module.getKey());
            moduleObject.addProperty("hidden", module.isHidden());

            ArrayList<Property<?>> list = Atlas.propertyManager.properties.get(module.getClass());
            if (list != null) {
                for (Property<?> property : list) {
                    try {
                        property.write(moduleObject);
                    } catch (Exception e) {
                        ((IAccessorMinecraft) mc).getLogger().warn(String.format("Failed to save property %s for module %s", property.getName(), module.getName()));
                    }
                }
            }
            object.add(module.getName(), moduleObject);
        }
        return object;
    }
}
