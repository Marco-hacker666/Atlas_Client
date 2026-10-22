package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.PostStrafeEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;

/**
 * Reaches walking speed sooner (2026-10-19, Slinky's Fast Accel rebuilt).
 *
 * On the ground the speed a held direction settles at is a geometric series:
 * each tick adds the input step and keeps 0.546 of the old motion (0.91
 * drag x 0.6 ground friction). Right after moveFlying this computes that
 * settling speed for the keys held (plus 0.2 on a sprint jump) and, while
 * the player is below it, moves the speed boost of the remaining way toward
 * it in the current direction. It never goes past what holding the keys
 * would reach anyway; it only gets there in fewer ticks.
 */
public class FastAccel extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty boost = new PercentProperty("boost", 50);
    public final BooleanProperty requireNotDamaged = new BooleanProperty("require-not-damaged", false);
    public final BooleanProperty requireWeapon = new BooleanProperty("require-weapon", false);

    public FastAccel() {
        super("FastAccel", false);
    }

    /** The speed holding these keys settles at on the ground. */
    public static double limit(float sideways, float forward, float factor, boolean sprintJump) {
        float length = (float) Math.sqrt(sideways * sideways + forward * forward);
        float denominator = !Float.isNaN(length) && !(length >= 1.0F) ? 1.0F : length;
        float scale = factor / denominator;
        float x = sideways * scale;
        float z = forward * scale;
        double step = Math.sqrt(x * x + z * z);
        double limit = step;
        for (int i = 0; i < 19; i++) {
            limit = limit * 0.546 + step;
        }
        if (sprintJump) {
            limit += 0.2;
        }
        return limit;
    }

    static boolean holdingWeapon() {
        net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
        return held != null && (held.getItem() instanceof net.minecraft.item.ItemSword
                || held.getItem() instanceof net.minecraft.item.ItemAxe);
    }

    @EventTarget
    public void onPostStrafe(PostStrafeEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getForward() == 0.0F && event.getStrafe() == 0.0F) {
            return;
        }
        if (this.requireWeapon.getValue() && !holdingWeapon()
                || this.requireNotDamaged.getValue() && mc.thePlayer.hurtTime > 0) {
            return;
        }
        double speed = Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ);
        if (speed == 0.0 || !mc.thePlayer.onGround) {
            return;
        }
        double limit = limit(event.getStrafe(), event.getForward(), event.getFriction(),
                event.isJump() && mc.thePlayer.isSprinting());
        if (speed > limit) {
            return;
        }
        double accelerated = speed + (limit - speed) * this.boost.getValue() / 100.0;
        mc.thePlayer.motionX = mc.thePlayer.motionX / speed * accelerated;
        mc.thePlayer.motionZ = mc.thePlayer.motionZ / speed * accelerated;
    }
}
