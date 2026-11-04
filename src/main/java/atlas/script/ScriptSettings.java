package atlas.script;

import atlas.Atlas;
import atlas.module.Module;
import atlas.property.Property;

import java.util.List;
import java.util.Locale;

/**
 * A script's settings are real menu settings (2026-11-02): Raven's modules.register* and AtlasScript's bool /
 * number / mode ... make Atlas properties in the script's module, shown in its settings card, saved with its
 * state and in profiles. A setting is found again by its name as shown or as stored, in any case and spacing.
 */
public final class ScriptSettings {
    private ScriptSettings() {
    }

    /** The script a Raven script's "modules" object (by script name) belongs to. */
    public static ScriptEntry entry(String scriptName) {
        return ScriptManager.get().byName(scriptName);
    }

    /** The script whose constructor is running on this thread, or null. */
    public static ScriptEntry constructing() {
        return ScriptManager.CONSTRUCTING.get();
    }

    /** The stored name of a setting shown as {@code name}: lower case, dashes for spaces. */
    public static String key(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                out.append(c);
            } else if (c == ' ' && out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        return out.length() == 0 ? "setting" : out.toString();
    }

    /**
     * Adds the setting to the script, under the current heading. A script registering a setting it already has
     * (Raven scripts sometimes do it in onEnable) gets the one it has.
     */
    @SuppressWarnings("unchecked")
    public static <P extends Property<?>> P add(ScriptEntry entry, P property, String label) {
        if (entry == null) {
            return property;
        }
        for (Property<?> existing : entry.properties) {
            if (existing.getName().equals(property.getName()) && existing.getClass() == property.getClass()) {
                return (P) existing;
            }
        }
        if (label != null) {
            property.setLabel(label);
        }
        if (entry.group != null) {
            property.setGroup(entry.group);
        }
        if (entry.host != null) {
            property.setOwner(entry.host);
        }
        entry.properties.add(property);
        return property;
    }

    public static void group(ScriptEntry entry, String group) {
        if (entry != null) {
            entry.group = group == null || group.trim().isEmpty() ? null : group;
        }
    }

    public static void step(ScriptEntry entry, String key, double step) {
        if (entry != null && step > 0.0) {
            entry.steps.put(key, step);
        }
    }

    /** A slider's value rounded to its step. */
    public static double stepped(ScriptEntry entry, Property<?> property, double value) {
        Double step = entry == null ? null : entry.steps.get(property.getName());
        if (step == null || step <= 0.0) {
            return value;
        }
        double rounded = Math.round(value / step) * step;
        /* Away from binary noise: 0.1 steps read 0.3, not 0.30000000000000004. */
        return Math.round(rounded * 1.0E9) / 1.0E9;
    }

    public static void alpha(ScriptEntry entry, String colourKey, Property<?> alpha) {
        if (entry != null) {
            entry.alphas.put(colourKey, alpha);
        }
    }

    public static Property<?> alphaOf(ScriptEntry entry, String colourKey) {
        return entry == null ? null : entry.alphas.get(colourKey);
    }

    /** A setting of a script by its name as shown or stored. */
    public static Property<?> find(ScriptEntry entry, String name) {
        return entry == null || name == null ? null : find(entry.properties, name);
    }

    /** A setting of any module -- a script's or a built-in one. */
    public static Property<?> find(Module module, String name) {
        if (module == null || name == null) {
            return null;
        }
        ScriptEntry entry = ScriptManager.of(module);
        if (entry != null) {
            return find(entry, name);
        }
        List<Property<?>> list = Atlas.propertyManager == null ? null : Atlas.propertyManager.properties.get(module.getClass());
        return list == null ? null : find(list, name);
    }

    private static Property<?> find(List<Property<?>> properties, String name) {
        String wanted = squash(name);
        for (Property<?> property : properties) {
            if (squash(property.getName()).equals(wanted) || squash(property.getLabel()).equals(wanted)) {
                return property;
            }
        }
        return null;
    }

    private static String squash(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
