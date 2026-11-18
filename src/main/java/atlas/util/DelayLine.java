package atlas.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DelayLine<T> {
    private final ArrayDeque<T> items = new ArrayDeque<T>();
    private final ArrayDeque<Long> arrivals = new ArrayDeque<Long>();

    public synchronized void add(T item, long arrivalMs) {
        this.items.addLast(item);
        this.arrivals.addLast(arrivalMs);
    }

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

    public synchronized long oldestArrival() {
        Long first = this.arrivals.peekFirst();
        return first == null ? 0L : first;
    }
}
