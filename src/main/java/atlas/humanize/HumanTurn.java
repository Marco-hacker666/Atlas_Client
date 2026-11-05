package atlas.humanize;

import net.minecraft.util.MathHelper;

/**
 * The aim error of a look that has to land on something (2026-11-05): a block face or a turn for a hit. Humanization's
 * tremor, overshoot and undershoot go on the way there, and stop once the look is close: a look left shaking at the
 * end would miss the face it is aimed at. With humanization off for this consumer the look is left as it is.
 */
public final class HumanTurn {
    /** Degrees from the target under which the look is exact. */
    public static final float SETTLED = 4.0F;

    private HumanTurn() {
    }

    /** The look to ask for this tick instead of {toYaw, toPitch}, turning from {fromYaw, fromPitch}. */
    public static float[] apply(Humanizer humanizer, float fromYaw, float fromPitch, float toYaw, float toPitch) {
        float turnYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float turnPitch = toPitch - fromPitch;
        /* Asked on settled ticks too: it has to see a turn end, or the next one rolls no overshoot. */
        AimError.Adjust adjust = humanizer.aim(turnYaw, turnPitch, 0.0F);
        if (adjust == null || (float) Math.hypot(turnYaw, turnPitch) < SETTLED) {
            return new float[]{toYaw, toPitch};
        }
        return new float[]{toYaw + adjust.yaw, MathHelper.clamp_float(toPitch + adjust.pitch, -90.0F, 90.0F)};
    }
}
