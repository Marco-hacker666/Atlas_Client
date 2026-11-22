package atlas.management;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class LagGuardRules {
    public static final int KB_MIN_TICKS = 8;
    public static final int KB_MAX_TICKS = 40;
    public static final int KB_GROUND_TICKS = 2;
    public static final int BLOCK_TICKS = 3;
    public static final int SETBACK_TICKS = 60;
    public static final int RECENT_TICKS = 10;
    public static final long DEFAULT_CAP_MS = 400L;
    public static final long MIN_CAP_MS = 50L;

    public static final double TELEPORT_BLOCKS = 8.0;

    public static final int JOIN_TICKS = 40;

    public static final int KB_RECENT_TICKS = 20;

    private static final Set<String> UNGUARDED = new HashSet<String>();
    private static final Set<String> KNOCKBACK_TOLERANT = new HashSet<String>();

    static {
        UNGUARDED.add("AntiVoid");
        UNGUARDED.add("NoFall");
        UNGUARDED.add("Scaffold");
        UNGUARDED.add("Blink");
        KNOCKBACK_TOLERANT.add("InvulnBlink");
    }

    public static boolean knockbackTolerant(String holder) {
        return holder != null && KNOCKBACK_TOLERANT.contains(holder);
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

    public synchronized void knockback(int tick) {
        this.kbActive = true;
        this.kbAt = tick;
    }

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

    public synchronized long streamAllowance(int tick, String holder) {
        if (!guarded(holder)) {
            return Long.MAX_VALUE;
        }
        if (strikes(holder) >= 2 || tick < this.setbackUntil) {
            return 0L;
        }
        return capMs(holder);
    }

    public synchronized boolean teleport(double distance) {
        return distance > TELEPORT_BLOCKS || this.worldTicks < JOIN_TICKS;
    }

    public synchronized boolean knockbackWindow() {
        return this.kbActive;
    }

    public synchronized void block(int tick) {
        this.blockUntil = tick + BLOCK_TICKS;
    }

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

    public synchronized Collection<String> setback(int tick, Map<String, Long> incomingNow) {
        boolean sameTrain = tick < this.setbackUntil;
        this.setbackUntil = tick + SETBACK_TICKS;
        Set<String> struck = new HashSet<String>();
        if (sameTrain) {
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

    public synchronized long capMs(String holder) {
        Long cap = this.caps.get(holder);
        return cap == null ? DEFAULT_CAP_MS : cap;
    }

    public synchronized String deny(int tick, String holder, boolean incoming) {
        if (!guarded(holder)) {
            return null;
        }
        if (strikes(holder) >= 2) {
            return "struck out";
        }
        if (incoming && this.streams.contains(holder)) {
            return null;
        }
        if (tick < this.setbackUntil) {
            return "after a correction";
        }
        if (this.kbActive && !knockbackTolerant(holder)) {
            return "knockback";
        }
        if (!incoming && tick < this.blockUntil) {
            return "dig/place";
        }
        return null;
    }

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
