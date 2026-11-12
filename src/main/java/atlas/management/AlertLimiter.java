package atlas.management;

import java.util.HashMap;
import java.util.Map;

/**
 * At most one line per cause in a stretch of time, with a count of the ones held back since (2026-11-12, taken out of
 * MoveCheck's chat lines): a cause that repeats every tick says so once, then "+N more" the next time it may speak.
 * One thread.
 */
final class AlertLimiter {
    private final long everyMs;
    /** Cause -> {when it last spoke, how many were held back since}. */
    private final Map<String, long[]> causes = new HashMap<String, long[]>();

    AlertLimiter(long everyMs) {
        this.everyMs = everyMs;
    }

    /**
     * Asks to speak for {@code cause} at {@code now} (milliseconds). -1: it spoke less than the stretch ago, and this
     * one is counted. Otherwise it may speak, and the number is how many were held back since it last did.
     */
    int pass(String cause, long now) {
        long[] state = this.causes.get(cause);
        if (state == null) {
            this.causes.put(cause, new long[]{now, 0L});
            return 0;
        }
        if (now - state[0] < this.everyMs) {
            state[1]++;
            return -1;
        }
        int held = (int) Math.min(Integer.MAX_VALUE, state[1]);
        state[0] = now;
        state[1] = 0L;
        return held;
    }

    void clear() {
        this.causes.clear();
    }
}
