package atlas.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public final class SharedHabits {
    private static final int SLOTS = DodgeHabits.WAYS + 1;

    private static final class Record {
        final double[] counts = new double[SLOTS];
        double reactionSum;
        double reactionN;
    }

    private final Map<String, Record> fetched = new HashMap<String, Record>();
    private final Map<String, Record> since = new HashMap<String, Record>();

    public static String key(String name, boolean cracked) {
        return (cracked ? "c:" : "p:") + (name == null ? "" : name.toLowerCase(Locale.ROOT));
    }

    public static boolean cracked(java.util.UUID id) {
        return id != null && id.version() == 3;
    }

    public synchronized void fetched(String key, double[] counts, double reactionSum, double reactionN) {
        Record r = new Record();
        for (int i = 0; i < SLOTS && counts != null && i < counts.length; i++) {
            r.counts[i] = Math.max(0.0, counts[i]);
        }
        r.reactionSum = Math.max(0.0, reactionSum);
        r.reactionN = Math.max(0.0, reactionN);
        this.fetched.put(key, r);
        this.since.remove(key);
    }

    public synchronized boolean known(String key) {
        return this.fetched.containsKey(key);
    }

    public synchronized void saw(String key, double reaction, int way) {
        Record r = this.since.get(key);
        if (r == null) {
            r = new Record();
            this.since.put(key, r);
        }
        r.counts[way < 0 || way >= DodgeHabits.WAYS ? DodgeHabits.WAYS : way] += 1.0;
        if (!Double.isNaN(reaction)) {
            r.reactionSum += reaction;
            r.reactionN += 1.0;
        }
    }

    public synchronized double[] weights(String key) {
        Record f = this.fetched.get(key);
        if (f == null) {
            return null;
        }
        Record s = this.since.get(key);
        double total = 0.0;
        double[] counts = new double[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            counts[i] = f.counts[i] + (s == null ? 0.0 : s.counts[i]);
            total += counts[i];
        }
        double[] out = new double[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            out[i] = (counts[i] + 1.0) / (total + SLOTS);
        }
        return out;
    }

    public synchronized double reaction(String key, double fallback) {
        Record f = this.fetched.get(key);
        if (f == null) {
            return fallback;
        }
        Record s = this.since.get(key);
        double n = f.reactionN + (s == null ? 0.0 : s.reactionN);
        double sum = f.reactionSum + (s == null ? 0.0 : s.reactionSum);
        return n >= 2.0 ? sum / n : fallback;
    }

    public synchronized double shots(String key) {
        Record f = this.fetched.get(key);
        if (f == null) {
            return 0.0;
        }
        Record s = this.since.get(key);
        double total = 0.0;
        for (int i = 0; i < SLOTS; i++) {
            total += f.counts[i] + (s == null ? 0.0 : s.counts[i]);
        }
        return total;
    }
}
