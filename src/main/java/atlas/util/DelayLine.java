package atlas.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Items held in arrival order, let out the way a slow connection lets them
 * out (2026-10-27, KnockbackDelay's "the server must believe the latency is
 * real"): never out of order, each one once its arrival plus the delay
 * current at the time has passed, so a delay that shrinks lets the queue out
 * a little faster each tick instead of all at once.
 *
 * Thread-safe; plain data, tested on its own.
 */
public final class DelayLine<T> {
    private final ArrayDeque<T> items = new ArrayDeque<T>();
    private final ArrayDeque<Long> arrivals = new ArrayDeque<Long>();

    public synchronized void add(T item, long arrivalMs) {
        this.items.addLast(item);
        this.arrivals.addLast(arrivalMs);
    }

    /** Everything whose arrival + {@code delayMs} is at or before {@code nowMs}, oldest first; stops at the first not due. */
    public synchronized List<T> due(long nowMs, long delayMs) {
        if (this.items.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> out = new ArrayList<T>();
        while (!this.items.isEmpty() && this.arrivals.peekFirst() + delayMs <= nowMs) {
            this.arrivals.pollFirst();
            out.add(this.items.pollFirst());
        }
        return out;
    }

    /** Everything, oldest first. */
    public synchronized List<T> drain() {
        List<T> out = new ArrayList<T>(this.items);
        this.items.clear();
        this.arrivals.clear();
        return out;
    }

    public synchronized void clear() {
        this.items.clear();
        this.arrivals.clear();
    }

    public synchronized int size() {
        return this.items.size();
    }

    public synchronized boolean isEmpty() {
        return this.items.isEmpty();
    }

    /** When the oldest item arrived; 0 when empty. */
    public synchronized long oldestArrival() {
        Long first = this.arrivals.peekFirst();
        return first == null ? 0L : first;
    }
}
