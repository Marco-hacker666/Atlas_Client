package atlas.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class DamageWatch {
    public static final class Hit {
        public final int targetId;

        public final float dealt;
        public final float usual;
        public final long at;

        public final float expected;

        public final float total;

        public final int hits;

        Hit(int targetId, float dealt, float usual, long at, float expected) {
            this(targetId, dealt, usual, at, expected, dealt, 1);
        }

        Hit(int targetId, float dealt, float usual, long at, float expected, float total, int hits) {
            this.targetId = targetId;
            this.dealt = dealt;
            this.usual = usual;
            this.at = at;
            this.expected = expected;
            this.total = total;
            this.hits = hits;
        }
    }

    public static final class Verdict {
        public final int hits;
        public final float dealt;
        public final float usual;

        Verdict(int hits, float dealt, float usual) {
            this.hits = hits;
            this.dealt = dealt;
            this.usual = usual;
        }
    }

    public static final float LOW_SHARE = 0.6F;

    public static final int STREAK = 3;

    public static final int MIN_BASELINE = 6;

    private static final int BASELINE_SIZE = 12;

    private static final int SETTLE_TICKS = 6;

    private static final int SETTLE_TICKS_SCORE = 24;

    private static final int HIDDEN_AFTER = 5;

    private static final class Target {
        float lastHealth = Float.NaN;
        int lastHurtTime;
        long lastAttackAt;

        float before = Float.NaN;
        float lowest;
        int ticksLeft;

        int flinches;
        long hitAt;

        float attackExpected = Float.NaN;
        float hitExpected = Float.NaN;

        String attackBasis = "";
        String hitBasis = "";

        final Map<String, Deque<Float>> own = new HashMap<String, Deque<Float>>();
    }

    private final Map<Integer, Target> targets = new HashMap<Integer, Target>();

    private final Map<String, Deque<Float>> session = new HashMap<String, Deque<Float>>();

    private static final String RATIO = "ratio";
    private int lowStreak;
    private float lowSum;
    private int resolved;
    private int moved;
    private boolean hidden;

    public void attack(int targetId, long now) {
        attack(targetId, now, Float.NaN);
    }

    public void attack(int targetId, long now, float expected) {
        attack(targetId, now, expected, "");
    }

    public void attack(int targetId, long now, float expected, String basis) {
        Target t = target(targetId);
        t.lastAttackAt = now;
        t.attackExpected = expected;
        t.attackBasis = basis == null ? "" : basis;
    }

    private static String kindOf(float expected, String basis) {
        return !Float.isNaN(expected) && expected >= MIN_EXPECTED ? RATIO : "raw:" + basis;
    }

    public Hit tick(int targetId, float health, int hurtTime, long now, long window) {
        Target t = target(targetId);
        Hit hit = null;
        boolean flinched = hurtTime > t.lastHurtTime;
        if (t.ticksLeft > 0) {
            t.lowest = Math.min(t.lowest, health);
            if (flinched) {
                t.flinches++;
                flinched = false;
            }
            if (--t.ticksLeft == 0) {
                hit = resolve(targetId, t);
            }
        }
        if (flinched && t.ticksLeft == 0 && t.lastAttackAt > 0L && now - t.lastAttackAt <= window
                && !Float.isNaN(t.lastHealth) && t.lastHealth > 0.0F) {
            t.before = t.lastHealth;
            t.lowest = Math.min(t.lastHealth, health);
            t.ticksLeft = this.precision > 0.0F ? SETTLE_TICKS_SCORE : SETTLE_TICKS;
            t.flinches = 1;
            t.hitAt = now;
            t.lastAttackAt = 0L;
            t.hitExpected = t.attackExpected;
            t.hitBasis = t.attackBasis;
        }
        t.lastHurtTime = hurtTime;
        t.lastHealth = health;
        return hit;
    }

    private Hit resolve(int targetId, Target t) {
        float total = Math.max(0.0F, t.before - t.lowest);
        int hits = Math.max(1, t.flinches);
        float dealt = total / hits;
        t.before = Float.NaN;
        if (t.lowest <= 0.0F) {
            return null;
        }
        this.resolved++;
        if (dealt > 0.001F) {
            this.moved++;
        }
        if (!this.hidden && this.resolved >= HIDDEN_AFTER && this.moved == 0) {
            this.hidden = true;
        }
        String kind = kindOf(t.hitExpected, t.hitBasis);
        boolean ratio = RATIO.equals(kind);

        float value = ratio ? dealt / t.hitExpected : dealt;
        float usualValue = usual(t, kind);
        push(series(t.own, kind), value);
        push(series(this.session, kind), value);

        float usual = Float.isNaN(usualValue) ? Float.NaN : ratio ? usualValue * t.hitExpected : usualValue;
        Hit hit = new Hit(targetId, dealt, usual, t.hitAt, t.hitExpected, total, hits);
        noteMitigation(hit);
        if (!Float.isNaN(usualValue) && value <= usualValue * LOW_SHARE) {
            this.lowStreak++;
            this.lowSum += dealt;
        } else {
            this.lowStreak = 0;
            this.lowSum = 0.0F;
        }
        return hit;
    }

    public Verdict verdict(Hit last) {
        if (this.hidden || last == null || this.lowStreak < STREAK) {
            return null;
        }
        Verdict verdict = new Verdict(this.lowStreak, this.lowSum / this.lowStreak, last.usual);
        this.lowStreak = 0;
        this.lowSum = 0.0F;
        return verdict;
    }

    private float usual(Target t, String kind) {
        Deque<Float> own = t.own.get(kind);
        if (own != null && own.size() >= MIN_BASELINE) {
            return median(own);
        }
        Deque<Float> all = this.session.get(kind);
        if (all != null && all.size() >= MIN_BASELINE) {
            return median(all);
        }
        return Float.NaN;
    }

    private static Deque<Float> series(Map<String, Deque<Float>> byKind, String kind) {
        Deque<Float> values = byKind.get(kind);
        if (values == null) {
            values = new ArrayDeque<Float>();
            byKind.put(kind, values);
        }
        return values;
    }

    public boolean healthHidden() {
        return this.hidden;
    }

    public int resolved() {
        return this.resolved;
    }

    public void forget(long now, long olderThan) {
        Iterator<Map.Entry<Integer, Target>> it = this.targets.entrySet().iterator();
        while (it.hasNext()) {
            Target t = it.next().getValue();
            if (t.ticksLeft == 0 && now - Math.max(t.lastAttackAt, t.hitAt) > olderThan) {
                it.remove();
            }
        }
    }

    public List<Integer> watched() {
        return new ArrayList<Integer>(this.targets.keySet());
    }

    public static final int DROP_STREAK = 4;

    private static final long DROP_GAP_MS = 3000L;
    private int dropStreak;
    private long lastDroppedAt;

    public int dropped(long now) {
        if (now - this.lastDroppedAt > DROP_GAP_MS) {
            this.dropStreak = 0;
        }
        this.lastDroppedAt = now;
        this.dropStreak++;
        return this.dropStreak == DROP_STREAK ? this.dropStreak : 0;
    }

    public void landed() {
        this.dropStreak = 0;
    }

    public static final float MITIGATED_SHARE = 0.65F;
    public static final int RECENT = 4;
    public static final int LOW_RUN = 3;

    private static final float MIN_EXPECTED = 2.0F;
    private final Deque<Hit> recentKnown = new ArrayDeque<Hit>();

    private float precision;

    public void setPrecision(float precision) {
        this.precision = precision;
    }

    private void noteMitigation(Hit hit) {
        if (Float.isNaN(hit.expected) || hit.expected < MIN_EXPECTED) {
            return;
        }
        this.recentKnown.addLast(hit);
        while (this.recentKnown.size() > RECENT) {
            this.recentKnown.removeFirst();
        }
    }

    public boolean mitigated(Hit hit) {
        return !Float.isNaN(hit.expected) && hit.dealt + this.precision * 0.5F <= hit.expected * MITIGATED_SHARE;
    }

    public static final class Mitigation {
        public final boolean active;
        public final int low;
        public final int of;
        public final float dealt;
        public final float expected;
        public final long lastAt;

        Mitigation(boolean active, int low, int of, float dealt, float expected, long lastAt) {
            this.active = active;
            this.low = low;
            this.of = of;
            this.dealt = dealt;
            this.expected = expected;
            this.lastAt = lastAt;
        }
    }

    public Mitigation mitigation(long now, long maxAge) {
        int low = 0;
        float dealt = 0.0F;
        float expected = 0.0F;
        long last = 0L;
        for (Hit hit : this.recentKnown) {
            last = Math.max(last, hit.at);
            if (mitigated(hit)) {
                low++;
                dealt += hit.dealt;
                expected += hit.expected;
            }
        }
        boolean active = low >= LOW_RUN && now - last <= maxAge;
        return new Mitigation(active, low, this.recentKnown.size(),
                low > 0 ? dealt / low : 0.0F, low > 0 ? expected / low : 0.0F, last);
    }

    public int droppedRun(long now) {
        return now - this.lastDroppedAt > DROP_GAP_MS ? 0 : this.dropStreak;
    }

    public void reset() {
        this.recentKnown.clear();
        this.dropStreak = 0;
        this.lastDroppedAt = 0L;
        this.targets.clear();
        this.session.clear();
        this.lowStreak = 0;
        this.lowSum = 0.0F;
        this.resolved = 0;
        this.moved = 0;
        this.hidden = false;
    }

    private Target target(int id) {
        Target t = this.targets.get(id);
        if (t == null) {
            t = new Target();
            this.targets.put(id, t);
        }
        return t;
    }

    private static void push(Deque<Float> values, float value) {
        values.addLast(value);
        while (values.size() > BASELINE_SIZE) {
            values.removeFirst();
        }
    }

    static float median(Deque<Float> values) {
        float[] sorted = new float[values.size()];
        int i = 0;
        for (Float v : values) {
            sorted[i++] = v;
        }
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0F;
    }
}
