package atlas.humanize;

import java.util.ArrayDeque;
import java.util.Locale;

public final class HumanStats {
    private static final long SPAN_MS = 30000L;

    private static final int CLICK_HIT = 0;
    private static final int CLICK_AIM_OFF = 1;
    private static final int CLICK_SWING_ONLY = 2;
    private static final int CLICK_SKIP = 3;

    private static final int CLICK_UNLUCKY = 4;

    private final ArrayDeque<long[]> clicks = new ArrayDeque<long[]>();
    private final ArrayDeque<long[]> reactions = new ArrayDeque<long[]>();

    private static final int AIM_OVER = 0;
    private static final int AIM_UNDER = 1;
    private static final int AIM_LAPSE = 2;
    private final ArrayDeque<long[]> aims = new ArrayDeque<long[]>();

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
        aim(adjust, System.currentTimeMillis());
    }

    public synchronized void aim(AimError.Adjust adjust, long now) {
        if (adjust.overshot) {
            this.aims.addLast(new long[]{now, AIM_OVER});
        }
        if (adjust.undershot) {
            this.aims.addLast(new long[]{now, AIM_UNDER});
        }
        trim(now);
    }

    public synchronized void lapse() {
        lapse(System.currentTimeMillis());
    }

    public synchronized void lapse(long now) {
        this.aims.addLast(new long[]{now, AIM_LAPSE});
        trim(now);
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
        while (!this.aims.isEmpty() && now - this.aims.peekFirst()[0] > SPAN_MS) {
            this.aims.pollFirst();
        }
    }

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
        int[] a = new int[3];
        for (long[] e : this.aims) {
            a[(int) e[1]]++;
        }
        return String.format(Locale.ROOT, "hit %s (%d/%d) | miss aim %d swing %d skip %d unlucky %d | react %s | over %d under %d lapse %d",
                clicked == 0 ? "-" : Math.round(100.0 * n[0] / clicked) + "%", n[0], clicked, n[1], n[2], n[3], n[4],
                react, a[AIM_OVER], a[AIM_UNDER], a[AIM_LAPSE]);
    }
}
