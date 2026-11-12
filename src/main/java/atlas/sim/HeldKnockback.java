package atlas.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Knockback packets a module kept from the game on their way in (2026-11-12, owner: GrimAC flagged nothing while the
 * self check reported every hit). KnockbackDelay cancels the packet and hands it to the game itself some hundreds of
 * milliseconds later, past the two moves {@link Impulses} offers a knockback to, so the move that really took it had
 * none to explain it. A held knockback waits here and goes to Impulses when the game applies it (setVelocity); one
 * never applied (a module dropped it) comes back from {@link #expired} once it is too old to be a delay.
 *
 * Any thread: packets arrive on the network thread, the game applies them on the client thread.
 */
public final class HeldKnockback {

    /** A knockback held this long is taken as dropped. */
    public static final long DROPPED_AFTER_MS = 1500L;
    /** setVelocity gets the packet's values divided by 8000: equal to well within this. */
    private static final double SAME = 1.0E-6;
    private static final int MAX_HELD = 8;

    /** One held knockback. */
    public static final class Held {
        public final double x;
        public final double y;
        public final double z;
        /** The module that cancelled the packet; null when not known. */
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

    /** A knockback packet for this player was cancelled on its way in. */
    public synchronized void held(double x, double y, double z, String by, long now) {
        if (this.held.size() >= MAX_HELD) {
            this.held.remove(0);
        }
        this.held.add(new Held(x, y, z, by, now));
    }

    /** The game set this motion (setVelocity): the held knockback it is, oldest first, taken off the list; else null. */
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

    /** The held knockbacks too old to be a delay, taken off the list. */
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
