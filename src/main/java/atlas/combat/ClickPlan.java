package atlas.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Click times planned ahead in milliseconds and batched into 50 ms ticks, the
 * way Minecraft drains every attack-key press queued since the previous tick.
 * Planning ahead lets the aura ask whether a click will fall on a given future
 * tick (to turn only when the click will come with the turn).
 *
 * Intervals are log-normal around a CPS drawn once per combo (sigma 0.45, the
 * mean, not the median, on the drawn CPS); a combo ends after 250 ms in which
 * nobody used the planned presses.
 *
 * Follows LiquidBounce nextgen's ClickPlan and HumanClickTiming (GPL-3.0,
 * CCBlueX), rewritten for this client (2026-10-14).
 */
public final class ClickPlan {
    public static final long TICK_MS = 50L;
    private static final long HORIZON_MS = 1000L;
    private static final long IDLE_MS = 250L;
    private static final int HISTORY = 32;
    private static final long NONE = Long.MIN_VALUE;
    private static final double SIGMA = 0.45;
    private static final long MIN_INTERVAL_MS = 10L;
    private static final long MAX_INTERVAL_MS = 1000L;

    private final Random random;
    private final List<Long> times = new ArrayList<>();
    private int minCps = 10;
    private int maxCps = 14;
    private int maxPerTick = 1;
    private int next;
    private long tickTime;
    private long comboStart;
    private double comboCps;
    private int due;
    private int consumed;
    private long idleSince = NONE;

    public ClickPlan(Random random) {
        this.random = random;
    }

    /** Changes the CPS range or the per-tick cap; planned presses after this tick are planned again. */
    public void configure(int minCps, int maxCps, int maxPerTick) {
        int lo = Math.max(1, Math.min(minCps, maxCps));
        int hi = Math.max(lo, Math.max(minCps, maxCps));
        int cap = Math.max(1, maxPerTick);
        if (lo != this.minCps || hi != this.maxCps || cap != this.maxPerTick) {
            this.minCps = lo;
            this.maxCps = hi;
            this.maxPerTick = cap;
            this.comboCps = 0.0;
            replan();
        }
    }

    /** Starts a new combo with a press at {@code now}. */
    public void reset(long now) {
        this.times.clear();
        this.next = 0;
        this.due = 0;
        this.consumed = 0;
        this.idleSince = NONE;
        this.comboStart = now;
        this.comboCps = 0.0;
        this.tickTime = now - TICK_MS;
        this.times.add(now);
    }

    /** Advances to the tick at {@code now} and makes its presses available to {@link #take()}. */
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
        // Stay on the 50 ms grid so a look ahead matches what is consumed, unless the tick drifted off it.
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

    /** Presses that the tick {@code tick} ticks from now will hold (0 = this tick). */
    public int clicksAt(int tick) {
        if (tick <= 0) {
            return this.due;
        }
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

    /** Ticks until the next press, 0 if this tick has one; at most 20. */
    public int ticksUntilClick() {
        for (int t = 0; t < 20; t++) {
            if (clicksAt(t) > 0) {
                return t;
            }
        }
        return 20;
    }

    /** Uses one press of this tick; false when none is left. */
    public boolean take() {
        if (this.consumed >= this.due) {
            return false;
        }
        this.consumed++;
        return true;
    }

    private void replan() {
        int keep = Math.max(this.next, 1);
        while (this.times.size() > keep) {
            this.times.remove(this.times.size() - 1);
        }
    }

    private void planUntil(long time) {
        if (this.times.isEmpty()) {
            return;
        }
        while (this.times.get(this.times.size() - 1) <= time) {
            planNext();
        }
    }

    private void planNext() {
        long last = this.times.get(this.times.size() - 1);
        long at = last + nextInterval(last - this.comboStart);
        if (this.times.size() >= this.maxPerTick) {
            at = Math.max(at, this.times.get(this.times.size() - this.maxPerTick) + TICK_MS);
        }
        this.times.add(at);
    }

    long nextInterval(long comboMs) {
        if (comboMs == 0L || this.comboCps <= 0.0) {
            this.comboCps = this.minCps + this.random.nextDouble() * (this.maxCps - this.minCps);
        }
        double mu = Math.log(1000.0 / this.comboCps) - SIGMA * SIGMA / 2.0;
        long interval = Math.round(Math.exp(mu + SIGMA * this.random.nextGaussian()));
        return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, interval));
    }

    private void trim() {
        int excess = this.next - HISTORY;
        if (excess > 0) {
            this.times.subList(0, excess).clear();
            this.next -= excess;
        }
    }
}
