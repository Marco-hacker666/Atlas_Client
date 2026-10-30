package atlas.config.online;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import atlas.Atlas;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.DragProperty;

import java.util.ArrayList;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept.
// Mirrors this client's config JSON shape (config/Atlas/*.json). Every property
// read is isolated in its own try/catch so one bad value cannot break the rest
// of the apply.
public class OnlineConfigApplier {

    public static final class Result {
        public int applied;
        public int failed;
        public final ArrayList<String> changed = new ArrayList<String>();
        public final ArrayList<String> failedNames = new ArrayList<String>();
        /** Left as they were on purpose (see SENSITIVE). */
        public final ArrayList<String> skipped = new ArrayList<String>();
    }

    /*
     * 2026-10-30 (audit): a config from someone else (or rewritten on the way) is not trusted with everything.
     * Never applied from one: key binds; any text setting (AutoAuth's password, the lines Spammer, AutoGG, KillSults
     * and the rest say in your name, commands to run); and switching on or off a module that runs code, holds a
     * secret or talks for you.
     */
    private static final java.util.Set<Class<?>> SENSITIVE = new java.util.HashSet<Class<?>>(java.util.Arrays.asList(
            atlas.module.modules.ScriptModule.class, atlas.module.modules.AutoAuth.class,
            atlas.module.modules.Spammer.class, atlas.module.modules.AutoMessage.class,
            atlas.module.modules.AutoGG.class, atlas.module.modules.KillSults.class,
            atlas.module.modules.KillMessage.class));

    public Result apply(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty config data");
        }
        JsonElement parsed = new JsonParser().parse(json);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Invalid config JSON: expected an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        Result result = new Result();
        for (Module module : Atlas.moduleManager.modules.values()) {
            JsonObject object = findModuleObject(root, module);
            if (object == null) {
                continue;
            }
            if (SENSITIVE.contains(module.getClass())) {
                if (object.has("toggled")) {
                    result.skipped.add(module.getName() + ".toggled");
                }
            } else {
                applyFlag(object, "toggled", module.getName(), result, new FlagWriter() {
                    @Override
                    public void write(boolean value) {
                        module.setEnabled(value);
                    }
                });
            }
            if (object.has("key")) {
                result.skipped.add(module.getName() + ".key");
            }
            applyFlag(object, "hidden", module.getName(), result, new FlagWriter() {
                @Override
                public void write(boolean value) {
                    module.setHidden(value);
                }
            });

            ArrayList<Property<?>> list = Atlas.propertyManager.properties.get(module.getClass());
            if (list == null) {
                continue;
            }
            for (Property<?> property : list) {
                if (!object.has(property.getName()) && !hasDragKeys(object, property)) {
                    continue;
                }
                String label = module.getName() + "." + property.getName();
                if (property instanceof atlas.property.properties.TextProperty) {
                    result.skipped.add(label);
                    continue;
                }
                try {
                    if (property.read(object)) {
                        result.applied++;
                        result.changed.add(label);
                    } else {
                        result.failed++;
                        result.failedNames.add(label);
                    }
                } catch (Exception e) {
                    result.failed++;
                    result.failedNames.add(label);
                }
            }
        }
        return result;
    }

    private boolean hasDragKeys(JsonObject object, Property<?> property) {
        return property instanceof DragProperty
                && (object.has(property.getName() + "_x") || object.has(property.getName() + "_y"));
    }

    private JsonObject findModuleObject(JsonObject root, Module module) {
        JsonElement element = root.get(module.getName());
        if (element == null || !element.isJsonObject()) {
            element = root.get(module.getClass().getSimpleName());
        }
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return null;
    }

    private void applyFlag(JsonObject object, String key, String moduleName, Result result, FlagWriter writer) {
        if (!object.has(key)) {
            return;
        }
        try {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                writer.write(element.getAsBoolean());
                result.applied++;
                result.changed.add(moduleName + "." + key);
            }
        } catch (Exception e) {
            result.failed++;
            result.failedNames.add(moduleName + "." + key);
        }
    }

    private void applyInt(JsonObject object, String key, String moduleName, Result result, IntWriter writer) {
        if (!object.has(key)) {
            return;
        }
        try {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                writer.write(element.getAsInt());
                result.applied++;
                result.changed.add(moduleName + "." + key);
            }
        } catch (Exception e) {
            result.failed++;
            result.failedNames.add(moduleName + "." + key);
        }
    }

    private interface FlagWriter {
        void write(boolean value);
    }

    private interface IntWriter {
        void write(int value);
    }
}
