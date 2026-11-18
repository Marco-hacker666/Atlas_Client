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

public final class RotationHelper {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final RotationHelper INSTANCE = new RotationHelper();

    public static final float NO_LIMIT = 180.0F;

    public static final float ACCEL = 35.0F;

    public static final float HAND_BACK_STEP = 45.0F;

    private static final float SETTLED = 1.5F;

    private static final int HAND_BACK_TICKS = 20;

    public static final int MOVE_CAMERA = 0;

    public static final int MOVE_FIX = 1;

    public static final int MOVE_LOOK = 2;

    private static final class Request {
        final Object owner;
        final float yaw;
        final float pitch;
        final int priority;
        final int move;
        final boolean clickAlong;
        final float maxTurn;

        final float minStep;

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

    private Request pending;

    private final java.util.Map<Object, Request> asked = new java.util.IdentityHashMap<>();

    private float sentYaw = Float.NaN;
    private float sentPitch = Float.NaN;

    private boolean applied;
    private Object appliedOwner;
    private boolean handingBack;
    private int handBackTicks;

    private float moveYaw = Float.NaN;

    private final RotationEngine turner = new RotationEngine();

    private float lastTurn;

    private RotationHelper() {
    }

    public static RotationHelper get() {
        return INSTANCE;
    }

    public static float[] request(Object owner, float yaw, float pitch, int priority, boolean moveFix, boolean clickAlong) {
        return request(owner, yaw, pitch, priority, moveFix ? MOVE_FIX : MOVE_CAMERA, clickAlong, NO_LIMIT);
    }

    public static float[] request(Object owner, float yaw, float pitch, int priority, int move, boolean clickAlong,
                                  float maxTurn) {
        return request(owner, yaw, pitch, priority, move, clickAlong, maxTurn, ACCEL);
    }

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

    public static float serverYaw() {
        return Float.isNaN(INSTANCE.sentYaw) && mc.thePlayer != null ? mc.thePlayer.rotationYaw : INSTANCE.sentYaw;
    }

    public static float serverPitch() {
        return Float.isNaN(INSTANCE.sentPitch) && mc.thePlayer != null ? mc.thePlayer.rotationPitch : INSTANCE.sentPitch;
    }

    public static float[] packetLook(UpdateEvent event) {
        Request r = INSTANCE.pending;
        if (r != null && event.getRotationPriority() < r.priority && event.getYaw() == r.fromYaw
                && event.getPitch() == r.fromPitch) {
            return r.look.clone();
        }
        return new float[]{event.getNewYaw(), event.getNewPitch()};
    }

    public static boolean askedByOther(Object owner, int priority) {
        Request r = INSTANCE.pending;
        return r != null && r.owner != owner && r.priority >= priority;
    }

    public static boolean isApplied(Object owner) {
        return INSTANCE.applied && INSTANCE.appliedOwner == owner;
    }

    public static void handBack() {
        if (mc.thePlayer != null) {
            INSTANCE.handingBack = true;
            INSTANCE.handBackTicks = 0;
        }
    }

    public static float[] limitTurn(float fromYaw, float fromPitch, float toYaw, float toPitch, float maxTurn) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float dPitch = MathHelper.clamp_float(toPitch, -90.0F, 90.0F) - fromPitch;
        float size = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        float scale = size <= maxTurn || size < 1.0E-6F ? 1.0F : maxTurn / size;
        return new float[]{fromYaw + dYaw * scale, MathHelper.clamp_float(fromPitch + dPitch * scale, -90.0F, 90.0F)};
    }

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

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.pending = null;
            this.asked.clear();
            return;
        }
        if (event.getType() == EventType.POST) {
            this.pending = null;
            this.asked.clear();
            return;
        }

        Request r = this.pending;
        if (r == null || !r.clickAlong || mc.currentScreen != null || VanillaInput.isFighting()) {
            return;
        }
        float[] look = r.look;
        MovingObjectPosition over = VanillaInput.pick(look[0], look[1]);
        boolean lookOnEntity = over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
        boolean cameraOnEntity = mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;

        if (lookOnEntity || cameraOnEntity) {
            VanillaInput.fighting(over);
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
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
            float[] look = event.getYaw() == r.fromYaw && event.getPitch() == r.fromPitch
                    ? r.look : this.decide(event.getYaw(), event.getPitch(), r.yaw, r.pitch, r.maxTurn, r.minStep);
            if (look == null) {
                this.applied = false;
                return;
            }

            boolean won = event.getRotationPriority() < r.priority;
            if (won) {
                event.setRotation(look[0], look[1], r.priority);

                event.setPervRotation(r.move != MOVE_CAMERA ? look[0] : mc.thePlayer.rotationYaw, r.priority);
                this.moveYaw = r.move == MOVE_FIX ? look[0] : Float.NaN;
            }
            this.applied = won;
            this.appliedOwner = won ? r.owner : null;
            this.handingBack = false;
            return;
        }
        if (this.applied) {
            this.applied = false;
            this.appliedOwner = null;
            this.handingBack = true;
            this.handBackTicks = 0;
        }
        if (!this.handingBack) {
            return;
        }
        if (event.isRotated() || ++this.handBackTicks > HAND_BACK_TICKS) {
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

    public float[] turnedInput(float forward, float strafe) {
        if (Float.isNaN(this.moveYaw) || mc.thePlayer == null || !RotationState.isActived()
                || RotationState.getSmoothedYaw() != this.moveYaw || forward == 0.0F && strafe == 0.0F
                || MoveUtil.strafeFixedThisTick(this.moveYaw)) {
            return new float[]{forward, strafe};
        }
        return MoveUtil.fixedStrafe(this.moveYaw, forward, strafe);
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
