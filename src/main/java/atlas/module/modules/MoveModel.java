package atlas.module.modules;

import net.minecraft.util.MathHelper;

/**
 * One tick of 1.8's walking and falling, horizontally, as a server that re-runs it would work it out (2026-11-05): the
 * motion carried in, a jump's sprint boost, moveFlying's input at a yaw. No collisions, liquids or ladders: MoveCheck
 * leaves those ticks out. Pure, so it can be tested.
 */
final class MoveModel {

    private static final float INPUT_SCALE = 0.98F;
    private static final float SNEAK_SCALE = 0.3F;
    private static final float USE_SCALE = 0.2F;
    private static final float AIR_SPEED = 0.02F;
    private static final float AIR_SPRINT_SPEED = 0.026F;
    private static final float JUMP_BOOST = 0.2F;

    private MoveModel() {
    }

    /** What one tick needs besides the keys and the yaw. */
    static final class State {
        double motionX;
        double motionZ;
        boolean onGround;
        boolean sprinting;
        boolean sneaking;
        boolean usingItem;
        boolean jumped;
        float slipperiness = 0.6F;
        float moveSpeed = 0.1F;
    }

    /** The horizontal move {x, z} this tick from these keys (-1, 0, 1) at this yaw. */
    static double[] step(State s, float yaw, float forward, float strafe) {
        double x = Math.abs(s.motionX) < 0.005 ? 0.0 : s.motionX;
        double z = Math.abs(s.motionZ) < 0.005 ? 0.0 : s.motionZ;
        float radians = yaw * (float) Math.PI / 180.0F;
        float sin = MathHelper.sin(radians);
        float cos = MathHelper.cos(radians);
        if (s.jumped && s.sprinting) {
            x -= sin * JUMP_BOOST;
            z += cos * JUMP_BOOST;
        }
        float scale = INPUT_SCALE * (s.sneaking ? SNEAK_SCALE : 1.0F) * (s.usingItem ? USE_SCALE : 1.0F);
        float inputForward = forward * scale;
        float inputStrafe = strafe * scale;
        float friction = s.onGround ? s.slipperiness * 0.91F : 0.91F;
        float speed = s.onGround ? s.moveSpeed * (0.16277136F / (friction * friction * friction))
                : s.sprinting ? AIR_SPRINT_SPEED : AIR_SPEED;
        float size = inputForward * inputForward + inputStrafe * inputStrafe;
        if (size >= 1.0E-4F) {
            size = speed / Math.max(1.0F, MathHelper.sqrt_float(size));
            inputForward *= size;
            inputStrafe *= size;
            x += inputStrafe * cos - inputForward * sin;
            z += inputForward * cos + inputStrafe * sin;
        }
        return new double[]{x, z};
    }

    /** How far the real move is from the best of the nine key combinations at this yaw. */
    static double bestError(State s, float yaw, double moveX, double moveZ) {
        double best = Double.MAX_VALUE;
        for (int forward = -1; forward <= 1; forward++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                double[] move = step(s, yaw, forward, strafe);
                best = Math.min(best, Math.hypot(moveX - move[0], moveZ - move[1]));
            }
        }
        return best;
    }
}
