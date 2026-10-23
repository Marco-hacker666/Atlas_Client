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
        lastConfig = name;
        if (name.equals("!") || name.equals("default")) {
            this.name = "default";
        }
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

    /** True while a config is being read, so property callbacks can tell a load from a user's change. */
    public static volatile boolean loading;

    public void load() {
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

            JsonElement parsed = new JsonParser().parse(new BufferedReader(new FileReader(file)));
            if (parsed == null || !parsed.isJsonObject()) {
                ChatUtil.sendFormatted(String.format("%sInvalid config format (&c&o%s&r)&r", Atlas.clientName, file.getName()));
                return;
            }

            JsonObject jsonObject = parsed.getAsJsonObject();
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
            }
            ChatUtil.sendFormatted(String.format("%sConfig has been loaded (&a&o%s&r)&r", Atlas.clientName, file.getName()));
        } catch (FileNotFoundException e) {
            ChatUtil.sendFormatted(String.format("%sConfig file not found (&c&o%s&r)&r", Atlas.clientName, file.getName()));
        } catch (JsonSyntaxException e) {
            ChatUtil.sendFormatted(String.format("%sConfig has invalid JSON syntax (&c&o%s&r)&r", Atlas.clientName, file.getName()));
            ((IAccessorMinecraft) mc).getLogger().error("JSON Syntax Error: " + e.getMessage());
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().error("Error loading config: " + e.getMessage());
            ChatUtil.sendFormatted(String.format("%sConfig couldn't be loaded (&c&o%s&r)&r", Atlas.clientName, file.getName()));
        }
    }

    public void save() {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            JsonObject object = snapshot();

            PrintWriter printWriter = new PrintWriter(new FileWriter(file));
            printWriter.println(gson.toJson(object));
            printWriter.close();
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
        File target = new File("./config/Atlas/", String.format("%s.json", "!".equals(name) ? "default" : name));
        if (!target.exists()) {
            return false;
        }
        JsonObject saved;
        try (Reader reader = new BufferedReader(new FileReader(target))) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return false;
            }
            saved = parsed.getAsJsonObject();
        } catch (Exception e) {
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
