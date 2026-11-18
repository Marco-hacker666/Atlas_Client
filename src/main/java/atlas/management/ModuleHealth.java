package atlas.management;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class ModuleHealth {
    public static final long RECENT_MS = 5L * 60L * 1000L;
    private static final int KEEP = 16;

    private static final class Count {
        int named;
        int involved;
        long lastAt;
        final ArrayDeque<Long> times = new ArrayDeque<Long>();
    }

    private static final Map<String, Count> COUNTS = new HashMap<String, Count>();

    private ModuleHealth() {
    }

    public static void named(String module, long now) {
        if (module == null || module.isEmpty()) {
            return;
        }
        synchronized (COUNTS) {
            Count count = countOf(module);
            count.named++;
            count.lastAt = now;
            count.times.addLast(now);
            while (count.times.size() > KEEP) {
                count.times.removeFirst();
            }
        }
    }

    public static void involved(String module, long now) {
        if (module == null || module.isEmpty()) {
            return;
        }
        synchronized (COUNTS) {
            countOf(module).involved++;
        }
    }

    public static void resetSession() {
        synchronized (COUNTS) {
            COUNTS.clear();
        }
    }

    private static Count countOf(String module) {
        Count count = COUNTS.get(module);
        if (count == null) {
            count = new Count();
            COUNTS.put(module, count);
        }
        return count;
    }

    public static final class Report {
        public static final Report NONE = new Report(0, 0, 0, 0L);

        public final int named;
        public final int involved;
        public final int recent;

        public final long lastAt;

        Report(int named, int involved, int recent, long lastAt) {
            this.named = named;
            this.involved = involved;
            this.recent = recent;
            this.lastAt = lastAt;
        }

        public boolean any() {
            return this.named > 0 || this.involved > 0;
        }
    }

    public static Report of(long now, String... names) {
        int named = 0;
        int involved = 0;
        int recent = 0;
        long lastAt = 0L;
        synchronized (COUNTS) {
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                if (name == null || seenBefore(names, i)) {
                    continue;
                }
                Count count = COUNTS.get(name);
                if (count == null) {
                    continue;
                }
                named += count.named;
                involved += count.involved;
                lastAt = Math.max(lastAt, count.lastAt);
                for (Long at : count.times) {
                    if (now - at <= RECENT_MS) {
                        recent++;
                    }
                }
            }
        }
        return named == 0 && involved == 0 ? Report.NONE : new Report(named, involved, recent, lastAt);
    }

    private static boolean seenBefore(String[] names, int index) {
        for (int j = 0; j < index; j++) {
            if (names[index].equals(names[j])) {
                return true;
            }
        }
        return false;
    }
}
