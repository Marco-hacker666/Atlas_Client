package myau.management;

import myau.event.types.EventType;
import myau.events.PacketEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * The one door every module goes through to hold an outgoing packet back
 * (2026-10-18, plan item 4, owner: "every module that touches packets under
 * one central system, with priorities, so they stop fighting").
 *
 * What was wrong, found while listing how each holder holds and lets go:
 *   - The event bus keeps calling listeners after one has cancelled a packet.
 *     FakeLag and NoSlow's NG Blink both hold movement by cancelling the event
 *     and keeping the packet; neither looked whether it was already cancelled.
 *     With both on, one movement packet could be kept by both and later sent
 *     twice -- extra movement packets, which is what timer and simulation
 *     checks pull a player back for.
 *   - Each kept its own queue and let it go on its own (sendPacketNoEvent,
 *     which goes past every other listener). With two queues holding at once,
 *     the newer one could reach the server before the older one: movement out
 *     of order, a lagback.
 *
 * The rules, in take():
 *   1. A packet already cancelled is someone else's: never taken twice.
 *   2. One holder at a time. While another registered holder has packets
 *      held, a holder of the same or lower priority does not start holding --
 *      the packet goes on, to the holder that has the queue or out behind it.
 *      A holder of higher priority makes the lower one let go (in order,
 *      ahead of this packet, which is at the head of its send) and takes over.
 *   3. Arbiter's catch (Clutch, AntiVoid) and the never-held replies stay
 *      with the holders' own code, which already obeys them.
 * Incoming (stage 3): the same two rules for packets from the server, in a
 * lane of their own. A packet two holders kept was processed twice when let
 * go -- one knockback pushed twice, an entity moved twice -- and two incoming
 * queues let go on their own reordered what the client handled (and so what
 * its transaction replies acknowledged). Incoming holders are all equal: the
 * first to hold keeps the lane until its queue is empty (no take-overs, since
 * a take-over would have to process packets on the network thread).
 * BlinkManager and LagManager hold inside the send mixin, after every
 * listener, and what a holder lets go passes through them, so whatever they
 * hold stays in order with it: they are not holders here.
 *
 * Refusals are counted and kept for the flag log, by module and reason.
 */
public final class PacketHub {

    /** Delaying movement: FakeLag, NG Blink. */
    public static final int MOVEMENT = 2;
    /** Holding for a fight action (a held hit, a block). */
    public static final int COMBAT = 3;

    private static final class Holder {
        final String name;
        final int priority;
        final IntSupplier held;
        final Runnable letGo;
        /** Holds movement or what is ordered with it: one such holder at a time. */
        final boolean exclusive;
        /** Holds packets from the server (its own lane). */
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

    /**
     * A module that holds outgoing packets: how many it holds now, and how to
     * make it let them all go, in order, at once.
     */
    public static void register(String name, int priority, IntSupplier held, Runnable letGo) {
        HOLDERS.put(name, new Holder(name, priority, held, letGo, true, false));
    }

    /** A module that holds packets from the server: one such holder at a time, first come keeps the lane. */
    public static void registerIncoming(String name, IntSupplier held) {
        HOLDERS.put(name, new Holder(name, MOVEMENT, held, null, true, true));
    }

    /**
     * A module that holds packets of its own kind on its own schedule -- replies
     * (PingSpoof, Disabler), inventory clicks (InvWalk) -- whose lateness is the
     * point. Rule 1 only: it never takes what another has taken, and a movement
     * holder is not kept from holding by it.
     */
    public static void registerSide(String name, IntSupplier held) {
        HOLDERS.put(name, new Holder(name, 0, held, null, false, false));
    }

    /**
     * Asks to hold this outgoing packet. True: the event is now cancelled and
     * the caller keeps the packet. False: the caller must neither keep nor
     * cancel it.
     */
    public static boolean take(PacketEvent event, String holder) {
        boolean incoming = event.getType() == EventType.RECEIVE;
        if (!incoming && event.getType() != EventType.SEND) {
            return false;
        }
        if (myau.module.modules.PingFix.exempt(event.getPacket())) {
            /* PingFix: keep-alives go straight through, so the ping shown is the real one. */
            return false;
        }
        if (event.isCancelled()) {
            alreadyTaken++;
            last = holder + ": already taken";
            lastAt = System.currentTimeMillis();
            return false;
        }
        Holder self = HOLDERS.get(holder);
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

    /** Which movement holder holds outgoing packets now, or null. */
    public static String holding() {
        return holding(false);
    }

    /** Which holder holds packets from the server now, or null. */
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

    /** "taken 3 · busy 12 · took over 0 · last FakeLag: NoSlow is holding" */
    public static String summary() {
        return "taken " + alreadyTaken + " · busy " + busy + " · took over " + tookOver
                + (last.isEmpty() ? "" : " · last " + last);
    }

    /** The last refusal or take-over, if within ms; else null. For the flag log. */
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
