package atlas.sim;

import java.util.HashMap;
import java.util.Map;

public final class DodgeHabits {
    public static final int WAYS = TargetDodges.WAYS.length;

    public static final double MOVED = 0.25;

    public static final int COMPARE = 8;

    public static final class Habit {
        public String name = "";

        public double reaction = -1.0;
        public int reactions;

        public final int[] counts = new int[WAYS + 1];

        public int shots() {
            int total = 0;
            for (int count : this.counts) {
                total += count;
            }
            return total;
        }

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

    public double reaction(String key, double fallback) {
        Habit habit = this.habits.get(key);
        return habit != null && habit.reactions >= 2 ? habit.reaction : fallback;
    }

    public double[] weights(String key) {
        Habit habit = this.habits.get(key);
        double[] out = new double[WAYS + 1];
        int shots = habit == null ? 0 : habit.shots();
        for (int i = 0; i <= WAYS; i++) {
            out[i] = ((habit == null ? 0 : habit.counts[i]) + 1.0) / (shots + WAYS + 1.0);
        }
        return out;
    }

    public static double reactionSample(int reactionTick, int way) {
        return reactionTick >= 1 && way >= 0 ? reactionTick : Double.NaN;
    }

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
            }
        }
    }
}
