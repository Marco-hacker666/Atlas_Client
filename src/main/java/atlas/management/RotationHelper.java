package atlas.management;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.util.MoveUtil;
import atlas.util.RotationEngine;
import atlas.util.RotationUtil;
import atlas.util.VanillaInput;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

/**
 * The shared server look (2026-10-31, after Raven bS 16's RotationHelper).
 *
 * A module asks for the look it wants this tick with {@link #request}; this
 * class does the rest the same way for everyone:
 * - Clutch's turn: it speeds up from rest, at most ACCEL degrees a tick more
 *   than the last tick's turn (measured on what the server got, whoever sent
 *   it), and with the Rotations module on the speed wanders smoothly and a
 *   turn that does not land this tick bows off its line (RotationEngine
 *   NOISE, CURVE). No cap beyond that (owner, 2026-10-31: "acceleration yes,
 *   not the 90-degree limit"), unless the module gives one of its own (its
 *   speed setting, each axis, as those modules always did);
 * - whole mouse counts from the look the server was last sent, never NaN;
 * - a yaw step equal to the one at the last judged placement is moved one
 *   mouse count (Grim DuplicateRotPlace, PlaceRotations);
 * - the movement yaw is one of three: the camera's (MOVE_CAMERA), the look's
 *   with the keys turned so the player still goes where the camera points
 *   (MOVE_FIX, MoveUtil.fixStrafe), or the look's as it is (MOVE_LOOK, the
 *   modules' STRICT and REAL); registered at the request's priority even for
 *   the camera's, so a lower module cannot move the player by its yaw under a
 *   packet that carries this one;
 * - with clickAlong this tick's clicks hit along that look (VanillaInput), so
 *   what is hit is what the server sees aimed at -- unless an aura already
 *   set the clicks' look this tick;
 * - when no module asks any more, the look goes back to the camera at
 *   HAND_BACK_STEP a tick instead of in one jump, the keys turned the same way.
 *
 * request() decides the look at once and returns it -- the look this tick's
 * packet carries if nothing of a higher priority asks after -- so a module
 * places, throws or hits along the look the server will get; asked again in
 * the same tick for the same thing it returns the same look, and that look is
 * the one the packet carries (Clutch's holdThisTick). Requests made at
 * TickEvent PRE (start of runTick) steer that tick's clicks too; requests made
 * later, up to UpdateEvent PRE, only the movement packet.
 *
 * It sits beside the older direct event.setRotation calls and follows the same
 * rule: the higher priority has the packet, and a module that set the look
 * itself at the same priority keeps it (it ran before this class, which runs
 * last; before the move onto this class the later of two equal priorities kept
 * it -- KillAura after ThrowAura). Atlas registers this class after every
 * module, so a module's LOWEST handler still asks before the packet is set.
 */
public final class RotationHelper {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final RotationHelper INSTANCE = new RotationHelper();

    /** No cap of the module's own: only the acceleration below limits the turn. */
    public static final float NO_LIMIT = 180.0F;
    /** At most this much more turn than last tick, before jitter: the hand speeds up, it does not jump to full speed. */
    public static final float ACCEL = 35.0F;
    /** Degrees a tick on the way back to the camera, before jitter. */
    public static final float HAND_BACK_STEP = 45.0F;
    /** Within this of the camera the hand-back lets go. */
    private static final float SETTLED = 1.5F;
    /** A hand-back never lasts longer than this many ticks. */
    private static final int HAND_BACK_TICKS = 20;

    /** Movement computed by the camera's yaw (the look is only in the packet). */
    public static final int MOVE_CAMERA = 0;
    /** Movement computed by the look's yaw, the keys turned so the player still goes where the camera points. */
    public static final int MOVE_FIX = 1;
    /** Movement computed by the look's yaw, keys as pressed: the player goes where the look points. */
    public static final int MOVE_LOOK = 2;

    private static final class Request {
        final Object owner;
        final float yaw;
        final float pitch;
        final int priority;
        final int move;
        final boolean clickAlong;
        final float maxTurn;
        /** The turn allowed from rest, at least (ACCEL unless the module asks for more). */
        final float minStep;
        /** The look decided for it, and the server look it was decided from. */
        final float[] look;
        final float fromYaw;
        final float fromPitch;

        Request(Object owner, float yaw, float pitch, int priority, int move, boolean clickAlong, float maxTurn,
                float minStep, float[] look, float fromYaw, float fromPitch) {
            this.owner = owner;
            this.yaw = yaw;
            this.pitch = pitch;
            this.priority = priority;
            this.move = move;
            this.clickAlong = clickAlong;
            this.maxTurn = maxTurn;
            this.minStep = minStep;
            this.look = look;
            this.fromYaw = fromYaw;
            this.fromPitch = fromPitch;
        }
    }

