package atlas.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Dodge habits as everyone has seen them (2026-11-16, owner: "everyone's dodge data in the cloud, one record per
 * player, created on first meeting, read by everyone after"). What the relay sent for a player (the sum of every
 * Atlas client's observations, old ones faded), plus what this client saw of it since; the relay already holds this
 * client's earlier ones, so they are not counted twice.
 *
 * Players are keyed by name, lower case, kept apart for cracked (offline-mode) accounts, whose names anyone can take:
 * an offline account's UUID is the name-based version 3 one, a real account's is version 4. No game types; tested.
 */
public final class SharedHabits {
    private static final int SLOTS = DodgeHabits.WAYS + 1;

    private static final class Record {
        final double[] counts = new double[SLOTS];
        double reactionSum;
        double reactionN;
    }

    private final Map<String, Record> fetched = new HashMap<String, Record>();
    private final Map<String, Record> since = new HashMap<String, Record>();

    /** The key of a player. */
    public static String key(String name, boolean cracked) {
        return (cracked ? "c:" : "p:") + (name == null ? "" : name.toLowerCase(Locale.ROOT));
    }

    /** Whether a UUID is an offline-mode one (version 3, made from the name). */
    public static boolean cracked(java.util.UUID id) {
        return id != null && id.version() == 3;
    }

    /** What the relay sent for this player (counts in DodgeHabits slot order, carrying on last). */
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

    /** The relay knows this player (even with nothing yet): its answer came. */
    public synchronized boolean known(String key) {
        return this.fetched.containsKey(key);
    }

    /** One shot watched by this client, after the relay's answer. */
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

    /** Weights per way and, last, carrying on, as DodgeHabits.weights; null while the relay's answer has not come. */
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

    /** Its reaction from everyone's observations, once there are two; else {@code fallback}. */
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

    /** How many shots everyone has watched of it (faded); 0 while unknown. */
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
