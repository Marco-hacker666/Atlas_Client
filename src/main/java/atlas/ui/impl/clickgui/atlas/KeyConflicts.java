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

/**
 * What else answers to a key (2026-10-23, docs/GUI-AUDIT-2026-10-23.md U6).
 * Binding a key never looked, so a key could switch two modules at once, or
 * toggle one module while it was also another's hold key, without a word.
 *
 * Only a warning: binding still binds, as it always did.
 */
public final class KeyConflicts {

    private KeyConflicts() {
    }

    /**
     * The names bound to {@code key}, other than {@code self}, in the order of
     * {@code bindings}. No key (0) is bound to nothing.
     */
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

    /** "A", "A and B", "A, B and C". */
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

    /**
     * Every module key and key setting in the client, by the name the menu
     * shows. The old "ClickGui" opener is left out: it shares Right Shift with
     * ClickGUI on purpose and stands aside for it (GuiModule).
     */
    public static Map<String, Integer> bindings() {
        /* One list for the bind warning and the conflict warnings (M5). */
        return atlas.management.Conflicts.bindings();
    }

    /** How a key setting is named in a warning: "Clutch (Hold Key)". */
    public static String nameOf(Module owner, Property<?> property) {
        return nameOf(owner.getName(), property.getLabel());
    }

    static String nameOf(String module, String setting) {
        return module + " (" + setting + ")";
    }
}
