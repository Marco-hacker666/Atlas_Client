package atlas.ui.impl.clickgui.atlas;

import atlas.Atlas;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.KeyProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KeyConflicts {
    private KeyConflicts() {
    }

    public static List<String> find(int key, String self, Map<String, Integer> bindings) {
        if (key == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            Integer bound = entry.getValue();
            if (bound != null && bound.intValue() == key && !entry.getKey().equals(self)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    public static String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(i == names.size() - 1 ? " and " : ", ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    public static Map<String, Integer> bindings() {
        return atlas.management.Conflicts.bindings();
    }

    public static String nameOf(Module owner, Property<?> property) {
        return nameOf(owner.getName(), property.getLabel());
    }

    static String nameOf(String module, String setting) {
        return module + " (" + setting + ")";
    }
}
