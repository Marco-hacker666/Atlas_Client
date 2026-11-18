package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.AttackEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.UpdateEvent;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.ChatUtil;
import atlas.util.KnockbackPlanner;
import atlas.util.MoveUtil;
import atlas.util.RotationEngine;
import atlas.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class KBDisplacement extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int PRIORITY = 2;

    public final IntProperty rotationSpeed = new IntProperty("rotation-speed", 8, 1, 20);
    public final FloatProperty trackingRange = new FloatProperty("tracking-range", 6.0F, 3.0F, 8.0F);
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    private EntityLivingBase target;
    private int armedTicks;
    private float moveYaw = Float.NaN;
    private String lastDebug = "";
    private int lastDebugTick = -1;

    public KBDisplacement() {
        super("KBDisplacement", false, false, "Silently aims your knockback at nearby hazards after a hit");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    private void reset() {
        this.target = null;
        this.armedTicks = 0;
        this.moveYaw = Float.NaN;
        this.lastDebug = "";
        this.lastDebugTick = -1;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        reset();
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (atlas.util.Auras.anyEnabled()) {
            return;
        }
        if (!event.isCancelled() && event.getTarget() instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) event.getTarget();
            this.armedTicks = 2;
            debug("armed", null);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        this.moveYaw = Float.NaN;
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.target == null || mc.thePlayer == null) {
            return;
        }
        if (this.armedTicks > 0) {
            this.armedTicks--;
        }
        EntityLivingBase living = this.target;
        if (living.isDead || mc.theWorld == null
                || mc.thePlayer.getDistanceToEntity(living) > this.trackingRange.getValue() + 1.0) {
            debug("rejected:no-target", null);
            reset();
            return;
        }
        if (!KnockbackPlanner.hasKnockbackSource(mc.thePlayer, EnchantmentHelper.getKnockbackModifier(mc.thePlayer))) {
            debug("rejected:no-kb-source", null);
            return;
        }
        if (living.hurtTime <= 0 && this.armedTicks <= 0) {
            debug("rejected:hurt-time-ended", null);
            reset();
            return;
        }
        if (KnockbackPlanner.fallingCrit(mc.thePlayer)) {
            debug("rejected:crit-priority", null);
            return;
        }
        KnockbackPlanner.Plan plan = KnockbackPlanner.plan(mc.theWorld, living);
        if (plan == null) {
            debug("rejected:no-plan", null);
            return;
        }
        float from = event.getYaw();
        float wantYaw = from + MathHelper.wrapAngleTo180_float(plan.yaw - from);
        float wantPitch = pitchTo(living, wantYaw);
        float limit = this.rotationSpeed.getValue() * 36.0F;
        float dYaw = MathHelper.clamp_float(wantYaw - from, -limit, limit);
        float dPitch = MathHelper.clamp_float(wantPitch - event.getPitch(), -limit, limit);
        float[] next = RotationUtil.gcd(new float[]{from + dYaw, event.getPitch() + dPitch},
                new float[]{from, event.getPitch()});
        event.setRotation(next[0], next[1], PRIORITY);
        this.moveYaw = next[0];
        event.setPervRotation(this.moveYaw, PRIORITY);
        debug("applied", plan);
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()
                && !Float.isNaN(this.moveYaw)
                && RotationState.isActived()
                && RotationState.getSmoothedYaw() == this.moveYaw
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(this.moveYaw);
        }
    }

    private static float pitchTo(EntityLivingBase living, float yaw) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 body = new Vec3(living.posX, living.posY + living.height * 0.5, living.posZ);
        return RotationEngine.rotationsTo(eyes, body, yaw)[1];
    }

    private void debug(String what, KnockbackPlanner.Plan plan) {
        if (!this.debug.getValue() || mc.thePlayer == null) {
            return;
        }
        String line = plan == null
                ? String.format("%s hurt=%d", what, this.target == null ? -1 : this.target.hurtTime)
                : String.format("%s %s hurt=%d", what, plan, this.target == null ? -1 : this.target.hurtTime);
        if (!line.equals(this.lastDebug) || mc.thePlayer.ticksExisted - this.lastDebugTick >= 8) {
            this.lastDebug = line;
            this.lastDebugTick = mc.thePlayer.ticksExisted;
            ChatUtil.sendFormatted("&7[&bKBDisplacement&7] " + line);
        }
    }
}
