package myau.module.modules;

import myau.event.EventTarget;
import myau.events.PostStrafeEvent;
import myau.events.StrafeEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;

/**
 * Turns your motion toward the keys you hold (2026-10-19, Slinky's Strafe
 * rebuilt).
 *
 * Right after moveFlying, the horizontal motion is pulled toward the
 * direction of the held keys at its current speed (plus 0.2 on a jump
 * started without sprint): 0 % leaves vanilla momentum, 100 % turns it fully
 * this tick. Separate strengths for the jump tick, the ground and the air.
 */
public class Strafe extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty jump = new PercentProperty("jump", 0);
    public final PercentProperty ground = new PercentProperty("ground", 0);
    public final PercentProperty air = new PercentProperty("air", 50);
    public final IntProperty maxHurtTime = new IntProperty("max-hurt-time", 10, 0, 10);
    public final BooleanProperty requireWeapon = new BooleanProperty("require-weapon", false);

    private boolean sprintBefore;

    public Strafe() {
        super("Strafe", false);
    }

    /** The motion pulled toward the held direction by {@code amount} (0..1). */
    public static double[] redirect(double x, double z, float yaw, float forward, float sideways, float amount,
                                    boolean boost) {
        double speed = Math.hypot(x, z);
        if (boost) {
            speed += 0.2;
        }
        double turn = sideways < 0.0F ? -1.0 : sideways > 0.0F ? 1.0 : 0.0;
        double heading = yaw;
        if (forward > 0.0F) {
            turn *= -45.0;
        } else if (forward < 0.0F) {
            heading += 180.0;
            turn *= 45.0;
        } else {
            turn *= -90.0;
        }
        double radians = (turn + heading) * Math.PI / 180.0;
        double nextX = x + (-Math.sin(radians) * speed - x) * amount;
        double nextZ = z + (Math.cos(radians) * speed - z) * amount;
        return new double[]{nextX, nextZ};
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (mc.thePlayer != null) {
            this.sprintBefore = mc.thePlayer.isSprinting();
        }
    }

    @EventTarget
    public void onPostStrafe(PostStrafeEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getForward() == 0.0F && event.getStrafe() == 0.0F) {
            return;
        }
        if (this.requireWeapon.getValue() && !FastAccel.holdingWeapon()
                || mc.thePlayer.hurtTime > this.maxHurtTime.getValue()) {
            return;
        }
        boolean onGround = mc.thePlayer.onGround;
        int percent = !onGround ? this.air.getValue() : event.isJump() ? this.jump.getValue() : this.ground.getValue();
        if (percent == 0) {
            return;
        }
        /* The yaw the movement itself used: the server's, while rotations are silent. */
        float yaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        double[] motion = redirect(mc.thePlayer.motionX, mc.thePlayer.motionZ, yaw, event.getForward(),
                event.getStrafe(), percent / 100.0F, onGround && event.isJump() && !this.sprintBefore);
        mc.thePlayer.motionX = motion[0];
        mc.thePlayer.motionZ = motion[1];
    }

    @Override
    public void onDisabled() {
        this.sprintBefore = false;
    }
}
