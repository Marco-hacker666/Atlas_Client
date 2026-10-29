package atlas.management;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.TickEvent;
import atlas.util.AsyncLog;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every delay module under one rule of when it may hold (2026-10-28, owner:
 * "after this no lag module may ever cause a lagback"). The rules are
 * LagGuardRules'; this is where they meet the game:
 *  - received, before anything else sees it (MixinNetworkManager): our own
 *    knockback lets every guarded hold go -- outgoing in order, incoming
 *    processed in order ahead of the push, a whole stream excepted -- and
 *    opens the knockback window;
 *    a teleport (an S08 far away or early in a world) only ends the holds;
 *    a correction (S08) drops what the blink and lag queues held (stale
 *    movement asks for another correction), lets incoming holders go ahead of
 *    it, starts the pause and strikes who held;
 *  - sent, before anything else sees it: our own dig or placement lets the
 *    outgoing holds go ahead of it and opens the block window;
 *  - the doors every holder goes through ask mayHold: PacketHub.take,
 *    BlinkManager.setBlinkState, LagManager.setDelay (and Stasis);
 *  - once a tick: the windows move on, and a guarded hold older than its
 *    ceiling is ended (PacketHolds lease).
 * Silent Aura's Rhythm-Lag hold is left to end itself at the next tick start
 * (its release must go out before the movement it held, see KillAuraLB).
 * Written to config/Atlas/lagguard-[stamp].txt.
 */
public final class LagGuard {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final LagGuardRules RULES = new LagGuardRules();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final String RHYTHM = BlinkModules.RHYTHM.moduleName();
    /** A whole stream's ramp down takes a few hundred ms; past its cap by more, it is ended. */
    private static final long STREAM_SLACK_MS = 250L;

    private static volatile int tick;
    private static volatile int selfId = Integer.MIN_VALUE;
    private static volatile boolean windowWasOpen;
    private static File logFile;
    private static final Map<String, Integer> denials = new TreeMap<String, Integer>();
    private static int summaryAt;

    private LagGuard() {
    }

    // ---- the doors -------------------------------------------------------

    /**
     * Whether {@code holder} may hold {@code packet} (null: start holding at
     * all) now. Counted when refused.
     */
    public static boolean mayHold(String holder, boolean incoming, Packet<?> packet) {
        String why = RULES.deny(tick, holder, incoming);
        if (why == null) {
            return true;
        }
        synchronized (denials) {
            String key = holder + " (" + why + ")";
            Integer n = denials.get(key);
            denials.put(key, n == null ? 1 : n + 1);
        }
        return false;
    }

    /**
     * {@code holder} holds the whole incoming stream in order now -- replies
     * included, as a slow connection (see LagGuardRules). Said once a tick.
     */
    public static void wholeStream(String holder, boolean whole) {
        RULES.stream(holder, whole);
    }

    public static boolean isWholeStream(String holder) {
        return RULES.isStream(holder);
    }

    /** How late a whole stream may run now, ms; it ramps itself to this. */
    public static long streamAllowanceMs(String holder) {
        return RULES.streamAllowance(tick, holder);
    }

    /** A holder that drops rather than holds (Stasis) says so, for the strikes. */
    public static void noteHeld(String holder) {
        RULES.held(tick, holder, 0L);
    }

    // ---- received / sent -----------------------------------------------------

