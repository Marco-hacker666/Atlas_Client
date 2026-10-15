package myau.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * How much each landed hit took off its target, and whether that has fallen
 * to a fraction of what hits usually do (2026-09-28).
 *
 * Some anticheats do not refuse a hit they distrust -- the target still
 * flinches -- they quietly cut its damage: a "silent" flag. Nothing in the
 * client says so. What it can see is the target's health (and absorption)
 * before and after the flinch that answers its own attack, so a run of hits
 * that each take a small part of the usual amount is the sign.
 *
 * Fed once a client tick with each watched target's health and hurt time;
 * plain arithmetic, so it can be tested. Client thread.
 *
 * 2026-10-13 (owner: a different weapon or a bare hand does different
 * damage, and so does the opponent's armour): "usual" is no longer one
 * number for every hit. A hit whose vanilla minimum is known (a player
 * target: weapon, enchantments, potions, armour, protection, blocking) is
 * judged as a share of that minimum, against the shares of earlier such
 * hits; any other is judged in raw health against earlier hits made with
 * the same weapon (the basis the caller passes). Changing weapon, or
 * moving to a better-armoured opponent, no longer reads as low damage.
 */
public final class DamageWatch {

    /** One landed hit, resolved. */
    public static final class Hit {
        public final int targetId;
        /** What one hit took off: the health lost in the window over the hits in it. */
        public final float dealt;
        public final float usual;
        public final long at;
        /** The least vanilla would have taken off (DamageModel); NaN when not known. */
        public final float expected;
        /** Health lost over the whole window. */
        public final float total;
        /** Flinches in the window: this hit and any that landed before it settled. */
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

    /** A run of hits that did far less than usual. */
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

    /* Tuning, deliberately fixed: see ENGINEERING-NOTES. */
    /** A hit this share of the usual or less is a low one (0.35 -> 0.6, 2026-10-13: see MITIGATED_SHARE). */
    public static final float LOW_SHARE = 0.6F;
    /** Low hits in a row before it is called. */
    public static final int STREAK = 3;
    /** Hits needed before "usual" means anything. */
    public static final int MIN_BASELINE = 6;
    /** Hits kept for "usual". */
    private static final int BASELINE_SIZE = 12;
    /** Ticks after the flinch to wait for the health to follow it. */
    private static final int SETTLE_TICKS = 6;
    /* 2026-10-12: health read from the tab list or the score under the name
       is refreshed by the server's own scoreboard task, often a second or
       more after the flinch. Six ticks resolved most hits there as 0 dealt
       while HitCheck saw 92-97% of them land (owner's log) -- "mitigation?"
       out of nothing. Those readings get a second to catch up. */
    private static final int SETTLE_TICKS_SCORE = 24;
    /** Hits read before deciding health is hidden, if none moved it. */
    private static final int HIDDEN_AFTER = 5;

    private static final class Target {
        float lastHealth = Float.NaN;
        int lastHurtTime;
        long lastAttackAt;
        /* The hit being resolved: health before the flinch, lowest since. */
        float before = Float.NaN;
        float lowest;
        int ticksLeft;
        /* Flinches since the one being resolved began, itself included. */
        int flinches;
        long hitAt;
        /* The least the pending attack should take off, and that of the hit being resolved. */
        float attackExpected = Float.NaN;
        float hitExpected = Float.NaN;
        /* What the pending attack was made with, and the hit being resolved. */
        String attackBasis = "";
        String hitBasis = "";
        /* This target's earlier hits, by kind (see kindOf). */
        final Map<String, Deque<Float>> own = new HashMap<String, Deque<Float>>();
    }

    private final Map<Integer, Target> targets = new HashMap<Integer, Target>();
    /* The session's earlier hits, by kind. */
    private final Map<String, Deque<Float>> session = new HashMap<String, Deque<Float>>();
    /** The kind of hit judged as a share of its vanilla minimum. */
    private static final String RATIO = "ratio";
    private int lowStreak;
    private float lowSum;
    private int resolved;
    private int moved;
    private boolean hidden;

    /** This client attacked the target (the packet left). */
    public void attack(int targetId, long now) {
        attack(targetId, now, Float.NaN);
    }

    /** The same, with the least the hit should take off (DamageModel), for the mitigation check. */
    public void attack(int targetId, long now, float expected) {
        attack(targetId, now, expected, "");
    }

    /**
     * The same, with what the hit was made with ({@code basis}: the weapon,
     * its enchantments and the attacker's potions), for hits whose minimum
     * is not known.
     */
    public void attack(int targetId, long now, float expected, String basis) {
        Target t = target(targetId);
        t.lastAttackAt = now;
        t.attackExpected = expected;
        t.attackBasis = basis == null ? "" : basis;
    }

    /** Which earlier hits a hit is compared with: RATIO when its minimum is known, else its weapon's. */
    private static String kindOf(float expected, String basis) {
        return !Float.isNaN(expected) && expected >= MIN_EXPECTED ? RATIO : "raw:" + basis;
    }

    /**
     * One tick of a watched target. Returns the hit it resolved, if any.
     *
     * @param health     health plus absorption as the client has it
     * @param hurtTime   the target's hurt time (10 on the flinch, counting down)
     * @param window     how long after an attack a flinch is still its answer
     */
    public Hit tick(int targetId, float health, int hurtTime, long now, long window) {
        Target t = target(targetId);
        Hit hit = null;
        boolean flinched = hurtTime > t.lastHurtTime;
        if (t.ticksLeft > 0) {
            t.lowest = Math.min(t.lowest, health);
            if (flinched) {
                /* 2026-10-15: with tab health the window is 24 ticks, room for
                   two more hits of a combo. Their damage was all put on the
                   first: the old aura "dealt" 7.3 a hit and KillAura2 4.3 --
                   two hits a window against one, not a damage cut. */
                t.flinches++;
                flinched = false;
            }
            if (--t.ticksLeft == 0) {
                hit = resolve(targetId, t);
            }
        }
        if (flinched && t.ticksLeft == 0 && t.lastAttackAt > 0L && now - t.lastAttackAt <= window
                && !Float.isNaN(t.lastHealth) && t.lastHealth > 0.0F) {
            /* The health before the flinch: the tick before, since the
               metadata can land with it or a tick after. */
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
            /* Dead: the last hit's damage is capped by what was left. */
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
        /* A share of the minimum, or health, as the kind says. */
        float value = ratio ? dealt / t.hitExpected : dealt;
        float usualValue = usual(t, kind);
        push(series(t.own, kind), value);
        push(series(this.session, kind), value);
        /* Reported in health either way. */
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

    /**
     * A run of low hits, once per run: STREAK in a row, each at most
     * LOW_SHARE of the usual. Null otherwise.
     */
    public Verdict verdict(Hit last) {
        if (this.hidden || last == null || this.lowStreak < STREAK) {
            return null;
        }
        Verdict verdict = new Verdict(this.lowStreak, this.lowSum / this.lowStreak, last.usual);
        this.lowStreak = 0;
        this.lowSum = 0.0F;
        return verdict;
    }

    /** "Usual" for this kind: this target's median once it has enough, else the session's. NaN until either does. */
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

    /** Whether the server looks to hide other players' health (it never moves). */
    public boolean healthHidden() {
        return this.hidden;
    }

    /** Hits resolved since start. */
    public int resolved() {
        return this.resolved;
    }

    /** Forget targets not attacked for a while. */
    public void forget(long now, long olderThan) {
        Iterator<Map.Entry<Integer, Target>> it = this.targets.entrySet().iterator();
        while (it.hasNext()) {
            Target t = it.next().getValue();
            if (t.ticksLeft == 0 && now - Math.max(t.lastAttackAt, t.hitAt) > olderThan) {
                it.remove();
            }
        }
    }

    /** The targets being watched. */
    public List<Integer> watched() {
        return new ArrayList<Integer>(this.targets.keySet());
    }

    // ------------------------------------------------------------ dropped hits

    /** Hits the server did not react to at all, in a row, before it is called. */
    public static final int DROP_STREAK = 4;
    /** A gap this long between two dropped hits starts a new run. */
    private static final long DROP_GAP_MS = 3000L;
    private int dropStreak;
    private long lastDroppedAt;

    /**
     * A hit the server did not act on at all -- no flinch, no damage, though
     * in range and in sight (HitCheck's "dropped"). Returns the run's length
     * the moment it reaches DROP_STREAK, once per run; 0 otherwise.
     */
    public int dropped(long now) {
        if (now - this.lastDroppedAt > DROP_GAP_MS) {
            this.dropStreak = 0;
        }
        this.lastDroppedAt = now;
        this.dropStreak++;
        return this.dropStreak == DROP_STREAK ? this.dropStreak : 0;
    }

    /** A hit that landed ends a run of dropped ones. */
    public void landed() {
        this.dropStreak = 0;
    }

    // -------------------------------------------------------------- mitigation

    /*
     * "Mitigation" (2026-10-04): the server cutting this client's damage, read
     * as recent hits doing far less than vanilla says they must -- not less
     * than usual, which a mitigation present from the first hit would make
     * the usual. A hit is low when it took off at most MITIGATED_SHARE of the
     * least it should have, less the health precision. Called when LOW_RUN of
     * the last RECENT hits with a known expectation are low.
     */
    /* 0.5 -> 0.65, and half the health precision instead of all of it
       (2026-10-13). Owner, Pika BedFight: hits taking 2 hp where they
       normally take 3-5 -- half -- were never called. At 0.5 plus a whole
       point of precision a hit had to lose well over half, so a cut to half
       could not be seen at all. A whole-number reading is off by at most one
       point and by half on average. Each hit's numbers now go to the flag log
       (HIT-DMG) to tune this from evidence. */
    public static final float MITIGATED_SHARE = 0.65F;
    public static final int RECENT = 4;
    public static final int LOW_RUN = 3;
    /** Below this an expectation is too small to tell anything from. */
    private static final float MIN_EXPECTED = 2.0F;
    private final Deque<Hit> recentKnown = new ArrayDeque<Hit>();
    /** Health precision: 0 for exact health, 1 for a whole-number score (tab, under the name). */
    private float precision;

    /** How exact the health readings are, for the next hits: see recentKnown. */
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

    /** Whether a hit took off much less than it had to. */
    public boolean mitigated(Hit hit) {
        return !Float.isNaN(hit.expected) && hit.dealt + this.precision * 0.5F <= hit.expected * MITIGATED_SHARE;
    }

    /** The state of the mitigation check. */
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

    /**
     * The recent hits, judged: active when LOW_RUN of the last RECENT are low,
     * and the newest of them is no older than maxAge. dealt/expected are the
     * averages of the low ones.
     */
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

    /** The current run of dropped hits (0 when the last one was more than DROP_GAP_MS ago). */
    public int droppedRun(long now) {
        return now - this.lastDroppedAt > DROP_GAP_MS ? 0 : this.dropStreak;
    }

    /** A new server: its hits and its health rules are its own. */
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
