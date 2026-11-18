package atlas.management;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

public final class Conflicts {
    public static final Set<String> HOLDERS = Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList(
            "FakeLag", "LagRange", "BackTrack", "KnockbackDelay", "ServerLag", "TimerRange", "PingSpoof", "Freeze",
            "Blink",

            "VelocityLag")));

    public static final class Conflict {
        public final String key;

        public final List<String> names;
        public final String message;

        Conflict(String key, List<String> names, String message) {
            this.key = key;
            this.names = names;
            this.message = message;
        }

        public boolean involves(String name) {
            return this.names.contains(name);
        }
    }

    private static final File FILE = new File("./config/Atlas/atlas-conflicts.txt");
    private static Set<String> ignored;

    private static final Set<String> WARNED = new LinkedHashSet<String>();

    private Conflicts() {
    }

    public static List<String> holdersOn(List<String[]> modulesOn) {
        List<String> out = new ArrayList<String>();
        for (String[] module : modulesOn) {
            if (HOLDERS.contains(module[1])) {
                out.add(module[0]);
            }
        }
        return out;
    }

    public static List<Conflict> find(List<String> holdersOn, Map<String, Integer> bindings, IntFunction<String> keyName) {
        List<Conflict> out = new ArrayList<Conflict>();
        if (holdersOn.size() >= 2) {
            List<String> names = new ArrayList<String>(holdersOn);
            List<String> sorted = new ArrayList<String>(names);
            Collections.sort(sorted);
            out.add(new Conflict("holders:" + String.join(",", sorted), names,
                    join(names) + (names.size() == 2 ? " both" : " all") + " hold packets back. Together they hold more often and longer,"
                            + " a common cause of damage mitigation."));
        }
        Map<Integer, List<String>> byKey = new LinkedHashMap<Integer, List<String>>();
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            Integer key = entry.getValue();
            if (key == null || key.intValue() == 0) {
                continue;
            }
            List<String> names = byKey.get(key);
            if (names == null) {
                names = new ArrayList<String>();
                byKey.put(key, names);
            }
            names.add(entry.getKey());
        }
        for (Map.Entry<Integer, List<String>> entry : byKey.entrySet()) {
            List<String> names = entry.getValue();
            if (names.size() < 2) {
                continue;
            }
            List<String> sorted = new ArrayList<String>(names);
            Collections.sort(sorted);
            out.add(new Conflict("key:" + entry.getKey() + ":" + String.join(",", sorted), names,
                    join(names) + (names.size() == 2 ? " are both" : " are all") + " bound to " + keyName.apply(entry.getKey().intValue())
                            + ": one press does " + (names.size() == 2 ? "both." : "all of them.")));
        }
        return out;
    }

    public static Map<String, Integer> bindings() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (atlas.Atlas.moduleManager == null) {
            return out;
        }
        for (atlas.module.Module module : atlas.Atlas.moduleManager.modules.values()) {
            if (module == null || module instanceof atlas.module.modules.GuiModule) {
                continue;
            }
            out.put(module.getName(), Integer.valueOf(module.getKey()));
        }
        if (atlas.Atlas.propertyManager == null) {
            return out;
        }
        for (Map.Entry<Class<?>, java.util.ArrayList<atlas.property.Property<?>>> entry
                : atlas.Atlas.propertyManager.properties.entrySet()) {
            atlas.module.Module owner = atlas.Atlas.moduleManager.modules.get(entry.getKey());
            if (owner == null || entry.getValue() == null) {
                continue;
            }
            for (atlas.property.Property<?> property : entry.getValue()) {
                if (property instanceof atlas.property.properties.KeyProperty) {
                    out.put(owner.getName() + " (" + property.getLabel() + ")",
                            ((atlas.property.properties.KeyProperty) property).getValue());
                }
            }
        }
        return out;
    }

    public static List<Conflict> current() {
        List<String[]> on = new ArrayList<String[]>();
        if (atlas.Atlas.moduleManager != null) {
            for (atlas.module.Module module : atlas.Atlas.moduleManager.modules.values()) {
                if (module != null && module.isEnabled()) {
                    on.add(new String[]{module.getName(), module.getClass().getSimpleName()});
                    if (module instanceof atlas.module.modules.Velocity
                            && ((atlas.module.modules.Velocity) module).holdsPackets()) {
                        on.add(new String[]{module.getName() + " (Lag)", "VelocityLag"});
                    }
                }
            }
        }
        return find(holdersOn(on), bindings(), key -> key == 0 ? "None" : atlas.util.KeyBindUtil.getKeyName(key));
    }

    public static synchronized List<Conflict> newFor(String module) {
        List<Conflict> out = new ArrayList<Conflict>();
        for (Conflict conflict : current()) {
            if (conflict.involves(module) && !isIgnored(conflict.key) && WARNED.add(conflict.key)) {
                out.add(conflict);
            }
        }
        return out;
    }

    public static void warnOnEnable(atlas.module.Module module) {
        try {
            for (Conflict conflict : newFor(module.getName())) {
                if (atlas.Atlas.notificationManager != null) {
                    atlas.Atlas.notificationManager.add(conflict.message, 6000L, 0xFFC857);
                }
            }
        } catch (Exception ignoredWarning) {
        }
    }

    static String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(i == names.size() - 1 ? " and " : ", ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    public static synchronized boolean isIgnored(String key) {
        return ignored().contains(key);
    }

    public static synchronized void ignore(String key) {
        if (ignored().add(key)) {
            save();
        }
    }

    private static Set<String> ignored() {
        if (ignored == null) {
            ignored = new LinkedHashSet<String>();
            try {
                if (FILE.exists()) {
                    for (String line : Files.readAllLines(FILE.toPath(), StandardCharsets.UTF_8)) {
                        if (!line.trim().isEmpty()) {
                            ignored.add(line.trim());
                        }
                    }
                }
            } catch (Exception ignoredRead) {
            }
        }
        return ignored;
    }

    private static void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            PrintWriter writer = new PrintWriter(FILE, "UTF-8");
            try {
                for (String key : ignored) {
                    writer.println(key);
                }
            } finally {
                writer.close();
            }
        } catch (Exception ignoredWrite) {
        }
    }

    static synchronized void resetForTest(Set<String> keys) {
        ignored = keys;
    }
}
