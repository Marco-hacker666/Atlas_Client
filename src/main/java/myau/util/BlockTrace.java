package myau.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Whether another player was blocking with a sword, tick by tick, for the
 * damage log (2026-10-16).
 *
 * On Pika, hits read about half (2.21 against 4.04) in the one-sided combos
 * we win, with old KillAura and KillAura2 alike, and 2.21 is what a hit on a
 * sword-blocking 1.8 player does with armour 7 (2.16). The HIT-DMG line only
 * had the block state 24 ticks after the hit, when the window settled. The
 * other player's use flag and the flinch both come from the server, so the
 * state around the tick the flinch is seen is the state the server hit.
 */
public final class BlockTrace {
    /** History kept per player. */
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

    /** One tick of a player's state. */
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

    /**
     * Ticks recorded in [from, to] and how many of them showed a block:
     * {blocked, ticks}.
     */
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

    /** "yes", "no" or "-" (nothing recorded): a block within slackMs of the flinch. */
    public String atHit(int id, long at, long slackMs) {
        int[] c = count(id, at - slackMs, at + slackMs);
        return c[1] == 0 ? "-" : c[0] > 0 ? "yes" : "no";
    }

    public void reset() {
        this.samples.clear();
    }
}