    /** Network thread, before the delay manager and every listener. */
    public static void onReceive(Packet<?> packet) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (packet instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook pos = (S08PacketPlayerPosLook) packet;
            double distance = distanceTo(pos);
            if (RULES.teleport(distance)) {
                int ended = endHolds();
                if (ended > 0) {
                    log(String.format("TELEPORT %.1f blocks: ended %d held, no pause", distance, ended));
                }
            } else {
                onCorrection();
            }
        } else if (isOwnKnockback(packet)) {
            onKnockback();
        }
    }

    /** Whichever thread sends, before every listener; held packets let out later do not come through here. */
    public static void onSend(Packet<?> packet) {
        if (mc.thePlayer == null) {
            return;
        }
        boolean dig = packet instanceof C07PacketPlayerDigging && isDig((C07PacketPlayerDigging) packet);
        boolean place = packet instanceof C08PacketPlayerBlockPlacement
                && ((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() != 255;
        if (!dig && !place) {
            return;
        }
        RULES.block(tick);
        int out = letGoOutgoing();
        if (out > 0) {
            log("BLOCK  " + (dig ? "dig" : "place") + ": " + out + " outgoing hold(s) let go ahead of it");
        }
    }

    private static boolean isDig(C07PacketPlayerDigging packet) {
        C07PacketPlayerDigging.Action action = packet.getStatus();
        return action == C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
                || action == C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK
                || action == C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK;
    }

    static boolean isOwnKnockback(Packet<?> packet) {
        if (packet instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) packet).getEntityID() == selfId;
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F;
        }
        return false;
    }

    /** Blocks from where we are to where {@code pos} puts us (its relative axes are offsets). */
    private static double distanceTo(S08PacketPlayerPosLook pos) {
        java.util.Set<S08PacketPlayerPosLook.EnumFlags> flags = pos.func_179834_f();
        boolean relX = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.X);
        boolean relY = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Y);
        boolean relZ = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Z);
        double dx = relX ? pos.getX() : pos.getX() - mc.thePlayer.posX;
        double dy = relY ? pos.getY() : pos.getY() - mc.thePlayer.posY;
        double dz = relZ ? pos.getZ() : pos.getZ() - mc.thePlayer.posZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void onKnockback() {
        RULES.knockback(tick);
        int out = letGoOutgoing();
        /* A whole stream goes on: the push is late because everything is. */
        int in = PacketHub.letGoGuarded(false, true, true);
        windowWasOpen = true;
        log("KB     window opens; let go: " + out + " outgoing, " + in + " incoming");
    }

    /**
     * Drops what the guarded blink and lag queues held (movement from before the
     * position packet asks for another one) and lets incoming holders go ahead of
     * it. Returns how many.
     */
    private static int endHolds() {
        int ended = 0;
        BlinkManager blink = Atlas.blinkManager;
        if (blink != null && blink.getBlinkingModule() != BlinkModules.NONE
                && LagGuardRules.guarded(blink.getBlinkingModule().moduleName())) {
            ended += blink.blinkedPackets.size();
            blink.abortForSetback();
        }
        LagManager lag = Atlas.lagManager;
        if (lag != null && !lag.packetQueue.isEmpty() && LagGuardRules.guarded(lag.holder())) {
            ended += lag.packetQueue.size();
            lag.dropForWorldChange(true);
            lag.setDelay(0);
        }
        return ended + PacketHub.letGoGuarded(false, true);
    }

    private static void onCorrection() {
        java.util.Map<String, Long> incomingNow = new java.util.HashMap<String, Long>();
        long now = System.currentTimeMillis();
        for (PacketHolds.Hold hold : PacketHolds.snapshot()) {
            if (hold.direction == PacketHolds.Direction.IN) {
                incomingNow.put(holder(hold.holder), hold.since > 0L ? now - hold.since : 0L);
            }
        }
        Collection<String> struck = RULES.setback(tick, incomingNow);
        int ended = endHolds();
        StringBuilder sb = new StringBuilder("SETBACK pause " + LagGuardRules.SETBACK_TICKS / 20 + "s; ended "
                + ended + " held");
        for (String holder : struck) {
            sb.append(" | strike ").append(holder).append(" #").append(RULES.strikes(holder))
                    .append(RULES.strikes(holder) >= 2 ? " (no more holding this world)" : ", cap " + RULES.capMs(holder) + "ms");
        }
        if (struck.isEmpty()) {
            sb.append(" | no strike (nothing guarded held, or the same train)");
        }
        log(sb.toString());
    }

    /** Every guarded outgoing hold, in order, now (Rhythm-Lag excepted: it ends itself). Returns how many. */
    private static int letGoOutgoing() {
        int n = PacketHub.letGoGuarded(true, false);
        BlinkManager blink = Atlas.blinkManager;
        if (blink != null) {
            BlinkModules owner = blink.getBlinkingModule();
            if (owner != BlinkModules.NONE && LagGuardRules.guarded(owner.moduleName())
                    && !RHYTHM.equals(owner.moduleName())) {
                blink.setBlinkState(false, owner);
                n++;
            }
        }
        LagManager lag = Atlas.lagManager;
        if (lag != null && !lag.packetQueue.isEmpty() && LagGuardRules.guarded(lag.holder())) {
            lag.releaseAll();
            n++;
        }
        return n;
    }

    // ---- the tick --------------------------------------------------------

    public static final class Listener {
        @EventTarget(Priority.LOWEST)
        public void onTick(TickEvent event) {
            if (event.getType() != EventType.POST || mc.thePlayer == null) {
                return;
            }
            tick++;
            selfId = mc.thePlayer.getEntityId();
            RULES.tick(tick, mc.thePlayer.onGround);
            if (windowWasOpen && !RULES.knockbackWindow()) {
                windowWasOpen = false;
                log("KB     window closes (back on the ground)");
            }
            long now = System.currentTimeMillis();
            for (PacketHolds.Hold hold : PacketHolds.snapshot()) {
                String holder = holder(hold.holder);
                long age = hold.since > 0L ? now - hold.since : 0L;
                if (hold.direction == PacketHolds.Direction.OUT) {
                    RULES.held(tick, holder, age);
                }
                /* Rhythm-Lag ends itself within its LagMax, release first; a forced end would send its movement while
                   the server still has the sword up. */
                /* A whole stream ramps down to its cap by itself; only one far past it is ended. */
                long cap = RULES.capMs(holder) + (RULES.isStream(holder) ? STREAM_SLACK_MS : 0L);
                if (LagGuardRules.guarded(holder) && !RHYTHM.equals(holder) && age > cap
                        && PacketHolds.expire(hold.holder)) {
                    log("CAP    " + holder + " held " + age + "ms, over its " + RULES.capMs(holder) + "ms: let go");
                }
            }
            if (tick - summaryAt >= 1200) {
                summary();
            }
        }

        @EventTarget(Priority.HIGHEST)
        public void onWorld(LoadWorldEvent event) {
            summary();
            RULES.reset();
            windowWasOpen = false;
        }
    }

    /** "Blink/NoSlow" -> "NoSlow": the module behind a hold's name. */
    static String holder(String name) {
        return name != null && name.startsWith("Blink/") ? name.substring(6) : name;
    }

    private static void summary() {
        summaryAt = tick;
        synchronized (denials) {
            if (denials.isEmpty()) {
                return;
            }
            log("HELD BACK " + denials);
            denials.clear();
        }
    }

    private static void log(String line) {
        if (logFile == null) {
            logFile = new File(LOG_DIR, "lagguard-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(logFile, LINE_STAMP.format(new Date()) + "  " + line);
    }
}
