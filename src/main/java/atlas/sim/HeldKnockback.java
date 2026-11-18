package atlas.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class HeldKnockback {
    public static final long DROPPED_AFTER_MS = 1500L;

    private static final double SAME = 1.0E-6;
    private static final int MAX_HELD = 8;

    public static final class Held {
        public final double x;
        public final double y;
        public final double z;

        public final String by;
        public final long at;

        Held(double x, double y, double z, String by, long at) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.by = by;
            this.at = at;
        }
    }

    private final List<Held> held = new ArrayList<Held>();

    public synchronized void held(double x, double y, double z, String by, long now) {
        if (this.held.size() >= MAX_HELD) {
            this.held.remove(0);
        }
        this.held.add(new Held(x, y, z, by, now));
    }

    public synchronized Held applied(double x, double y, double z) {
        for (Iterator<Held> it = this.held.iterator(); it.hasNext(); ) {
            Held kb = it.next();
            if (Math.abs(kb.x - x) <= SAME && Math.abs(kb.y - y) <= SAME && Math.abs(kb.z - z) <= SAME) {
                it.remove();
                return kb;
            }
        }
        return null;
    }

    public synchronized List<Held> expired(long now) {
        List<Held> out = new ArrayList<Held>();
        for (Iterator<Held> it = this.held.iterator(); it.hasNext(); ) {
            Held kb = it.next();
            if (now - kb.at > DROPPED_AFTER_MS) {
                it.remove();
                out.add(kb);
            }
        }
        return out;
    }

    public synchronized void clear() {
        this.held.clear();
    }
}
