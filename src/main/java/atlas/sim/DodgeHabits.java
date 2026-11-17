package atlas.sim;

import java.util.HashMap;
import java.util.Map;

/**
 * Each player's way of getting out of an arrow's path, learned from the arrows shot at it (2026-11-16, owner:
 * "remember everyone's habits: how long after my shot they start to move and which way they usually go").
 *
 * After a shot, what the player really did is held against what it would have done carrying on and against each of
 * the ways out ({@link TargetDodges#WAYS}, in its own keys, as habits are): the first tick it left the carry-on path
 * is its reaction, and the way whose path its own stays nearest to is what it did. A player that never left the
 * path counts as "carried on".
 *
 * What it gives back: the player's reaction (an average, once there are two), and a weight for carrying on and for
 * each way, (count + 1) / (shots + ways + 1), so an unknown player weighs them all the same and one that always
 * steps left weighs left most. No game types; tested.
 */
public final class DodgeHabits {
    /** Ways out, as TargetDodges has them; one more slot after them for carrying on. */
    public static final int WAYS = TargetDodges.WAYS.length;
    /** How far off the carry-on path (blocks, across the ground) counts as having moved. */
    public static final double MOVED = 0.25;
    /** Ticks after the reaction the paths are compared over. */
    public static final int COMPARE = 8;

    /** One player's habit. */
    public static final class Habit {
        public String name = "";
        /** Its reaction, ticks after the shot reached the server; -1 until known. */
        public double reaction = -1.0;
        public int reactions;
        /** Counts per way, the last slot carrying on. */
        public final int[] counts = new int[WAYS + 1];

        public int shots() {
            int total = 0;
            for (int count : this.counts) {
                total += count;
            }
            return total;
        }

        /** The way it took most, or "carry on"; "-" while none is known. */
        public String usual() {
            int best = -1;
            for (int i = 0; i < this.counts.length; i++) {
                if (this.counts[i] > 0 && (best < 0 || this.counts[i] > this.counts[best])) {
                    best = i;
                }
            }
            return best < 0 ? "-" : best == WAYS ? "carry on" : TargetDodges.WAYS[best].name;
        }
    }

    private final Map<String, Habit> habits = new HashMap<String, Habit>();

    public Habit get(String key) {
        return this.habits.get(key);
    }

    /**
     * One shot's answer: its reaction in ticks (NaN when it did not move) and the way it took (-1 for carrying on).
     */
    public Habit record(String key, String name, double reaction, int way) {
        Habit habit = this.habits.get(key);
        if (habit == null) {
            habit = new Habit();
            this.habits.put(key, habit);
        }
        habit.name = name == null ? "" : name;
        if (!Double.isNaN(reaction)) {
            habit.reaction = habit.reactions == 0 ? reaction : habit.reaction * 0.7 + reaction * 0.3;
            habit.reactions++;
        }
        habit.counts[way < 0 || way >= WAYS ? WAYS : way]++;
        return habit;
    }

    /** Its reaction once two are known, else {@code fallback}. */
    public double reaction(String key, double fallback) {
        Habit habit = this.habits.get(key);
        return habit != null && habit.reactions >= 2 ? habit.reaction : fallback;
    }

    /** Weights for each way and, last, carrying on; they add up to 1. */
    public double[] weights(String key) {
        Habit habit = this.habits.get(key);
        double[] out = new double[WAYS + 1];
        int shots = habit == null ? 0 : habit.shots();
        for (int i = 0; i <= WAYS; i++) {
            out[i] = ((habit == null ? 0 : habit.counts[i]) + 1.0) / (shots + WAYS + 1.0);
        }
        return out;
    }

    /**
     * The reaction a shot shows, or NaN when it shows none (2026-11-16, from the first rounds: most came out 0).
     * A player off the carry-on path from the very first tick was already changing its keys when the shot came (a
     * fight is all strafing): that says nothing of how fast it reacts. One whose path stayed nearest to carrying on
     * did not dodge, whenever it drifted off. Neither is a reaction; the way it went still counts.
     */
    public static double reactionSample(int reactionTick, int way) {
        return reactionTick >= 1 && way >= 0 ? reactionTick : Double.NaN;
    }

    /**
     * The first tick (index into the arrays) the player was {@link #MOVED} off the carry-on path, or -1.
     *
     * @param actual   where it was, {x, z} a tick
     * @param carry    where carrying on would have put it, the same ticks
     */
    public static int reactionTick(double[][] actual, double[][] carry) {
        int n = Math.min(actual.length, carry.length);
        for (int i = 0; i < n; i++) {
            if (actual[i] != null && carry[i] != null
                    && Math.hypot(actual[i][0] - carry[i][0], actual[i][1] - carry[i][1]) > MOVED) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The way whose path stays nearest to where the player went, over {@link #COMPARE} ticks from {@code from}; -1
     * when carrying on is nearer than all of them.
     *
     * @param ways {x, z} a tick for each way, in WAYS order
     */
    public static int classify(double[][] actual, double[][] carry, double[][][] ways, int from) {
        double best = error(actual, carry, from);
        int pick = -1;
        for (int w = 0; w < ways.length; w++) {
            double e = error(actual, ways[w], from);
            if (e < best - 1.0E-9) {
                best = e;
                pick = w;
            }
        }
        return pick;
    }

    private static double error(double[][] actual, double[][] path, int from) {
        if (path == null) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        int n = 0;
        int to = Math.min(Math.min(actual.length, path.length), Math.max(0, from) + COMPARE);
        for (int i = Math.max(0, from); i < to; i++) {
            if (actual[i] == null || path[i] == null) {
                continue;
            }
            sum += Math.hypot(actual[i][0] - path[i][0], actual[i][1] - path[i][1]);
            n++;
        }
        return n == 0 ? Double.MAX_VALUE : sum / n;
    }

    /** Lines to keep it in a file: key, name, reaction, reactions, counts. */
    public String save() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Habit> entry : this.habits.entrySet()) {
            Habit h = entry.getValue();
            out.append(entry.getKey()).append(' ').append(h.name.isEmpty() ? "-" : h.name).append(' ')
                    .append(h.reaction).append(' ').append(h.reactions);
            for (int count : h.counts) {
                out.append(' ').append(count);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Reads what {@link #save} wrote; bad lines are skipped. */
    public void load(String text) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length != 4 + WAYS + 1) {
                continue;
            }
            try {
                Habit h = new Habit();
                h.name = "-".equals(parts[1]) ? "" : parts[1];
                h.reaction = Double.parseDouble(parts[2]);
                h.reactions = Integer.parseInt(parts[3]);
                for (int i = 0; i <= WAYS; i++) {
                    h.counts[i] = Integer.parseInt(parts[4 + i]);
                }
                this.habits.put(parts[0], h);
            } catch (NumberFormatException ignored) {
                // A line that is not ours.
            }
        }
    }
}
