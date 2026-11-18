package atlas.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BlockTrace {
    private static final long KEEP_MS = 3000L;

    private static final class Sample {
        final long at;
        final boolean blocking;

        Sample(long at, boolean blocking) {
            this.at = at;
            this.blocking = blocking;
        }
    }

    private final Map<Integer, ArrayDeque<Sample>> samples = new HashMap<Integer, ArrayDeque<Sample>>();

    public void record(int id, long now, boolean blocking) {
        ArrayDeque<Sample> list = this.samples.get(id);
        if (list == null) {
            list = new ArrayDeque<Sample>();
            this.samples.put(id, list);
        }
        list.addLast(new Sample(now, blocking));
        while (!list.isEmpty() && now - list.peekFirst().at > KEEP_MS) {
            list.removeFirst();
        }
    }

    public int[] count(int id, long from, long to) {
        int blocked = 0;
        int ticks = 0;
        ArrayDeque<Sample> list = this.samples.get(id);
        if (list != null) {
            for (Iterator<Sample> it = list.iterator(); it.hasNext(); ) {
                Sample s = it.next();
                if (s.at >= from && s.at <= to) {
                    ticks++;
                    if (s.blocking) {
                        blocked++;
                    }
                }
            }
        }
        return new int[]{blocked, ticks};
    }

    public String atHit(int id, long at, long slackMs) {
        int[] c = count(id, at - slackMs, at + slackMs);
        return c[1] == 0 ? "-" : c[0] > 0 ? "yes" : "no";
    }

    public void reset() {
        this.samples.clear();
    }
}
