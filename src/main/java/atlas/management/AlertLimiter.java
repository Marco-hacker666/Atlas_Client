package atlas.management;

import java.util.HashMap;
import java.util.Map;

final class AlertLimiter {
    private final long everyMs;

    private final Map<String, long[]> causes = new HashMap<String, long[]>();

    AlertLimiter(long everyMs) {
        this.everyMs = everyMs;
    }

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
