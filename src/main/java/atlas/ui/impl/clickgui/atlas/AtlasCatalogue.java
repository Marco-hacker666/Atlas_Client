package atlas.ui.impl.clickgui.atlas;

import atlas.Atlas;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atlas reads the central module-to-category table used by the other menus. */
final class AtlasCatalogue {

    private AtlasCatalogue() {
    }

    static Map<String, List<Module>> catalogue() {
        Map<String, List<Module>> out = new LinkedHashMap<String, List<Module>>();
        for (Category category : Category.values()) {
            List<Module> modules = ModuleCategories.modulesIn(category, Atlas.moduleManager.modules);
            if (!modules.isEmpty()) {
                out.put(category.displayName(), modules);
            }
        }

        /* Keep uncategorised modules reachable if one is added before its
           placement is recorded in ModuleCategories. */
        java.util.Set<Module> listed = new java.util.HashSet<Module>();
        for (List<Module> modules : out.values()) {
            listed.addAll(modules);
        }
        List<Module> other = new ArrayList<Module>();
        for (Module module : Atlas.moduleManager.modules.values()) {
            if (module != null && !listed.contains(module)) {
                other.add(module);
            }
        }
        if (!other.isEmpty()) {
            other.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            out.put("Other", other);
        }
        return out;
    }
}
