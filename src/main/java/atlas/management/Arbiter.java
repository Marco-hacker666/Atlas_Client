package atlas.management;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Arbiter {
    private static volatile String catcher;
    private static volatile String viewHolder;

    private static final Set<String> yielded = ConcurrentHashMap.newKeySet();

    private Arbiter() {
    }

    public static void setCatching(String module, boolean on) {
        if (on) {
            catcher = module;
        } else if (module.equals(catcher)) {
            catcher = null;
        }
    }

    public static boolean catching() {
        return catcher != null;
    }

    public static String catcher() {
        return catcher;
    }

    public static void setViewHeld(String module, boolean on) {
        if (on) {
            viewHolder = module;
        } else if (module.equals(viewHolder)) {
            viewHolder = null;
        }
    }

    public static boolean viewHeld() {
        return viewHolder != null;
    }

    public static void yielded(String module) {
        if (catcher != null) {
            yielded.add(module);
        }
    }

    public static List<String> drainYielded() {
        List<String> names = new ArrayList<String>(yielded);
        yielded.removeAll(names);
        return names;
    }
}
