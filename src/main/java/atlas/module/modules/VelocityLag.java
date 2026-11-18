package atlas.module.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class VelocityLag<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<T>();
    private int releaseTick = -1;
    private long cooldownUntil;

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

    synchronized boolean start(T knockback, int nowTick, int ticks, long nowMs) {
        if (this.releaseTick >= 0 || nowMs < this.cooldownUntil || ticks < 1) {
            return false;
        }
        this.releaseTick = nowTick + ticks;
        this.queue.addLast(knockback);
        return true;
    }

    synchronized boolean add(T packet) {
        if (this.releaseTick < 0) {
            return false;
        }
        this.queue.addLast(packet);
        return true;
    }

    synchronized List<T> due(int nowTick, long nowMs, int cooldownMs) {
        if (this.releaseTick < 0 || nowTick < this.releaseTick) {
            return new ArrayList<T>();
        }
        return drain(nowMs, cooldownMs);
    }

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