    /** The winning request of this tick so far (highest priority, then the last one). */
    private Request pending;
    /** Each owner's last request this tick, so the same ask gets the same look even after another took the lead. */
    private final java.util.Map<Object, Request> asked = new java.util.IdentityHashMap<>();
    /** The look the server was last sent (UpdateEvent's lastReported values). */
    private float sentYaw = Float.NaN;
    private float sentPitch = Float.NaN;
    /** This class had the movement packet last tick. */
    private boolean applied;
    private Object appliedOwner;
    private boolean handingBack;
    private int handBackTicks;
    /** The yaw movement is computed by this tick when this class fixes it; NaN otherwise. */
    private float moveYaw = Float.NaN;
    /** Clutch's turn (speed wander, bow); one instance, so it remembers its own speed. */
    private final RotationEngine turner = new RotationEngine();
    /** The turn the server saw last tick (between the last two sent looks), degrees. */
    private float lastTurn;

    private RotationHelper() {
    }

    public static RotationHelper get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ API

    /**
     * The look {@code owner} wants the server to have this tick. Call it every
     * tick the look is wanted (at TickEvent PRE when the clicks should follow
     * it); stop calling and the look is handed back to the camera.
     *
     * @return the look this tick's packet carries for it (a copy), or null when a higher priority asked first
     */
    public static float[] request(Object owner, float yaw, float pitch, int priority, boolean moveFix, boolean clickAlong) {
        return request(owner, yaw, pitch, priority, moveFix ? MOVE_FIX : MOVE_CAMERA, clickAlong, NO_LIMIT);
    }

    /**
     * request() with the movement yaw (MOVE_CAMERA, MOVE_FIX or MOVE_LOOK) and a cap of the module's own, degrees a
     * tick on each axis (NO_LIMIT for none).
     */
    public static float[] request(Object owner, float yaw, float pitch, int priority, int move, boolean clickAlong,
                                  float maxTurn) {
        return request(owner, yaw, pitch, priority, move, clickAlong, maxTurn, ACCEL);
    }

    /**
     * request() whose turn from rest may be as big as {@code minStep} (never below ACCEL): for a module that must
     * land a look within the tick, as Scaffold at a GodBridge side switch; it speeds up from there as any other.
     */
    public static float[] request(Object owner, float yaw, float pitch, int priority, int move, boolean clickAlong,
                                  float maxTurn, float minStep) {
        if (owner == null || mc.thePlayer == null || !finite(yaw) || !finite(pitch)) {
            return null;
        }
        Request current = INSTANCE.pending;
        if (current != null && priority < current.priority) {
            return null;
        }
        float clamped = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        float fromYaw = serverYaw();
        float fromPitch = serverPitch();
        float cap = finite(maxTurn) && maxTurn > 0.0F ? Math.min(NO_LIMIT, maxTurn) : NO_LIMIT;
        float[] look;
        Request before = INSTANCE.asked.get(owner);
        if (before != null && before.yaw == yaw && before.pitch == clamped
                && before.fromYaw == fromYaw && before.fromPitch == fromPitch) {
            /* The same ask again this tick (at the start of the tick for the clicks, then for the packet): the same
               look, so what was done along it still holds. */
            look = before.look;
        } else {
            look = INSTANCE.decide(fromYaw, fromPitch, yaw, clamped, cap, minStep);
            if (look == null) {
                return null;
            }
        }
        Request made = new Request(owner, yaw, clamped, priority, move, clickAlong, cap, minStep, look, fromYaw,
                fromPitch);
        INSTANCE.asked.put(owner, made);
        INSTANCE.pending = made;
        return look.clone();
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** The look the server was last sent; the camera's when nothing has been sent yet. */
    public static float serverYaw() {
        return Float.isNaN(INSTANCE.sentYaw) && mc.thePlayer != null ? mc.thePlayer.rotationYaw : INSTANCE.sentYaw;
    }

    public static float serverPitch() {
        return Float.isNaN(INSTANCE.sentPitch) && mc.thePlayer != null ? mc.thePlayer.rotationPitch : INSTANCE.sentPitch;
    }

    /**
     * During UpdateEvent PRE, the look this tick's movement packet will carry as things stand: the pending
     * request's when it will have the packet (this class sets it last), else what is set on the event. For a
     * module that clicks in a LOWEST handler, which runs before this class.
     */
    public static float[] packetLook(UpdateEvent event) {
        Request r = INSTANCE.pending;
        if (r != null && event.getRotationPriority() < r.priority && event.getYaw() == r.fromYaw
                && event.getPitch() == r.fromPitch) {
            return r.look.clone();
        }
        return new float[]{event.getNewYaw(), event.getNewPitch()};
    }

    /** Another owner's request at {@code priority} or above is pending this tick. */
    public static boolean askedByOther(Object owner, int priority) {
        Request r = INSTANCE.pending;
        return r != null && r.owner != owner && r.priority >= priority;
    }

    /** {@code owner}'s request had the movement packet last tick. */
    public static boolean isApplied(Object owner) {
        return INSTANCE.applied && INSTANCE.appliedOwner == owner;
    }

    /**
     * For a module that set the look itself (event.setRotation) and stops:
     * the look goes back to the camera over the next ticks.
     */
    public static void handBack() {
        if (mc.thePlayer != null) {
            INSTANCE.handingBack = true;
            INSTANCE.handBackTicks = 0;
        }
    }

    /**
     * At most {@code maxTurn} degrees from {from} toward {to} (the straight
     * angle, yaw the short way round), pitch kept in range. Pure, tested.
     */
    public static float[] limitTurn(float fromYaw, float fromPitch, float toYaw, float toPitch, float maxTurn) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float dPitch = MathHelper.clamp_float(toPitch, -90.0F, 90.0F) - fromPitch;
        float size = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        float scale = size <= maxTurn || size < 1.0E-6F ? 1.0F : maxTurn / size;
        return new float[]{fromYaw + dYaw * scale, MathHelper.clamp_float(fromPitch + dPitch * scale, -90.0F, 90.0F)};
    }

