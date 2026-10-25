package atlas.humanize;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Time before reacting to a target (2026-10-25). A target never seen, or not
 * for reacquireWindow, costs a new-target reaction; one seen again within it
 * a (shorter) reacquire; changing from one target to another a switch. Until
 * the time is up the caller does not aim or click. Targets are entity ids.
 */
public final class Reaction {
    public static final int KIND_NONE = 0;
    public static final int KIND_NEW = 1;
    public static final int KIND_REACQUIRE = 2;
    public static final int KIND_SWITCH = 3;

    private final Random random;
    private int current = Integer.MIN_VALUE;
    private long readyAt;
    private long lastDrawn;
    private int lastKind = KIND_NONE;
    private final Map<Integer, Long> lostAt = new HashMap<Integer, Long>();

    public Reaction(Random random) {
        this.random = random;
    }

    public void reset() {
        this.current = Integer.MIN_VALUE;
        this.lostAt.clear();
    }

    /** One draw in [min, max] by the profile's shape. */
    public static long draw(int min, int max, int shape, Random random) {
        int low = Math.max(0, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        double span = high - low;
        double value;
        switch (shape) {
            case HumanProfile.SHAPE_UNIFORM:
                value = low + random.nextDouble() * span;
                break;
            case HumanProfile.SHAPE_LOG_NORMAL: {
                double median = (low + high) / 2.0;
                value = Math.max(1.0, median) * Math.exp(0.25 * random.nextGaussian());
                break;
            }
            default:
                value = low + (random.nextDouble() + random.nextDouble()) / 2.0 * span;
        }
        return Math.round(Math.max(low, Math.min(high, value)));
    }

    /**
     * Whether the reaction to {@code target} (an entity id; Integer.MIN_VALUE
     * for none) is over at {@code now}.
     */
    public boolean ready(int target, long now, HumanProfile p) {
        if (target == Integer.MIN_VALUE) {
            if (this.current != Integer.MIN_VALUE) {
                this.lostAt.put(this.current, now);
                this.current = Integer.MIN_VALUE;
            }
            return false;
        }
        if (target != this.current) {
            Long lost = this.lostAt.get(target);
            int kind;
            if (this.current != Integer.MIN_VALUE) {
                this.lostAt.put(this.current, now);
                kind = KIND_SWITCH;
            } else if (lost != null && now - lost <= p.reacquireWindow) {
                kind = KIND_REACQUIRE;
            } else {
                kind = KIND_NEW;
            }
            long delay = kind == KIND_SWITCH ? draw(p.reactionSwitchMin, p.reactionSwitchMax, p.reactionShape, this.random)
                    : kind == KIND_REACQUIRE ? draw(p.reactionReacquireMin, p.reactionReacquireMax, p.reactionShape, this.random)
                    : draw(p.reactionNewMin, p.reactionNewMax, p.reactionShape, this.random);
            this.current = target;
            this.readyAt = now + delay;
            this.lastDrawn = delay;
            this.lastKind = kind;
            prune(now, p.reacquireWindow);
        }
        return now >= this.readyAt;
    }

    private void prune(long now, int window) {
        for (Iterator<Map.Entry<Integer, Long>> it = this.lostAt.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > Math.max(window, 1000) * 2L) {
                it.remove();
            }
        }
    }

    public long lastDrawn() {
        return this.lastDrawn;
    }

    public int lastKind() {
        return this.lastKind;
    }
}
