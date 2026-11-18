package atlas.util;

import atlas.event.events.Event;
import atlas.events.JumpEvent;
import atlas.events.KnockbackEvent;
import atlas.events.LivingUpdateEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.events.PreMoveInputEvent;
import atlas.events.SafeWalkEvent;
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
    private static double kbX, kbY, kbZ;
    private static boolean kbCancelled, edgeStop;

    private static Map<String, TreeSet<String>> tickMovers = new TreeMap<String, TreeSet<String>>();

    public static volatile java.util.function.Consumer<UpdateEvent> beforeUpdatePost;

    private ActionWatch() {
    }

    public static boolean watches(Event event) {
        return event instanceof UpdateEvent || event instanceof StrafeEvent || event instanceof PlayerUpdateEvent
                || event instanceof KnockbackEvent || event instanceof JumpEvent || event instanceof TickEvent
                || event instanceof LivingUpdateEvent || event instanceof MoveInputEvent
                || event instanceof PreMoveInputEvent || event instanceof SafeWalkEvent;
    }

    public static void silent(String module, String kind) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()) {
            return;
        }
        tick(module, kind);
    }

    public static void before(Event event) {
        armed = false;
        Minecraft mc = Minecraft.getMinecraft();

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
        if (event instanceof KnockbackEvent) {
            KnockbackEvent knockback = (KnockbackEvent) event;
            kbX = knockback.getX();
            kbY = knockback.getY();
            kbZ = knockback.getZ();
            kbCancelled = knockback.isCancelled();
        }
        if (event instanceof SafeWalkEvent) {
            edgeStop = ((SafeWalkEvent) event).isSafeWalk();
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
        if (event instanceof KnockbackEvent) {
            KnockbackEvent knockback = (KnockbackEvent) event;
            if (knockback.getX() != kbX || knockback.getY() != kbY || knockback.getZ() != kbZ
                    || knockback.isCancelled() != kbCancelled) {
                tick(name, "knockback");
            }
        }
        if (event instanceof SafeWalkEvent && ((SafeWalkEvent) event).isSafeWalk() != edgeStop) {
            tick(name, "edge");
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

    public static Map<String, TreeSet<String>> takeTickMovers() {
        Map<String, TreeSet<String>> out = tickMovers;
        tickMovers = new TreeMap<String, TreeSet<String>>();
        return out;
    }

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
