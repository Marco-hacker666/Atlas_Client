package atlas.nickresolver;

import java.util.ArrayDeque;
import java.util.function.IntSupplier;

public final class RateLimiter {
    private final IntSupplier limit;
    private final long windowMs;
    private final long minSpacingMs;
    private final ArrayDeque<Long> issued = new ArrayDeque<Long>();
    private long pausedUntil;
    private long lastIssued;

    public RateLimiter(IntSupplier limit, long windowMs, long minSpacingMs) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.minSpacingMs = minSpacingMs;
    }

    public void acquire() throws InterruptedException {
        while (true) {
            long wait;
            synchronized (this) {
                long now = System.currentTimeMillis();
                while (!this.issued.isEmpty() && now - this.issued.peekFirst() >= this.windowMs) {
                    this.issued.pollFirst();
                }
                if (now < this.pausedUntil) {
                    wait = this.pausedUntil - now;
                } else if (this.issued.size() >= Math.max(1, this.limit.getAsInt())) {
                    wait = this.windowMs - (now - this.issued.peekFirst());
                } else if (now - this.lastIssued < this.minSpacingMs) {
                    wait = this.minSpacingMs - (now - this.lastIssued);
                } else {
                    this.issued.addLast(now);
                    this.lastIssued = now;
                    return;
                }
            }
            Thread.sleep(Math.max(1L, Math.min(wait, 5_000L)));
        }
    }

    public synchronized void backOff(long ms) {
        this.pausedUntil = Math.max(this.pausedUntil, System.currentTimeMillis() + ms);
    }

    public synchronized int used() {
        long now = System.currentTimeMillis();
        while (!this.issued.isEmpty() && now - this.issued.peekFirst() >= this.windowMs) {
            this.issued.pollFirst();
        }
        return this.issued.size();
    }

    public synchronized boolean isPaused() {
        return System.currentTimeMillis() < this.pausedUntil;
    }
}
