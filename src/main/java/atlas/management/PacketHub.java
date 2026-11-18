package atlas.management;

import atlas.event.types.EventType;
import atlas.events.PacketEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

public final class PacketHub {
    public static final int MOVEMENT = 2;

    public static final int COMBAT = 3;

    private static final class Holder {
        final String name;
        final int priority;
        final IntSupplier held;
        final Runnable letGo;

        final boolean exclusive;

        final boolean incoming;

        Holder(String name, int priority, IntSupplier held, Runnable letGo, boolean exclusive, boolean incoming) {
            this.name = name;
            this.priority = priority;
            this.held = held;
            this.letGo = letGo;
            this.exclusive = exclusive;
            this.incoming = incoming;
        }
    }

    private static final Map<String, Holder> HOLDERS = new ConcurrentHashMap<String, Holder>();
    private static volatile int alreadyTaken;
    private static volatile int busy;
    private static volatile int tookOver;
    private static volatile String last = "";
    private static volatile long lastAt;

    private PacketHub() {
    }

    public static void register(String name, int priority, IntSupplier held, Runnable letGo) {
        HOLDERS.put(name, new Holder(name, priority, held, letGo, true, false));
    }

    public static void registerIncoming(String name, IntSupplier held) {
        HOLDERS.put(name, new Holder(name, MOVEMENT, held, null, true, true));
    }

    public static void registerIncoming(String name, IntSupplier held, Runnable letGo) {
        HOLDERS.put(name, new Holder(name, MOVEMENT, held, letGo, true, true));
    }

    public static int letGoGuarded(boolean outgoing, boolean incoming) {
        return letGoGuarded(outgoing, incoming, false);
    }

    public static int letGoGuarded(boolean outgoing, boolean incoming, boolean keepStreams) {
        int n = 0;
        for (Holder holder : HOLDERS.values()) {
            if (!holder.exclusive || holder.letGo == null || !LagGuardRules.guarded(holder.name)
                    || (holder.incoming ? !incoming : !outgoing) || held(holder) == 0) {
                continue;
            }
            if (keepStreams && holder.incoming && LagGuard.isWholeStream(holder.name)) {
                continue;
            }
            try {
                holder.letGo.run();
                n++;
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        return n;
    }

    public static void registerSide(String name, IntSupplier held) {
        HOLDERS.put(name, new Holder(name, 0, held, null, false, false));
    }

    public static boolean take(PacketEvent event, String holder) {
        boolean incoming = event.getType() == EventType.RECEIVE;
        if (!incoming && event.getType() != EventType.SEND) {
            return false;
        }
        if (atlas.module.modules.PingFix.exempt(event.getPacket())) {
            return false;
        }
        if (event.isCancelled()) {
            alreadyTaken++;
            last = holder + ": already taken";
            lastAt = System.currentTimeMillis();
            return false;
        }
        Holder self = HOLDERS.get(holder);
        if (self != null && self.exclusive && !LagGuard.mayHold(holder, incoming, event.getPacket())) {
            if (held(self) > 0 && self.letGo != null) {
                self.letGo.run();
            }
            last = holder + ": LagGuard";
            lastAt = System.currentTimeMillis();
            return false;
        }
        if (self == null || !self.exclusive || self.incoming != incoming) {
            event.setCancelled(true);
            return true;
        }
        int priority = self.priority;
        for (Holder other : HOLDERS.values()) {
            if (other == self || !other.exclusive || other.incoming != incoming || held(other) == 0) {
                continue;
            }
            if (other.priority >= priority || other.letGo == null) {
                busy++;
                last = holder + ": " + other.name + " is holding";
                lastAt = System.currentTimeMillis();
                return false;
            }
            other.letGo.run();
            tookOver++;
            last = holder + ": took over from " + other.name;
            lastAt = System.currentTimeMillis();
        }
        event.setCancelled(true);
        return true;
    }

    private static int held(Holder holder) {
        try {
            return holder.held.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static String holding() {
        return holding(false);
    }

    public static String holdingIncoming() {
        return holding(true);
    }

    private static String holding(boolean incoming) {
        for (Holder holder : HOLDERS.values()) {
            if (holder.exclusive && holder.incoming == incoming && held(holder) > 0) {
                return holder.name;
            }
        }
        return null;
    }

    public static String summary() {
        return "taken " + alreadyTaken + " · busy " + busy + " · took over " + tookOver
                + (last.isEmpty() ? "" : " · last " + last);
    }

    public static String recent(long ms) {
        return System.currentTimeMillis() - lastAt <= ms && !last.isEmpty() ? last : null;
    }

    static void clearForTest() {
        HOLDERS.clear();
        alreadyTaken = 0;
        busy = 0;
        tookOver = 0;
        last = "";
        lastAt = 0L;
    }
}
