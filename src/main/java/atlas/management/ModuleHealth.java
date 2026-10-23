package atlas.management;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * How often each module was named as the likely cause of a server correction
 * this session (2026-10-23, docs/GUI-AUDIT-2026-10-23.md F1), for the menu's
 * health marks.
 *
 * FlagDetector already weighs every correction (Attribution) and keeps who
 * it implicated, but that record is a plain map written from the network
 * thread and trimmed by whoever reads it (FlagResponder reads it with a short
 * window), so nothing else can count from it. This is a count of its own,
 * kept apart:
 *
 *   named     corrections put down to this module alone (Attribution SINGLE,
 *             the only verdict FlagResponder acts on);
 *   involved  corrections where it was one of two or more close candidates
 *             (MULTIPLE_CANDIDATES), shown more quietly;
 *   recent    how many of the named ones were in the last five minutes.
 *
 * Kept by the name the module was recorded under, which is its menu name or
 * its class name depending on who noted it; the menu asks by both. Reset at
 * the start of every connection, as FlagDetector's own record is.
 */
public final class ModuleHealth {

    /** "Recent", for the red mark: three named corrections inside this. */
    public static final long RECENT_MS = 5L * 60L * 1000L;
    private static final int KEEP = 16;

    private static final class Count {
        int named;
        int involved;
        long lastAt;
        final ArrayDeque<Long> times = new ArrayDeque<Long>();
    }

    /** Guarded by itself: written from the network thread, read by the menu a few times a second. */
    private static final Map<String, Count> COUNTS = new HashMap<String, Count>();

    private ModuleHealth() {
    }

    /** A correction put down to this module alone. */
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

    /** A correction with this module among two or more close candidates. */
    public static void involved(String module, long now) {
        if (module == null || module.isEmpty()) {
            return;
        }
        synchronized (COUNTS) {
            countOf(module).involved++;
        }
    }

    /** A new connection: what the last server objected to says nothing about this one. */
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

    /** What the menu shows for one module. */
    public static final class Report {
        public static final Report NONE = new Report(0, 0, 0, 0L);

        public final int named;
        public final int involved;
        public final int recent;
        /** When it was last named; 0 never. */
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

    /** The counts recorded under any of a module's names (its menu name, its class name). */
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

    /** A module whose menu name is its class name is asked for once, not twice. */
    private static boolean seenBefore(String[] names, int index) {
        for (int j = 0; j < index; j++) {
            if (names[index].equals(names[j])) {
                return true;
            }
        }
        return false;
    }
}
