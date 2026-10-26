package atlas.ui.impl.clickgui.atlas;

import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;

import java.util.List;

/**
 * What the menu says about a setting beyond its value (2026-10-23,
 * docs/GUI-AUDIT-2026-10-23.md U5): its default, its range, whether it was
 * changed, and putting it back. Nothing here draws, so it is tested as it is.
 *
 * Only settings whose default is a plain value -- a switch, a number, a mode,
 * a colour, a key, text -- can be compared and reset. A HUD position
 * (DragProperty) holds an object that moves as it is dragged, so its "default"
 * is not a fixed value to go back to; those are left alone.
 *
 * A reset sets the base, the value that is saved, through setValue as any
 * change in the menu does. A tuner's override (AutoTune) stays on top of it.
 */
public final class SettingFacts {

    private SettingFacts() {
    }

    public static boolean resettable(Property<?> property) {
        Object fallback = property.getDefault();
        return fallback instanceof Boolean || fallback instanceof Number || fallback instanceof String;
    }

    /** Whether the saved value differs from the default. A colour compares its RGB only, as it is saved. */
    public static boolean changed(Property<?> property) {
        if (!resettable(property)) {
            return false;
        }
        Object base = property.getBaseValue();
        Object fallback = property.getDefault();
        if (property instanceof ColorProperty && base instanceof Integer) {
            return (((Integer) base).intValue() & 0xFFFFFF) != (((Integer) fallback).intValue() & 0xFFFFFF);
        }
        return !fallback.equals(base);
    }

    /** Puts a changed setting back to its default. False when it was not changed or refused it. */
    public static boolean reset(Property<?> property) {
        return changed(property) && property.setValue(property.getDefault());
    }

    static int countChanged(List<Property<?>> properties) {
        int count = 0;
        for (Property<?> property : properties) {
            if (changed(property)) {
                count++;
            }
        }
        return count;
    }

    /** Resets every changed setting in the list; returns how many were. */
    static int resetAll(List<Property<?>> properties) {
        int count = 0;
        for (Property<?> property : properties) {
            if (reset(property)) {
                count++;
            }
        }
        return count;
    }

    /** A value of this setting as the menu writes it: "On", "3.5", "Full", "40%", "#DD4747". */
    public static String format(Property<?> property, Object value) {
        if (value == null) {
            return "none";
        }
        if (property instanceof BooleanProperty) {
            return Boolean.TRUE.equals(value) ? "On" : "Off";
        }
        if (property instanceof ModeProperty) {
            String name = ((ModeProperty) property).modeName(((Integer) value).intValue());
            return name.isEmpty() ? "?" : name;
        }
        if (property instanceof PercentProperty) {
            return value + "%";
        }
        if (property instanceof ColorProperty) {
            return String.format("#%06X", ((Integer) value).intValue() & 0xFFFFFF);
        }
        if (property instanceof KeyProperty) {
            int key = ((Integer) value).intValue();
            return key == 0 ? "None" : atlas.util.KeyBindUtil.getKeyName(key);
        }
        if (value instanceof String) {
            return ((String) value).isEmpty() ? "empty" : (String) value;
        }
        return String.valueOf(value);
    }

    public static String defaultText(Property<?> property) {
        return format(property, property.getDefault());
    }

    /** "0 to 6.0", "0% to 100%", "4 options"; empty when a setting has no range to tell. */
    public static String range(Property<?> property) {
        if (property instanceof IntProperty) {
            IntProperty p = (IntProperty) property;
            return p.getMinimum() + " to " + p.getMaximum();
        }
        if (property instanceof FloatProperty) {
            FloatProperty p = (FloatProperty) property;
            return p.getMinimum() + " to " + p.getMaximum();
        }
        if (property instanceof PercentProperty) {
            PercentProperty p = (PercentProperty) property;
            return p.getMinimum() + "% to " + p.getMaximum() + "%";
        }
        if (property instanceof ModeProperty) {
            int options = ((ModeProperty) property).visibleModes().length;
            return options + (options == 1 ? " option" : " options");
        }
        return "";
    }
}
