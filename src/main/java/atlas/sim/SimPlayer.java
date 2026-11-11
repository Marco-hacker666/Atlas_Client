package atlas.sim;

import atlas.management.RotationHelper;
import atlas.management.RotationState;
import atlas.mixin.IAccessorEntity;
import atlas.mixin.IAccessorEntityLivingBase;
import atlas.module.modules.NoSlow;
import atlas.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlime;
import net.minecraft.block.BlockSoulSand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * The game's side of {@link PlayerSim} (2026-11-11): the player as it is now,
 * the world it moves in, and the keys its next move will get.
 *
 * Which keys depends on when in the tick the question is asked:
 *   - {@link #inputAhead}: before the move's keys are read (UpdateEvent PRE,
 *     TickEvent) -- from the keys held;
 *   - {@link #inputBeforeMove}: at MoveInputEvent, before the shared look
 *     turns the keys (RotationHelper, LOWEST) -- from movementInput as the
 *     modules so far left it;
 *   - {@link #inputAsMoved}: once the keys are final (LivingUpdateEvent) --
 *     movementInput as it is.
 * All three give the yaw the move runs by: the look's when a module turns the
 * move, else the camera's.
 *
 * What the simulation cannot see: a module that changes the push itself at
 * StrafeEvent or sets the motion (Speed, TargetStrafe, a velocity module),
 * and anything the server does. Ask it about plain walking.
 */
public final class SimPlayer {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private SimPlayer() {
    }

    /**
     * False where the simulation does not cover the move: water, lava, a
     * ladder, a cobweb, soul sand, slime, riding, flying. The caller keeps
     * what it did before.
     */
    public static boolean supported(EntityPlayerSP player) {
        if (player == null || player.worldObj == null || player.movementInput == null) {
            return false;
        }
        if (player.isInWater() || player.isInLava() || player.isOnLadder() || player.isRiding()
                || player.capabilities.isFlying || player.noClip || player.isPlayerSleeping()
                || ((IAccessorEntity) player).getIsInWeb()) {
            return false;
        }
        AxisAlignedBB box = player.getEntityBoundingBox();
        Block under = player.worldObj.getBlockState(new BlockPos(player.posX, box.minY - 0.2, player.posZ)).getBlock();
        Block at = player.worldObj.getBlockState(new BlockPos(player.posX, box.minY + 0.01, player.posZ)).getBlock();
        return !(under instanceof BlockSlime) && !(under instanceof BlockSoulSand) && !(at instanceof BlockSoulSand);
    }

    /** The player now. A new object each call: tick it freely. */
    public static PlayerSim state(EntityPlayerSP player) {
        PlayerSim sim = new PlayerSim();
        sim.box = player.getEntityBoundingBox();
        sim.motionX = player.motionX;
        sim.motionY = player.motionY;
        sim.motionZ = player.motionZ;
        sim.onGround = player.onGround;
        sim.sprinting = player.isSprinting();
        sim.collidedHorizontally = player.isCollidedHorizontally;
        sim.jumpTicks = ((IAccessorEntityLivingBase) player).getJumpTicks();
        sim.moveSpeed = player.getAIMoveSpeed();
        sim.airSpeed = player.jumpMovementFactor;
        double attribute = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue();
        /* Sprinting multiplies the attribute by 1.3 (its modifier is on while the flag is). */
        sim.walkSpeed = player.isSprinting() ? attribute / (1.0 + (double) 0.3F) : attribute;
        PotionEffect jump = player.getActivePotionEffect(Potion.jump);
        sim.jumpBoost = jump == null ? 0.0F : (float) (jump.getAmplifier() + 1) * 0.1F;
        sim.stepHeight = player.stepHeight;
        sim.canSprint = (float) player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;
        /* The game asks isUsingItem here too, and gets NoSlow's answer (MixinEntityPlayerSP.isUsing); asked here
           without counting the tick. */
        sim.canStartSprint = !NoSlow.wouldSlowMove(player) && !player.isPotionActive(Potion.blindness);
        return sim;
    }

    /** The world as {@code self} collides with it (blocks, the border, boats). */
    public static PlayerSim.World world(final World world, final Entity self) {
        return new PlayerSim.World() {
            @Override
            public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
                return world.getCollidingBoundingBoxes(self, area);
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return world.getBlockState(new BlockPos(x, y, z)).getBlock().slipperiness;
            }
        };
    }

    /** The keys the next move will get, asked before they are read this tick: from the keys held. */
    public static PlayerSim.Input inputAhead(EntityPlayerSP player) {
        PlayerSim.Input in = look(player);
        boolean sneak = mc.gameSettings.keyBindSneak.isKeyDown();
        float scale = sneak ? 0.3F : 1.0F;
        in.forward = MoveUtil.getForwardValue() * scale;
        in.strafe = MoveUtil.getLeftValue() * scale;
        in.jump = mc.gameSettings.keyBindJump.isKeyDown();
        in.sneak = sneak;
        return turnedAndSlowed(player, in);
    }

    /** The keys this tick's move will get, asked at MoveInputEvent before the shared look turns them. */
    public static PlayerSim.Input inputBeforeMove(EntityPlayerSP player) {
        return turnedAndSlowed(player, held(player));
    }

    /** The keys as they are in movementInput: this tick's move's once they are final, else the last move's. */
    public static PlayerSim.Input inputAsMoved(EntityPlayerSP player) {
        return held(player);
    }

    private static PlayerSim.Input look(EntityPlayerSP player) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : player.rotationYaw;
        in.sprint = mc.gameSettings.keyBindSprint.isKeyDown();
        return in;
    }

    private static PlayerSim.Input held(EntityPlayerSP player) {
        PlayerSim.Input in = look(player);
        in.forward = player.movementInput.moveForward;
        in.strafe = player.movementInput.moveStrafe;
        in.jump = player.movementInput.jump;
        in.sneak = player.movementInput.sneak;
        return in;
    }

    /** What is still to happen to the keys before the move: the shared look's turn, and the item's slowdown. */
    private static PlayerSim.Input turnedAndSlowed(EntityPlayerSP player, PlayerSim.Input in) {
        float[] turned = RotationHelper.get().turnedInput(in.forward, in.strafe);
        in.forward = turned[0];
        in.strafe = turned[1];
        if (NoSlow.wouldSlowMove(player)) {
            in.forward *= 0.2F;
            in.strafe *= 0.2F;
        }
        return in;
    }
}
