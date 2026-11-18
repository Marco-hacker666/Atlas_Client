package atlas.util;

import java.util.ArrayDeque;

public final class ManagedClicks<T> {
    private final ArrayDeque<T> queued = new ArrayDeque<T>();
    private T saved;
    private boolean swapped;

    public void queue(T target) {
        this.queued.addLast(target);
    }

    public T next() {
        return this.queued.pollFirst();
    }

    public boolean pending() {
        return !this.queued.isEmpty();
    }

    public void clear() {
        this.queued.clear();
    }

    public T swapIn(T current, T managed) {
        if (managed == null) {
            this.swapped = false;
            return current;
        }
        this.saved = current;
        this.swapped = true;
        return managed;
    }

    public T restore(T current) {
        if (!this.swapped) {
            return current;
        }
        this.swapped = false;
        T back = this.saved;
        this.saved = null;
        return back;
    }
}