    /**
     * Each axis at most {@code cap} degrees from {from} toward {to}, yaw the short way round, pitch in range;
     * NO_LIMIT or more leaves the turn whole. Pure, tested.
     */
    public static float[] clampTurn(float fromYaw, float fromPitch, float toYaw, float toPitch, float cap) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float dPitch = MathHelper.clamp_float(toPitch, -90.0F, 90.0F) - fromPitch;
        if (cap < NO_LIMIT) {
            dYaw = MathHelper.clamp_float(dYaw, -cap, cap);
            dPitch = MathHelper.clamp_float(dPitch, -cap, cap);
        }
        return new float[]{fromYaw + dYaw, MathHelper.clamp_float(fromPitch + dPitch, -90.0F, 90.0F)};
    }

    private static float jitter(float base, float spread) {
        return base * (1.0F - spread + (float) Math.random() * spread * 2.0F);
    }

    // --------------------------------------------------------------- events

    /** LOWEST: after the modules that ask at the start of the tick. The clicks of this tick follow the request. */
    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.pending = null;
            this.asked.clear();
            return;
        }
        if (event.getType() == EventType.POST) {
            /* A request the movement packet never came for (no player update this tick) does not carry over. */
            this.pending = null;
            this.asked.clear();
            return;
        }
        /* The look was decided when it was asked for, from the look the server has, which nothing changes before
           the movement packet: this tick's clicks go along exactly the look that packet will carry. */
        Request r = this.pending;
        if (r == null || !r.clickAlong || mc.currentScreen != null || VanillaInput.isFighting()) {
            return;
        }
        float[] look = r.look;
        MovingObjectPosition over = VanillaInput.pick(look[0], look[1]);
        boolean lookOnEntity = over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
        boolean cameraOnEntity = mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
        /* Only when a player is involved: otherwise the camera's crosshair stays, for blocks. */
        if (lookOnEntity || cameraOnEntity) {
            VanillaInput.fighting(over);
        }
    }

    /** LOWEST: after every module's own rotation, so the priorities are compared like theirs. */
    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
            /* After the movement packet: lastReported is now the look just sent, which the next tick's requests
               (made at TickEvent PRE) must start from. At PRE it is still the one before. */
            float before = this.sentYaw;
            float beforePitch = this.sentPitch;
            this.sentYaw = event.getYaw();
            this.sentPitch = event.getPitch();
            this.lastTurn = Float.isNaN(before) ? 0.0F
                    : RotationEngine.angle(this.sentYaw, this.sentPitch, before, beforePitch);
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        this.moveYaw = Float.NaN;
        this.sentYaw = event.getYaw();
        this.sentPitch = event.getPitch();
        Request r = this.pending;
        this.pending = null;
        this.asked.clear();
        if (mc.thePlayer == null) {
            this.applied = false;
            this.handingBack = false;
            return;
        }
        if (r != null) {
            /* The look decided when it was asked for, while the server look is still the one it was decided from:
               whatever was clicked, placed or thrown along it holds, so the packet carries it unchanged. */
            float[] look = event.getYaw() == r.fromYaw && event.getPitch() == r.fromPitch
                    ? r.look : this.decide(event.getYaw(), event.getPitch(), r.yaw, r.pitch, r.maxTurn, r.minStep);
            if (look == null) {
                this.applied = false;
                return;
            }
            /* A module that set the look itself at this priority or above keeps the packet. Ties go to it: it ran
               before this class, which runs last, and before the move onto this class the later of two equal
               priorities kept it -- KillAura (LOW) after ThrowAura, both at 1. */
            boolean won = event.getRotationPriority() < r.priority;
            if (won) {
                event.setRotation(look[0], look[1], r.priority);
                /* The movement yaw at this priority either way, so a lower module cannot slip its own in. */
                event.setPervRotation(r.move != MOVE_CAMERA ? look[0] : mc.thePlayer.rotationYaw, r.priority);
                this.moveYaw = r.move == MOVE_FIX ? look[0] : Float.NaN;
            }
            this.applied = won;
            this.appliedOwner = won ? r.owner : null;
            this.handingBack = false;
            return;
        }
        if (this.applied) {
            /* Nobody asks any more: back to the camera from here. */
            this.applied = false;
            this.appliedOwner = null;
            this.handingBack = true;
            this.handBackTicks = 0;
        }
        if (!this.handingBack) {
            return;
        }
        if (event.isRotated() || ++this.handBackTicks > HAND_BACK_TICKS) {
            /* Another module turns the look itself now. */
            this.handingBack = false;
            return;
        }
        float cameraYaw = mc.thePlayer.rotationYaw;
        float cameraPitch = mc.thePlayer.rotationPitch;
        if (Math.abs(MathHelper.wrapAngleTo180_float(cameraYaw - event.getYaw())) < SETTLED
                && Math.abs(cameraPitch - event.getPitch()) < SETTLED) {
            this.handingBack = false;
            return;
        }
        float[] toward = limitTurn(event.getYaw(), event.getPitch(), cameraYaw, cameraPitch, jitter(HAND_BACK_STEP, 0.15F));
        float[] look = this.decide(event.getYaw(), event.getPitch(), toward[0], toward[1], NO_LIMIT, ACCEL);
        if (look == null) {
            this.handingBack = false;
            return;
        }
        event.setRotation(look[0], look[1], 0);
        event.setPervRotation(look[0], 0);
        this.moveYaw = look[0];
    }

    /**
     * One tick from {from} toward {to}, Clutch's way: each axis within the module's cap (NO_LIMIT: none), at most
     * ACCEL more than the server saw last tick (no other limit); speed wander and bow when the Rotations module is
     * on; whole mouse counts; a yaw step equal to the last judged placement's moved one count. Null if it is not a
     * number.
     */
    private float[] decide(float fromYaw, float fromPitch, float toYaw, float toPitch, float cap, float minStep) {
        if (!finite(fromYaw) || !finite(fromPitch)) {
            return null;
        }
        float[] wanted = clampTurn(fromYaw, fromPitch, toYaw, toPitch, cap);
        float allowed = Math.max(Math.max(ACCEL, minStep), this.lastTurn + jitter(ACCEL, 0.2F));
        java.util.Set<RotationEngine.Feature> features = atlas.module.modules.Rotations.active() != null
                ? java.util.EnumSet.of(RotationEngine.Feature.NOISE, RotationEngine.Feature.CURVE)
                : java.util.EnumSet.noneOf(RotationEngine.Feature.class);
        float[] look = this.turner.step(fromYaw, fromPitch, wanted[0], wanted[1], allowed, 0.0F, 0, false, features);
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(look[0] - fromYaw));
        for (int guard = 0; guard < 3 && turn > 2.0F && PlaceRotations.wouldDuplicate(look[0]); guard++) {
            double unit = RotationUtil.gcd();
            look[0] += Math.signum(MathHelper.wrapAngleTo180_float(look[0] - fromYaw)) * (float) (unit > 0.0 ? unit : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(look[0] - fromYaw));
        }
        if (!finite(look[0]) || !finite(look[1])) {
            return null;
        }
        look[1] = MathHelper.clamp_float(look[1], -90.0F, 90.0F);
        return look;
    }

    /** The keys turned so the player still goes where the camera points, when this class sets the movement yaw. */
    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (Float.isNaN(this.moveYaw) || mc.thePlayer == null || !RotationState.isActived()
                || RotationState.getSmoothedYaw() != this.moveYaw) {
            return;
        }
        if (mc.thePlayer.movementInput.moveForward != 0.0F || mc.thePlayer.movementInput.moveStrafe != 0.0F) {
            MoveUtil.fixStrafe(this.moveYaw);
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.pending = null;
        this.asked.clear();
        this.applied = false;
        this.appliedOwner = null;
        this.handingBack = false;
        this.moveYaw = Float.NaN;
        this.sentYaw = Float.NaN;
        this.sentPitch = Float.NaN;
        this.lastTurn = 0.0F;
        this.turner.reset();
    }
}
