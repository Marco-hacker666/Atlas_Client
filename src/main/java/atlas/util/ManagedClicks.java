package atlas.util;

import java.util.ArrayDeque;

/**
 * The bookkeeping of VanillaInput, kept free of the game so it is tested on
 * its own (2026-10-26): what each queued click is to point at, in order, and
 * the swap of the crosshair for one click and back.
 *
 * @param <T> what a click points at (the game's MovingObjectPosition)
 */
public final class ManagedClicks<T> {
    private final ArrayDeque<T> queued = new ArrayDeque<T>();
    private T saved;
    private boolean swapped;

    /** A click pressed this tick, to point at {@code target}. */
    public void queue(T target) {
        this.queued.addLast(target);
    }

    /** The target of the next click vanilla makes, or null when none of ours is waiting. */
    public T next() {
        return this.queued.pollFirst();
    }

    public boolean pending() {
        return !this.queued.isEmpty();
    }

    /** End of the tick: presses vanilla did not turn into clicks (an item in use) are dropped. */
    public void clear() {
        this.queued.clear();
    }

    /**
     * For one click: returns what the crosshair is to be while it runs and
     * remembers {@code current} to put back. {@code managed} null: no swap.
     */
    public T swapIn(T current, T managed) {
        if (managed == null) {
            this.swapped = false;
            return current;
        }
        this.saved = current;
        this.swapped = true;
        return managed;
    }

    /** After the click: what the crosshair goes back to, or {@code current} when nothing was swapped. */
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
