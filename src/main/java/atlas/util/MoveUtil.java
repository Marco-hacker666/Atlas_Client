package atlas.util;

import atlas.Atlas;
import atlas.management.RotationState;
import atlas.module.modules.TargetStrafe;
import net.minecraft.client.Minecraft;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;

public class MoveUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static boolean isForwardPressed() {
        if (MoveUtil.mc.gameSettings.keyBindForward.isKeyDown() != MoveUtil.mc.gameSettings.keyBindBack.isKeyDown())
            return true;
        return MoveUtil.mc.gameSettings.keyBindLeft.isKeyDown() != MoveUtil.mc.gameSettings.keyBindRight.isKeyDown();
    }

    public static int getForwardValue() {
        int forwardValue = 0;
        if (MoveUtil.mc.gameSettings.keyBindForward.isKeyDown()) {
            ++forwardValue;
        }
        if (MoveUtil.mc.gameSettings.keyBindBack.isKeyDown()) {
            --forwardValue;
        }
        return forwardValue;
    }

    public static int getLeftValue() {
        int leftValue = 0;
        if (MoveUtil.mc.gameSettings.keyBindLeft.isKeyDown()) {
            ++leftValue;
        }
        if (MoveUtil.mc.gameSettings.keyBindRight.isKeyDown()) {
            --leftValue;
        }
        return leftValue;
    }

    public static float getMoveYaw() {
        return MoveUtil.adjustYaw(RotationState.isActived() ? RotationState.getSmoothedYaw() : MoveUtil.mc.thePlayer.rotationYaw, MoveUtil.mc.thePlayer.movementInput.moveForward, MoveUtil.mc.thePlayer.movementInput.moveStrafe);
    }

    public static float adjustYaw(float yaw, float forward, float strafe) {
        TargetStrafe targetStrafe = (TargetStrafe) Atlas.moduleManager.modules.get(TargetStrafe.class);
        if (targetStrafe.isEnabled()) {
            if (!Float.isNaN(targetStrafe.getTargetYaw())) {
                return targetStrafe.getTargetYaw();
            }
        }
        if (forward < 0.0f) {
            yaw += 180.0f;
        }
        if (strafe != 0.0f) {
            float multiplier = forward == 0.0f ? 1.0f : 0.5f * Math.signum(forward);
            yaw += -90.0f * multiplier * Math.signum(strafe);
        }
        return MathHelper.wrapAngleTo180_float(yaw);
    }

    public static float getDirectionYaw() {
        if (MoveUtil.getSpeed() == 0.0) {
            return MathHelper.wrapAngleTo180_float(MoveUtil.mc.thePlayer.rotationYaw);
        }
        return MathHelper.wrapAngleTo180_float((float) Math.toDegrees(Math.atan2(MoveUtil.mc.thePlayer.motionZ, MoveUtil.mc.thePlayer.motionX)) - 90.0f);
    }

    public static double getBaseMoveSpeed() {
        double baseSpeed = 0.28015;
        if (MoveUtil.getSpeedTime() > 0) {
            baseSpeed = 0.28015 * (1.0 + 0.15 * (double) MoveUtil.getSpeedLevel());
        }
        return baseSpeed;
    }

    public static double getBaseJumpHigh(int speedLevel) {
        double jumpHeight = 0.452;
        if (speedLevel == 1) {
            jumpHeight = 0.49720000000000003;
        } else if (speedLevel >= 2) {
            jumpHeight *= 1.2;
        }
        return jumpHeight;
    }

    public static double getJumpMotion() {
        int speedLevel = 0;
        if (MoveUtil.getSpeedTime() > 0) {
            speedLevel = MoveUtil.getSpeedLevel();
        }
        return MoveUtil.getBaseJumpHigh(speedLevel);
    }

    public static double getSpeed() {
        return MoveUtil.getSpeed(MoveUtil.mc.thePlayer.motionX, MoveUtil.mc.thePlayer.motionZ);
    }

    public static double getSpeed(double motionX, double motionZ) {
        return Math.hypot(motionX, motionZ);
    }

    public static void setSpeed(double speed) {
        MoveUtil.setSpeed(speed, MoveUtil.getDirectionYaw());
    }

    public static void setSpeed(double speed, float yaw) {
        MoveUtil.mc.thePlayer.motionX = -Math.sin(Math.toRadians(yaw)) * speed;
        MoveUtil.mc.thePlayer.motionZ = Math.cos(Math.toRadians(yaw)) * speed;
    }

    public static void addSpeed(double speed, float yaw) {
        MoveUtil.mc.thePlayer.motionX += -Math.sin(Math.toRadians(yaw)) * speed;
        MoveUtil.mc.thePlayer.motionZ += Math.cos(Math.toRadians(yaw)) * speed;
    }

    public static int getSpeedLevel() {
        int speedLevel = 0;
        if (MoveUtil.mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            speedLevel = (MoveUtil.mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1);
        }
        return speedLevel;
    }

    public static int getSpeedTime() {
        if (MoveUtil.mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            return MoveUtil.mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getDuration();
        }
        return 0;
    }

    public static float getAllowedHorizontalDistance() {
        float slipperiness = MoveUtil.mc.thePlayer.worldObj.getBlockState(new BlockPos(MathHelper.floor_double(MoveUtil.mc.thePlayer.posX), MathHelper.floor_double(MoveUtil.mc.thePlayer.getEntityBoundingBox().minY) - 1, MathHelper.floor_double(MoveUtil.mc.thePlayer.posZ))).getBlock().slipperiness * 0.91f;
        return MoveUtil.mc.thePlayer.getAIMoveSpeed() * (0.16277136f / (slipperiness * slipperiness * slipperiness));
    }

    public static double[] predictMovement() {
        float strafeInput = (float) MoveUtil.getLeftValue() * 0.98f;
        float forwardInput = (float) MoveUtil.getForwardValue() * 0.98f;
        float inputMagnitude = strafeInput * strafeInput + forwardInput * forwardInput;
        if (inputMagnitude >= 1.0E-4f) {
            inputMagnitude = MathHelper.sqrt_float(inputMagnitude);
            if (inputMagnitude < 1.0f) {
                inputMagnitude = 1.0f;
            }
            inputMagnitude = MoveUtil.getAllowedHorizontalDistance() / inputMagnitude;
            float sinYaw = MathHelper.sin(MoveUtil.mc.thePlayer.rotationYaw * (float) Math.PI / 180.0f);
            float cosYaw = MathHelper.cos(MoveUtil.mc.thePlayer.rotationYaw * (float) Math.PI / 180.0f);
            strafeInput *= inputMagnitude;
            forwardInput *= inputMagnitude;
            return new double[]{strafeInput * cosYaw - forwardInput * sinYaw, forwardInput * cosYaw + strafeInput * sinYaw};
        }
        return new double[]{0.0, 0.0};
    }

    /* fixStrafe's once-a-tick guard: the tick and the yaw it last turned the input to. */
    private static int fixedTick = Integer.MIN_VALUE;
    private static float fixedYaw;

    /**
     * Turns this tick's movement input from the camera's yaw to the yaw the
     * server is told, as the nearest of the eight key directions at the same
     * strength.
     *
     * 2026-10-17 (a player: "your movement fix is broken, you're not moving
     * properly"): this used to REBUILD the input from the raw W/A/S/D keys and
     * overwrite movementInput, so whatever another module had done to it this
     * tick was lost -- SprintReset's released forward, a soft forward, a
     * module's own steering -- and the strength was reset to 1. Now it takes
     * the input as it stands. It runs once a tick: called again (MoveFix and a
     * rotating module both call it) it would turn an input already turned.
     */
    public static void fixStrafe(float targetYaw) {
        MovementInput input = MoveUtil.mc.thePlayer.movementInput;
        int tick = MoveUtil.mc.thePlayer.ticksExisted;
        if (tick == fixedTick && MathHelper.wrapAngleTo180_float(targetYaw - fixedYaw) == 0.0F) {
            return;
        }
        fixedTick = tick;
        fixedYaw = targetYaw;
        TargetStrafe targetStrafe = (TargetStrafe) Atlas.moduleManager.modules.get(TargetStrafe.class);
        float[] out;
        if (targetStrafe != null && targetStrafe.isEnabled() && !Float.isNaN(targetStrafe.getTargetYaw())
                && (input.moveForward != 0.0F || input.moveStrafe != 0.0F)) {
            float strength = Math.max(Math.abs(input.moveForward), Math.abs(input.moveStrafe));
            out = keysToward(targetStrafe.getTargetYaw(), targetYaw, strength);
        } else {
            out = turnInput(MoveUtil.mc.thePlayer.rotationYaw, targetYaw, input.moveForward, input.moveStrafe);
        }
        input.moveForward = out[0];
        input.moveStrafe = out[1];
    }

    /**
     * {forward, strafe} that move toward the same world direction as
     * {@code forward}/{@code strafe} do at {@code cameraYaw}, but at
     * {@code moveYaw}: the nearest of the eight key directions, at the
     * input's strength (the larger of the two magnitudes, so a sneak's 0.3 or
     * a soft forward stays). No input, no movement.
     */
    public static float[] turnInput(float cameraYaw, float moveYaw, float forward, float strafe) {
        if (forward == 0.0F && strafe == 0.0F) {
            return new float[]{0.0F, 0.0F};
        }
        float strength = Math.max(Math.abs(forward), Math.abs(strafe));
        /* World direction of the input (moveFlying: strafe to the left turns it by -90). */
        float world = cameraYaw - (float) Math.toDegrees(Math.atan2(strafe, forward));
        return keysToward(world, moveYaw, strength);
    }

    /** Keys, at {@code strength}, that move toward {@code worldYaw} when moving by {@code moveYaw}. */
    static float[] keysToward(float worldYaw, float moveYaw, float strength) {
        float relative = MathHelper.wrapAngleTo180_float(worldYaw - moveYaw);
        int sector = Math.round(relative / 45.0F);
        double angle = Math.toRadians(sector * 45.0);
        float forward = (float) Math.round(Math.cos(angle));
        float strafe = (float) -Math.round(Math.sin(angle));
        return new float[]{forward * strength, strafe * strength};
    }

    public static boolean isMoving() {
        return mc.thePlayer.movementInput.moveForward != 0.0F
                || mc.thePlayer.movementInput.moveStrafe != 0.0F
                || Math.abs(mc.thePlayer.motionX) > 0.01
                || Math.abs(mc.thePlayer.motionZ) > 0.01;
    }

    public static double direction(
            float rotationYaw, final double moveForward, final double moveStrafing) {
        if (moveForward < 0F) rotationYaw += 180F;
        float forward = 1F;
        if (moveForward < 0F) forward = -0.5F;
        else if (moveForward > 0F) forward = 0.5F;
        if (moveStrafing > 0F) rotationYaw -= 90F * forward;
        if (moveStrafing < 0F) rotationYaw += 90F * forward;
        return Math.toRadians(rotationYaw);
    }

    public static double wrappedDifference(double number1, double number2) {
        return Math.min(
                Math.abs(number1 - number2),
                Math.min(
                        Math.abs(number1 - 360) - Math.abs(number2 - 0),
                        Math.abs(number2 - 360) - Math.abs(number1 - 0)));
    }


    public static void fixMovement(final float yaw) {
        final float forward = mc.thePlayer.movementInput.moveForward;
        final float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward == 0 && strafe == 0) return;

        final double angle =
                MathHelper.wrapAngleTo180_double(
                        Math.toDegrees(direction(mc.thePlayer.rotationYaw, forward, strafe)));
        float closestForward = 0, closestStrafe = 0;
        double closestDifference = Double.MAX_VALUE;

        for (float predictedForward = -1F; predictedForward <= 1F; predictedForward += 1F) {
            for (float predictedStrafe = -1F; predictedStrafe <= 1F; predictedStrafe += 1F) {
                if (predictedStrafe == 0 && predictedForward == 0) continue;

                final double predictedAngle =
                        MathHelper.wrapAngleTo180_double(
                                Math.toDegrees(direction(yaw, predictedForward, predictedStrafe)));
                final double difference = wrappedDifference(angle, predictedAngle);

                if (difference < closestDifference) {
                    closestDifference = difference;
                    closestForward = predictedForward;
                    closestStrafe = predictedStrafe;
                }
            }
        }
    }
}
