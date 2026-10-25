package atlas.lb.clicking;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * The presses ahead, planned on a timeline and consumed tick by tick
 * (2026-10-24, LiquidBounce NextGen port, GPL-3.0; ClickPlan.kt).
 *
 * Presses are planned up to a second ahead from the timing; each tick takes
 * those due by then (at most max-per-tick), so a lookahead (clicksAt(n))
 * says exactly what that tick will consume. A combo ends when a gap in ticks
 * or unused presses last longer than 250 ms; the next one starts fresh (and
 * Human timing draws a new CPS).
 */
public final class ClickPlan {
    public static final long TICK_MS = 50L;
    private static final long HORIZON_MS = 1000L;
    private static final long IDLE_MS = 250L;
    private static final int HISTORY = 32;
    private static final long NONE = Long.MIN_VALUE;

    private final ClickTiming timing;
    private final Random random;

    private int minCps = 11;
    private int maxCps = 14;
    private int maxPerTick = 2;
    /** Adds a guaranteed click to tick n when nothing is planned for it. */
    private IntPredicate enforced = tick -> false;

    private long tickTime;
    private final List<Long> times = new ArrayList<Long>();
    private final List<Long> intervals = new ArrayList<Long>();
    private int next;
    private long comboStart;
    private int due;
    private int consumed;
    private long idleSince = NONE;

    public ClickPlan(ClickTiming timing) {
        this(timing, new Random());
    }

    public ClickPlan(ClickTiming timing, Random random) {
        this.timing = timing;
        this.random = random;
    }

    public void setCps(int min, int max) {
        if (min != this.minCps || max != this.maxCps) {
            this.minCps = min;
            this.maxCps = max;
            replan();
        }
    }

    public void setMaxPerTick(int value) {
        if (value != this.maxPerTick) {
            this.maxPerTick = value;
            replan();
        }
    }

    public void setEnforced(IntPredicate enforced) {
        this.enforced = enforced;
    }

    public long getTickTime() {
        return this.tickTime;
    }

    public long comboMs() {
        return this.tickTime - this.comboStart;
    }

    /** Starts a new combo with a press at {@code now}. */
    public void reset(long now) {
        this.times.clear();
        this.intervals.clear();
        this.next = 0;
        this.due = 0;
        this.consumed = 0;
        this.idleSince = NONE;
        this.comboStart = now;
        this.tickTime = now - TICK_MS;
        this.times.add(now);
    }

    /** Advances to the next tick; presses nobody used for a while, or a gap in ticks, end the combo. */
    public void tick(long now) {
        if (this.consumed > 0) {
            this.idleSince = NONE;
        } else if (this.due > 0 && this.idleSince == NONE) {
            this.idleSince = this.tickTime;
        }
        if (this.times.isEmpty() || now - this.tickTime > IDLE_MS
                || this.idleSince != NONE && now - this.idleSince > IDLE_MS) {
            reset(now);
        }
        /* Stay on the 50 ms grid so lookahead matches consumption, unless the real tick drifted off it. */
        long expected = this.tickTime + TICK_MS;
        this.tickTime = Math.abs(now - expected) > TICK_MS ? now : expected;

        planUntil(this.tickTime + HORIZON_MS);
        int count = 0;
        while (this.next < this.times.size() && this.times.get(this.next) <= this.tickTime) {
            this.next++;
            count++;
        }
        this.due = Math.min(count, this.maxPerTick);
        this.consumed = 0;
        trim();
    }

    /** Presses for the tick {@code tick} ticks from now (0: this one). */
    public int clicksAt(int tick) {
        int planned = tick <= 0 ? this.due : plannedAt(tick);
        return planned == 0 && this.enforced.test(Math.max(0, tick)) ? 1 : planned;
    }

    private int plannedAt(int tick) {
        long from = this.tickTime + (tick - 1) * TICK_MS;
        long to = from + TICK_MS;
        planUntil(to);
        int count = 0;
        for (int i = this.next; i < this.times.size(); i++) {
            long time = this.times.get(i);
            if (time > to) {
                break;
            }
            if (time > from) {
                count++;
            }
        }
        return Math.min(count, this.maxPerTick);
    }

    /** A click attempt; true when it succeeded. */
    public interface Attempt {
        boolean run();
    }

    /**
     * Uses up the presses of this tick. A press failing {@code gate} is dropped
     * (as vanilla drops presses during its miss delay). Returns the successful attempts.
     */
    public int consume(BooleanSupplier gate, Attempt attempt) {
        int clicks = 0;
        while (take()) {
            if (gate.getAsBoolean() && attempt.run()) {
                clicks++;
            }
        }
        return clicks;
    }

    private boolean take() {
        if (this.consumed >= clicksAt(0)) {
            return false;
        }
        this.consumed++;
        return true;
    }

    /** Drops the presses planned after this tick, so they are planned again with the changed settings. */
    private void replan() {
        int keep = Math.max(this.next, 1);
        if (this.times.size() <= keep) {
            return;
        }
        int dropped = this.times.size() - keep;
        this.times.subList(keep, this.times.size()).clear();
        int from = Math.max(0, this.intervals.size() - dropped);
        this.intervals.subList(from, this.intervals.size()).clear();
    }

    private void planUntil(long time) {
        /* Before the first tick nothing is planned (a lookahead from onEnabled, or a tick that returned
           early): no presses yet, rather than reading the last of an empty list. */
        if (this.times.isEmpty()) {
            return;
        }
        while (this.times.get(this.times.size() - 1) <= time) {
            planNext();
        }
    }

    private void planNext() {
        long last = this.times.get(this.times.size() - 1);
        long time = last + this.timing.nextInterval(this.intervals, last - this.comboStart,
                this.minCps, this.maxCps, this.random);
        if (this.times.size() >= this.maxPerTick) {
            time = Math.max(time, this.times.get(this.times.size() - this.maxPerTick) + TICK_MS);
        }
        this.intervals.add(time - last);
        if (this.intervals.size() > HISTORY) {
            this.intervals.remove(0);
        }
        this.times.add(time);
    }

    private void trim() {
        int excess = this.next - HISTORY;
        if (excess > 0) {
            this.times.subList(0, excess).clear();
            this.next -= excess;
        }
    }
}
