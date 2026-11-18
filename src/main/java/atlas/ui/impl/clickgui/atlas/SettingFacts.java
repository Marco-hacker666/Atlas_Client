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

public final class SettingFacts {
    private SettingFacts() {
    }

    public static boolean resettable(Property<?> property) {
        Object fallback = property.getDefault();
        return fallback instanceof Boolean || fallback instanceof Number || fallback instanceof String;
    }

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

    static int resetAll(List<Property<?>> properties) {
        int count = 0;
        for (Property<?> property : properties) {
            if (reset(property)) {
                count++;
            }
        }
        return count;
    }

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
