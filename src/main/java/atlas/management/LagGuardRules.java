package atlas.management;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * When a delay module may hold packets at all (2026-10-28, owner: "after this
 * no lag module may ever cause a lagback"). LagGuard asks this; plain state
 * and arithmetic in client ticks, tested on its own.
 *
 * From the owner's flag logs (49 files, 803 corrections): the corrections a
 * delay module was blamed for came at three moments --
 *  - right after this player's own knockback, mostly in the air (KnockbackDelay
 *    30 of 42, Stasis 10 of 16, Rhythm-Lag 2 of 3);
 *  - while digging or placing (FakeLag 30 of 64, LagRange 10 of 13);
 *  - long holds let out at once (FakeLag 26 "exceeds travel").
 * So:
 *  - the knockback window: from our own knockback until we have been back on
 *    the ground two ticks and at least eight have passed (forty at most),
 *    nothing is held, the knockback itself included -- except by a whole
 *    stream (below);
 *  - the block window: three ticks after our own dig or placement, nothing
 *    outgoing is held (and what was held went out ahead of it);
 *  - the setback pause: after a correction, three seconds without any hold;
 *    the corrections that follow within the pause are one setback with it
 *    (2026-10-29: a train of seven struck the same hold four times). A
 *    guarded outgoing holder that held in the half second before it, or an
 *    incoming one holding at that moment, takes a strike (Backtrack's brief
 *    holds are in most corrections without causing them).
 *    One strike caps its holds at half the longest it held then; two, and it
 *    holds nothing more in this world;
 *  - a ceiling on any guarded hold (DEFAULT_CAP_MS), so none grows long.
 * A teleport (far from where we are, or in the first seconds of a world) is
 * no correction: no pause and no strikes (2026-10-29: 281 position packets
 * in the 10-28 games, 9 of them lagbacks).
 * A whole stream (2026-10-29): an incoming holder that holds everything that
 * arrives, in order, transaction and keep-alive replies included (KnockbackDelay
 * Steady, or Spike with HoldOthers) looks to the server like a slow connection:
 * the knockback is late because everything is, and every reply says so. It is
 * not stopped in the knockback window, nor let go when a knockback arrives:
 * cutting it there is what made the 10-28 lagbacks -- its delay fell from
 * 125 ms to nothing at the push, the one thing a velocity check looks for. It
 * asks streamAllowance instead and ramps itself down: nothing during the
 * setback pause or once struck out, else its cap. It is struck only for a
 * correction within KB_RECENT_TICKS of our knockback (it holds through every
 * correction in a fight; the others are not its doing).
 * Fall-safety holds (AntiVoid, NoFall, Scaffold) and the player's own Blink
 * are not guarded: they are not delays for an edge, and ending them early is
 * what would drop the player.
 */
public final class LagGuardRules {
    public static final int KB_MIN_TICKS = 8;
    public static final int KB_MAX_TICKS = 40;
    public static final int KB_GROUND_TICKS = 2;
    public static final int BLOCK_TICKS = 3;
    public static final int SETBACK_TICKS = 60;
    public static final int RECENT_TICKS = 10;
    public static final long DEFAULT_CAP_MS = 400L;
    public static final long MIN_CAP_MS = 50L;
    /** A position packet this far from us, in blocks, is a teleport. */
    public static final double TELEPORT_BLOCKS = 8.0;
    /** Ticks into a world in which every position packet is a teleport (spawn, round start). */
    public static final int JOIN_TICKS = 40;
    /** A whole stream is struck for a correction this soon after our knockback. */
    public static final int KB_RECENT_TICKS = 20;

    private static final Set<String> UNGUARDED = new HashSet<String>();

    static {
        UNGUARDED.add("AntiVoid");
        UNGUARDED.add("NoFall");
        UNGUARDED.add("Scaffold");
        UNGUARDED.add("Blink");
    }

    private int grounded;
    private boolean kbActive;
    private int kbAt = Integer.MIN_VALUE / 2;
    private int worldTicks;
    private int blockUntil = Integer.MIN_VALUE / 2;
    private int setbackUntil = Integer.MIN_VALUE / 2;
    private final Map<String, Integer> strikes = new HashMap<String, Integer>();
    private final Map<String, Long> caps = new HashMap<String, Long>();
    private final Map<String, Integer> lastHeldAt = new HashMap<String, Integer>();
    private final Map<String, Long> longestRecent = new HashMap<String, Long>();
    private final Set<String> streams = new HashSet<String>();

    public static boolean guarded(String holder) {
        return holder != null && !UNGUARDED.contains(holder);
    }

    /** Once a tick: ground state, and the knockback window's end. */
    public synchronized void tick(int tick, boolean onGround) {
        this.grounded = onGround ? this.grounded + 1 : 0;
        this.worldTicks++;
        if (this.kbActive) {
            int since = tick - this.kbAt;
            if (since >= KB_MIN_TICKS && this.grounded >= KB_GROUND_TICKS || since > KB_MAX_TICKS) {
                this.kbActive = false;
            }
        }
    }

    /** Our own knockback arrived. */
    public synchronized void knockback(int tick) {
        this.kbActive = true;
        this.kbAt = tick;
    }

    /** {@code holder} holds the whole incoming stream in order now (or no longer). */
    public synchronized void stream(String holder, boolean whole) {
        if (whole) {
            this.streams.add(holder);
        } else {
            this.streams.remove(holder);
        }
    }

