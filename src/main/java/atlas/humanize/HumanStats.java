package atlas.humanize;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * What a humanized module did lately (2026-10-25): clicks, hits, misses by
 * style, reactions, overshoots and lapses over the last 30 seconds, for the
 * stats line and the log.
 */
public final class HumanStats {
    private static final long SPAN_MS = 30000L;

    private static final int CLICK_HIT = 0;
    private static final int CLICK_AIM_OFF = 1;
    private static final int CLICK_SWING_ONLY = 2;
    private static final int CLICK_SKIP = 3;
    /** A click meant to land whose ray missed anyway. */
    private static final int CLICK_UNLUCKY = 4;

    private final ArrayDeque<long[]> clicks = new ArrayDeque<long[]>();
    private final ArrayDeque<long[]> reactions = new ArrayDeque<long[]>();
    private int overshoots;
    private int undershoots;
    private int lapses;

    public synchronized void hit(long now) {
        add(CLICK_HIT, now);
    }

    public synchronized void miss(Misses.Style style, long now) {
        add(style == Misses.Style.AIM_OFF ? CLICK_AIM_OFF : style == Misses.Style.SWING_ONLY ? CLICK_SWING_ONLY : CLICK_SKIP, now);
    }

    public synchronized void unlucky(long now) {
        add(CLICK_UNLUCKY, now);
    }

    public synchronized void reaction(long ms, long now) {
        this.reactions.addLast(new long[]{now, ms});
        trim(now);
    }

    public synchronized void aim(AimError.Adjust adjust) {
        if (adjust.overshot) {
            this.overshoots++;
        }
        if (adjust.undershot) {
            this.undershoots++;
        }
    }

    public synchronized void lapse() {
        this.lapses++;
    }

    private void add(int kind, long now) {
        this.clicks.addLast(new long[]{now, kind});
        trim(now);
    }

    private void trim(long now) {
        while (!this.clicks.isEmpty() && now - this.clicks.peekFirst()[0] > SPAN_MS) {
            this.clicks.pollFirst();
        }
        while (!this.reactions.isEmpty() && now - this.reactions.peekFirst()[0] > SPAN_MS) {
            this.reactions.pollFirst();
        }
    }

    /** Hits / clicks over the last 30 s (-1 with none). Skipped clicks are not clicks. */
    public synchronized double hitRate(long now) {
        trim(now);
        int clicked = 0;
        int hits = 0;
        for (long[] c : this.clicks) {
            if (c[1] != CLICK_SKIP) {
                clicked++;
            }
            if (c[1] == CLICK_HIT) {
                hits++;
            }
        }
        return clicked == 0 ? -1.0 : hits / (double) clicked;
    }

    /** One line: "hit 41% (37/90) | miss aim 30 swing 9 skip 6 unlucky 2 | react 236ms | over 4 under 2 lapse 1". */
    public synchronized String line(long now) {
        trim(now);
        int[] n = new int[5];
        for (long[] c : this.clicks) {
            n[(int) c[1]]++;
        }
        int clicked = n[0] + n[1] + n[2] + n[4];
        long sum = 0;
        for (long[] r : this.reactions) {
            sum += r[1];
        }
        String react = this.reactions.isEmpty() ? "-" : (sum / this.reactions.size()) + "ms";
        return String.format(Locale.ROOT, "hit %s (%d/%d) | miss aim %d swing %d skip %d unlucky %d | react %s | over %d under %d lapse %d",
                clicked == 0 ? "-" : Math.round(100.0 * n[0] / clicked) + "%", n[0], clicked, n[1], n[2], n[3], n[4],
                react, this.overshoots, this.undershoots, this.lapses);
    }
}
