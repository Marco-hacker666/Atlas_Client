package atlas.module.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Velocity's Lag mode: the queue (2026-10-24, owner: "a lag velocity").
 *
 * After Rise's DelayVelocity: from this player's own knockback on, that
 * knockback and every transaction (and, optionally, explosion) wait in one
 * queue and reach the game together, in the order they came, after a few
 * ticks. Nothing else is held, so the world keeps moving. The server sees its
 * transactions answered late -- a short rise in ping -- and the knockback
 * taken late with them, which is what latency looks like.
 *
 * The delay is cut so ping plus delay stays within a budget (as FakeLag's
 * ping-budget); a cooldown after each release keeps a combo from holding the
 * player permanently. Thread-safe: packets arrive on the network thread, the
 * release runs on the client thread.
 *
 * @param <T> the queued packet type (a plain object in tests)
 */
final class VelocityLag<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<T>();
    private int releaseTick = -1;
    private long cooldownUntil;

    /** Ticks to hold for: the setting, cut so ping + delay stays within the budget; 0 = do not hold. */
    static int holdTicks(int ticks, int ping, int budget) {
        if (budget <= 0 || ping < 0) {
            return ticks;
        }
        return Math.max(0, Math.min(ticks, (budget - ping) / 50));
    }

    synchronized boolean holding() {
        return this.releaseTick >= 0;
    }

    synchronized int size() {
        return this.queue.size();
    }

    /** Starts holding with this knockback; false when it may not (holding already, cooling down, no ticks). */
    synchronized boolean start(T knockback, int nowTick, int ticks, long nowMs) {
        if (this.releaseTick >= 0 || nowMs < this.cooldownUntil || ticks < 1) {
            return false;
        }
        this.releaseTick = nowTick + ticks;
        this.queue.addLast(knockback);
        return true;
    }

    /** Adds to the queue while holding; false (not taken) otherwise. */
    synchronized boolean add(T packet) {
        if (this.releaseTick < 0) {
            return false;
        }
        this.queue.addLast(packet);
        return true;
    }

    /** Everything, in order, once the hold is over; empty before. */
    synchronized List<T> due(int nowTick, long nowMs, int cooldownMs) {
        if (this.releaseTick < 0 || nowTick < this.releaseTick) {
            return new ArrayList<T>();
        }
        return drain(nowMs, cooldownMs);
    }

    /** Everything, in order, now (a correction, a world change, switched off, the queue too long). */
    synchronized List<T> drain(long nowMs, int cooldownMs) {
        List<T> out = new ArrayList<T>(this.queue);
        this.queue.clear();
        if (this.releaseTick >= 0) {
            this.cooldownUntil = nowMs + cooldownMs;
        }
        this.releaseTick = -1;
        return out;
    }
}