    public synchronized boolean isStream(String holder) {
        return this.streams.contains(holder);
    }

    /** How late a whole stream may run now, ms: none in the pause or struck out, else its cap. */
    public synchronized long streamAllowance(int tick, String holder) {
        if (!guarded(holder)) {
            return Long.MAX_VALUE;
        }
        if (strikes(holder) >= 2 || tick < this.setbackUntil) {
            return 0L;
        }
        return capMs(holder);
    }

    /** A position packet {@code distance} blocks from where we are: a teleport, not a correction. */
    public synchronized boolean teleport(double distance) {
        return distance > TELEPORT_BLOCKS || this.worldTicks < JOIN_TICKS;
    }

    public synchronized boolean knockbackWindow() {
        return this.kbActive;
    }

    /** Our own dig or placement went out. */
    public synchronized void block(int tick) {
        this.blockUntil = tick + BLOCK_TICKS;
    }

    /** An outgoing hold by {@code holder}, {@code ageMs} old, was seen this tick. */
    public synchronized void held(int tick, String holder, long ageMs) {
        if (!guarded(holder)) {
            return;
        }
        Integer last = this.lastHeldAt.get(holder);
        if (last == null || tick - last > RECENT_TICKS) {
            this.longestRecent.put(holder, ageMs);
        } else {
            Long longest = this.longestRecent.get(holder);
            this.longestRecent.put(holder, longest == null ? ageMs : Math.max(longest, ageMs));
        }
        this.lastHeldAt.put(holder, tick);
        this.outgoing.add(holder);
    }

    private final Set<String> outgoing = new HashSet<String>();

    /**
     * A correction arrived. Starts the pause and strikes every guarded
     * outgoing holder that held within RECENT_TICKS and every guarded incoming
     * holder in {@code incomingNow} (holding at this moment, with its hold's
     * age). Returns the ones struck.
     */
    public synchronized Collection<String> setback(int tick, Map<String, Long> incomingNow) {
        boolean sameTrain = tick < this.setbackUntil;
        this.setbackUntil = tick + SETBACK_TICKS;
        Set<String> struck = new HashSet<String>();
        if (sameTrain) {
            /* Within the pause nothing has held since the first: the same setback, struck once. */
            return struck;
        }
        if (incomingNow != null) {
            for (Map.Entry<String, Long> entry : incomingNow.entrySet()) {
                if (guarded(entry.getKey())) {
                    this.lastHeldAt.put(entry.getKey(), tick);
                    Long longest = this.longestRecent.get(entry.getKey());
                    this.longestRecent.put(entry.getKey(), longest == null ? entry.getValue() : Math.max(longest, entry.getValue()));
                }
            }
        }
        for (Map.Entry<String, Integer> entry : this.lastHeldAt.entrySet()) {
            boolean incomingHolder = incomingNow != null && incomingNow.containsKey(entry.getKey());
            if (incomingHolder && this.streams.contains(entry.getKey()) && !this.kbActive
                    && tick - this.kbAt > KB_RECENT_TICKS) {
                continue;
            }
            if (tick - entry.getValue() <= RECENT_TICKS && (incomingHolder || this.outgoing.contains(entry.getKey()))) {
                String holder = entry.getKey();
                struck.add(holder);
                Integer n = this.strikes.get(holder);
                this.strikes.put(holder, n == null ? 1 : n + 1);
                Long longest = this.longestRecent.get(holder);
                long half = Math.max(MIN_CAP_MS, (longest == null ? DEFAULT_CAP_MS : longest) / 2L);
                Long cap = this.caps.get(holder);
                this.caps.put(holder, cap == null ? Math.min(half, DEFAULT_CAP_MS) : Math.min(cap, half));
            }
        }
        return struck;
    }

    public synchronized int strikes(String holder) {
        Integer n = this.strikes.get(holder);
        return n == null ? 0 : n;
    }

    /** The longest {@code holder} may hold, ms. */
    public synchronized long capMs(String holder) {
        Long cap = this.caps.get(holder);
        return cap == null ? DEFAULT_CAP_MS : cap;
    }

    /** Whether {@code holder} may start or go on holding now: null, or why not. */
    public synchronized String deny(int tick, String holder, boolean incoming) {
        if (!guarded(holder)) {
            return null;
        }
        if (strikes(holder) >= 2) {
            return "struck out";
        }
        if (incoming && this.streams.contains(holder)) {
            /* A slow connection: through windows and the pause, ramping itself down by streamAllowance. */
            return null;
        }
        if (tick < this.setbackUntil) {
            return "after a correction";
        }
        if (this.kbActive) {
            return "knockback";
        }
        if (!incoming && tick < this.blockUntil) {
            return "dig/place";
        }
        return null;
    }

    /** A new world: everything starts over. */
    public synchronized void reset() {
        this.grounded = 0;
        this.kbActive = false;
        this.kbAt = Integer.MIN_VALUE / 2;
        this.worldTicks = 0;
        this.streams.clear();
        this.blockUntil = Integer.MIN_VALUE / 2;
        this.setbackUntil = Integer.MIN_VALUE / 2;
        this.strikes.clear();
        this.caps.clear();
        this.lastHeldAt.clear();
        this.longestRecent.clear();
        this.outgoing.clear();
    }
}
