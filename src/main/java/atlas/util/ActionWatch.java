package atlas.util;

import atlas.event.events.Event;
import atlas.events.JumpEvent;
import atlas.events.KnockbackEvent;
import atlas.events.LivingUpdateEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.events.StrafeEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.HashMap;
import java.util.Map;

/**
 * What a module did to the player without sending a packet (2026-10-11).
 *
 * The ledger used to see packets only: attacks, placements, digs, swings, holds.
 * A module that changes the player's motion, ground state, look or the game's
 * timer -- Speed, Fly, NoSlow, Velocity, Timer, KillAura's and Scaffold's turns
 * -- was never named when the server corrected the player, and FlagResponder
 * could not act on it. EventManager takes a snapshot before each module's
 * handler of the movement-path events and compares after: a change is that
 * module's doing, recorded as "move", "rotate" or "timer". Client thread only;
 * the same module and kind at most every 250 ms, so a module that turns every
 * tick does not push everything else out of the ledger.
 */
public final class ActionWatch {
    private static final double MOTION_EPS = 1.0E-4;
    private static final float ANGLE_EPS = 0.01F;
    private static final long DEDUPE_MS = 250L;
    private static final Map<String, Long> LAST = new HashMap<String, Long>();

    private static double mx, my, mz;
    private static boolean ground;
    private static float yaw, pitch, sentYaw, sentPitch, timer;
    private static boolean armed;

    private ActionWatch() {
    }

    /** Events on the movement path, where a module's handler can move or turn the player. */
    public static boolean watches(Event event) {
        return event instanceof UpdateEvent || event instanceof StrafeEvent || event instanceof PlayerUpdateEvent
                || event instanceof KnockbackEvent || event instanceof JumpEvent || event instanceof TickEvent
                || event instanceof LivingUpdateEvent;
    }

    public static void before(Event event) {
        armed = false;
        Minecraft mc = Minecraft.getMinecraft();
        /* No game (tests, a headless run) or another thread: nothing to watch. */
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        if (player == null || !mc.isCallingFromMinecraftThread()) {
            return;
        }
        mx = player.motionX;
        my = player.motionY;
        mz = player.motionZ;
        ground = player.onGround;
        yaw = player.rotationYaw;
        pitch = player.rotationPitch;
        if (event instanceof UpdateEvent) {
            sentYaw = ((UpdateEvent) event).getNewYaw();
            sentPitch = ((UpdateEvent) event).getNewPitch();
        }
        timer = timerSpeed();
        armed = true;
    }

    public static void after(Object source, Event event) {
        if (!armed || !(source instanceof Module)) {
            return;
        }
        armed = false;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        if (player == null) {
            return;
        }
        String name = ((Module) source).getName();
        if (Math.abs(player.motionX - mx) > MOTION_EPS || Math.abs(player.motionY - my) > MOTION_EPS
                || Math.abs(player.motionZ - mz) > MOTION_EPS || player.onGround != ground) {
            note(name, "move");
        }
        boolean turned = Math.abs(player.rotationYaw - yaw) > ANGLE_EPS || Math.abs(player.rotationPitch - pitch) > ANGLE_EPS;
        if (event instanceof UpdateEvent) {
            UpdateEvent update = (UpdateEvent) event;
            turned |= Math.abs(update.getNewYaw() - sentYaw) > ANGLE_EPS
                    || Math.abs(update.getNewPitch() - sentPitch) > ANGLE_EPS;
        }
        if (turned) {
            note(name, "rotate");
        }
        if (Math.abs(timerSpeed() - timer) > 1.0E-4F) {
            note(name, "timer");
        }
    }

    private static float timerSpeed() {
        try {
            return ((IAccessorMinecraft) Minecraft.getMinecraft()).getTimer().timerSpeed;
        } catch (Throwable t) {
            return 1.0F;
        }
    }

    private static void note(String module, String kind) {
        long now = System.currentTimeMillis();
        String key = module + '|' + kind;
        Long last = LAST.get(key);
        if (last != null && now - last < DEDUPE_MS) {
            return;
        }
        LAST.put(key, now);
        ActionLedger.note(module, kind);
    }
}
