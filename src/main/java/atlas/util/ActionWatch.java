package atlas.util;

import atlas.event.events.Event;
import atlas.events.JumpEvent;
import atlas.events.KnockbackEvent;
import atlas.events.LivingUpdateEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.events.PreMoveInputEvent;
import atlas.events.StrafeEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

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
 *
 * 2026-11-11, for MoveCheck's self check: also the keys (movementInput, and the key states at PreMoveInputEvent),
 * the sprint and the push a StrafeEvent carries, and every change of the tick kept by module and kind, with no
 * dedupe (tickMovers). The ledger sees only move, rotate and timer, as before.
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
    private static float inForward, inStrafe, pushStrafe, pushForward, pushFriction;
    private static boolean inJump, inSneak, sprinting;
    private static int keys;
    /** Module -> kinds of change, since the last takeTickMovers. Client thread only. */
    private static Map<String, TreeSet<String>> tickMovers = new TreeMap<String, TreeSet<String>>();
    /**
     * Run just before an UpdateEvent POST goes to any listener: the tick's move is made and its movement packet is
     * out, and no module has acted since (MoveCheck's end of the tick).
     */
    public static volatile java.util.function.Consumer<UpdateEvent> beforeUpdatePost;

    private ActionWatch() {
    }

    /** Events on the movement path, where a module's handler can move or turn the player. */
    public static boolean watches(Event event) {
        return event instanceof UpdateEvent || event instanceof StrafeEvent || event instanceof PlayerUpdateEvent
                || event instanceof KnockbackEvent || event instanceof JumpEvent || event instanceof TickEvent
                || event instanceof LivingUpdateEvent || event instanceof MoveInputEvent
                || event instanceof PreMoveInputEvent;
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
        sprinting = player.isSprinting();
        if (player.movementInput != null) {
            inForward = player.movementInput.moveForward;
            inStrafe = player.movementInput.moveStrafe;
            inJump = player.movementInput.jump;
            inSneak = player.movementInput.sneak;
        }
        if (event instanceof PreMoveInputEvent) {
            keys = keyStates(mc);
        }
        if (event instanceof StrafeEvent) {
            StrafeEvent strafe = (StrafeEvent) event;
            pushStrafe = strafe.getStrafe();
            pushForward = strafe.getForward();
            pushFriction = strafe.getFriction();
        }
        armed = true;
    }

    private static int keyStates(Minecraft mc) {
        if (mc.gameSettings == null) {
            return 0;
        }
        return (mc.gameSettings.keyBindForward.isKeyDown() ? 1 : 0) | (mc.gameSettings.keyBindBack.isKeyDown() ? 2 : 0)
                | (mc.gameSettings.keyBindLeft.isKeyDown() ? 4 : 0) | (mc.gameSettings.keyBindRight.isKeyDown() ? 8 : 0)
                | (mc.gameSettings.keyBindJump.isKeyDown() ? 16 : 0) | (mc.gameSettings.keyBindSneak.isKeyDown() ? 32 : 0)
                | (mc.gameSettings.keyBindSprint.isKeyDown() ? 64 : 0);
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
            tick(name, "move");
        }
        if (player.isSprinting() != sprinting) {
            tick(name, "sprint");
        }
        if (player.movementInput != null && (player.movementInput.moveForward != inForward
                || player.movementInput.moveStrafe != inStrafe || player.movementInput.jump != inJump
                || player.movementInput.sneak != inSneak)) {
            tick(name, "input");
        }
        if (event instanceof PreMoveInputEvent && keyStates(mc) != keys) {
            tick(name, "input");
        }
        if (event instanceof StrafeEvent) {
            StrafeEvent strafe = (StrafeEvent) event;
            if (strafe.getStrafe() != pushStrafe || strafe.getForward() != pushForward
                    || strafe.getFriction() != pushFriction) {
                tick(name, "push");
            }
        }
        boolean turned = Math.abs(player.rotationYaw - yaw) > ANGLE_EPS || Math.abs(player.rotationPitch - pitch) > ANGLE_EPS;
        if (event instanceof UpdateEvent) {
            UpdateEvent update = (UpdateEvent) event;
            turned |= Math.abs(update.getNewYaw() - sentYaw) > ANGLE_EPS
                    || Math.abs(update.getNewPitch() - sentPitch) > ANGLE_EPS;
        }
        if (turned) {
            note(name, "rotate");
            tick(name, "rotate");
        }
        if (Math.abs(timerSpeed() - timer) > 1.0E-4F) {
            note(name, "timer");
        }
    }

    private static void tick(String module, String kind) {
        TreeSet<String> kinds = tickMovers.get(module);
        if (kinds == null) {
            kinds = new TreeSet<String>();
            tickMovers.put(module, kinds);
        }
        kinds.add(kind);
    }

    /** What modules changed since the last call, and starts a new count. */
    public static Map<String, TreeSet<String>> takeTickMovers() {
        Map<String, TreeSet<String>> out = tickMovers;
        tickMovers = new TreeMap<String, TreeSet<String>>();
        return out;
    }

    /** The same so far, without starting a new count. */
    public static Map<String, TreeSet<String>> peekTickMovers() {
        return tickMovers;
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
