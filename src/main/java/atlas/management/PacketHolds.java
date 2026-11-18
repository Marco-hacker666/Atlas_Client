package atlas.management;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public final class PacketHolds {
    public enum Direction { OUT, IN }

    public static final class Hold {
        public final String holder;
        public final Direction direction;
        public final int count;

        public final long since;
        public final String reason;
        public final String release;

        public Hold(String holder, Direction direction, int count, long since, String reason, String release) {
            this.holder = holder;
            this.direction = direction;
            this.count = count;
            this.since = since;
            this.reason = reason;
            this.release = release;
        }

        public String describe(long now) {
            StringBuilder sb = new StringBuilder(this.holder).append(' ')
                    .append(this.direction.name().toLowerCase(Locale.ROOT)).append(' ').append(this.count);
            if (this.since > 0L) {
                sb.append(" for ").append(Math.max(0L, now - this.since)).append("ms");
            }
            sb.append(" (").append(this.reason).append("; ").append(this.release).append(')');
            return sb.toString();
        }
    }

    public interface Source {
        Hold current();
    }

    public interface Lease {
        String owner();

        long ceilingMs();

        void expire();
    }

    public static final class Expiry {
        public final long at;
        public final String holder;
        public final int count;
        public final String why;

        Expiry(long at, String holder, int count, String why) {
            this.at = at;
            this.holder = holder;
            this.count = count;
            this.why = why;
        }

        @Override
        public String toString() {
            return this.holder + " " + this.count + " packets: " + this.why;
        }
    }

    private static final class Entry {
        final Source source;
        final Lease lease;

        Entry(Source source, Lease lease) {
            this.source = source;
            this.lease = lease;
        }
    }

    public static final long LEASE_MARGIN_MS = 1000L;

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<Entry>();
    private static volatile int expiries;
    private static volatile int failures;
    private static volatile Expiry lastExpiry;

    private PacketHolds() {
    }

    public static void register(Source source) {
        ENTRIES.add(new Entry(source, null));
    }

    public static void register(Source source, Lease lease) {
        ENTRIES.add(new Entry(source, lease));
    }

    public static boolean expire(String holder) {
        for (Entry entry : ENTRIES) {
            if (entry.lease == null) {
                continue;
            }
            Hold hold = read(entry.source);
            if (hold != null && hold.holder.equals(holder)) {
                try {
                    entry.lease.expire();
                    return true;
                } catch (RuntimeException e) {
                    failures++;
                    return false;
                }
            }
        }
        return false;
    }

    public static List<Hold> snapshot() {
        List<Hold> holds = new ArrayList<Hold>();
        for (Entry entry : ENTRIES) {
            Hold hold = read(entry.source);
            if (hold != null) {
                holds.add(hold);
            }
        }
        return holds;
    }

    private static Hold read(Source source) {
        try {
            Hold hold = source.current();
            return hold != null && hold.count > 0 ? hold : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static int total() {
        int total = 0;
        for (Hold hold : snapshot()) {
            total += hold.count;
        }
        return total;
    }

    public static String describe() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Hold hold : snapshot()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(hold.describe(now));
        }
        return sb.toString();
    }

    public static List<Expiry> enforce(long now, Predicate<String> moduleOn) {
        List<Expiry> ended = new ArrayList<Expiry>();
        for (Entry entry : ENTRIES) {
            if (entry.lease == null) {
                continue;
            }
            Hold hold = read(entry.source);
            if (hold == null) {
                continue;
            }
            String why = null;
            try {
                String owner = entry.lease.owner();
                long ceiling = entry.lease.ceilingMs();
                if (owner != null && !moduleOn.test(owner)) {
                    why = "held for " + owner + ", which is off";
                } else if (ceiling >= 0L && hold.since > 0L && now - hold.since > ceiling) {
                    why = "held " + (now - hold.since) + "ms, longer than its " + ceiling + "ms ceiling";
                }
            } catch (RuntimeException e) {
                failures++;
                continue;
            }
            if (why == null) {
                continue;
            }
            Expiry expiry = new Expiry(now, hold.holder, hold.count, why);
            try {
                entry.lease.expire();
            } catch (RuntimeException e) {
                failures++;
                expiry = new Expiry(now, hold.holder, hold.count, why + "; ending it failed: " + e);
            }
            expiries++;
            lastExpiry = expiry;
            ended.add(expiry);
        }
        return ended;
    }

    public static int expiries() {
        return expiries;
    }

    public static int failures() {
        return failures;
    }

    public static Expiry lastExpiry() {
        return lastExpiry;
    }

    public static final class Watchdog {
        private final Predicate<String> moduleOn;

        public Watchdog(Predicate<String> moduleOn) {
            this.moduleOn = moduleOn;
        }

        @EventTarget(Priority.LOWEST)
        public void onTick(TickEvent event) {
            if (event.getType() != EventType.POST) {
                return;
            }
            for (Expiry expiry : enforce(System.currentTimeMillis(), this.moduleOn)) {
                System.err.println("[Atlas] packet hold lease ended: " + expiry);
            }
        }
    }

    static void clearForTests() {
        ENTRIES.clear();
        expiries = 0;
        failures = 0;
        lastExpiry = null;
    }
}
