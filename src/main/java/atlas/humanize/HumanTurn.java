package atlas.humanize;

import net.minecraft.util.MathHelper;

public final class HumanTurn {
    public static final float SETTLED = 4.0F;

    private HumanTurn() {
    }

    public static float[] apply(Humanizer humanizer, float fromYaw, float fromPitch, float toYaw, float toPitch) {
        float turnYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float turnPitch = toPitch - fromPitch;

        AimError.Adjust adjust = humanizer.aim(turnYaw, turnPitch, 0.0F);
        if (adjust == null || (float) Math.hypot(turnYaw, turnPitch) < SETTLED) {
            return new float[]{toYaw, toPitch};
        }
        return new float[]{toYaw + adjust.yaw, MathHelper.clamp_float(toPitch + adjust.pitch, -90.0F, 90.0F)};
    }
}
