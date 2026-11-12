package atlas.sim;

import atlas.management.RotationHelper;
import atlas.management.RotationState;
import atlas.mixin.IAccessorEntity;
import atlas.mixin.IAccessorEntityLivingBase;
import atlas.module.modules.NoSlow;
import atlas.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSlime;
import net.minecraft.block.BlockSoulSand;
import net.minecraft.block.BlockWeb;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.List;

/**
 * The game's side of {@link PlayerSim} (2026-11-11): the player as it is now,
 * the world it moves in, and the keys its next move will get.
 *
 * Both the state and the keys depend on when in the tick the question is
 * asked, and they have to be of the same moment. {@link #snapshot} gives the
 * two together for a {@link PlayerSim.Stage} (2026-11-12; before, a caller
 * picked a state method and a keys method itself and could pair them wrong):
 *   - START, between ticks (TickEvent, UpdateEvent): the whole tick is to
 *     come, and the keys are the ones held;
 *   - INPUT, at MoveInputEvent before the shared look turns the keys
 *     (RotationHelper, LOWEST): the water has pushed, and the keys are
 *     movementInput's as the modules so far left them, with the turn and the
 *     item's slowdown still to come;
 *   - MOVE, at LivingUpdateEvent: only the move is left, and the keys are
 *     movementInput's as they are.
 * The yaw is the one the move runs by: the look's when a module turns the
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

    /** The player, the keys of its next move and the world, all of one moment in the tick. */
    public static final class Snapshot {
        /** A new state each time: tick it freely. Its first tick runs what is left of the game's. */
        public final PlayerSim sim;
        /** The keys that tick's move gets. A new object: change it to ask "what if". */
        public final PlayerSim.Input input;
        public final PlayerSim.World world;

        private Snapshot(PlayerSim sim, PlayerSim.Input input, PlayerSim.World world) {
            this.sim = sim;
            this.input = input;
            this.world = world;
        }
    }

    /**
     * False where the simulation does not cover the move: riding, flying,
     * no-clip, sleeping, dead (the game then takes no keys). (Water, lava,
     * ladders, cobwebs, soul sand and slime are covered since 2026-11-12.)
     * The caller keeps what it did before.
     */
    public static boolean supported(EntityPlayerSP player) {
        if (player == null || player.worldObj == null || player.movementInput == null) {
            return false;
        }
        return !player.isRiding() && !player.capabilities.isFlying && !player.noClip && !player.isPlayerSleeping()
                && player.isEntityAlive();
    }

    /** The player, its keys and the world as they are at {@code stage} of the tick; ask only where that is true. */
    public static Snapshot snapshot(EntityPlayerSP player, PlayerSim.Stage stage) {
        PlayerSim sim = state(player);
        sim.stage = stage;
        PlayerSim.Input input;
        if (stage == PlayerSim.Stage.START) {
            input = inputAhead(player);
        } else if (stage == PlayerSim.Stage.INPUT) {
            input = inputBeforeMove(player);
        } else {
            input = inputAsMoved(player);
        }
        return new Snapshot(sim, input, world(player.worldObj, player));
    }

    /** The yaw the player's move runs by now: the look's when a module turns the move, else the camera's. */
    public static float moveYaw(EntityPlayerSP player) {
        return RotationState.isActived() ? RotationState.getSmoothedYaw() : player.rotationYaw;
    }

    /** The player now, as between ticks (TickEvent, UpdateEvent). A new object each call: tick it freely. */
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
        sim.width = player.width;
        sim.height = player.height;
        sim.sprintTicksLeft = player.sprintingTicksLeft;
        sim.canSprint = (float) player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;
        /* The game asks isUsingItem here too, and gets NoSlow's answer (MixinEntityPlayerSP.isUsing); asked here
           without counting the tick. */
        sim.canStartSprint = !NoSlow.wouldSlowMove(player) && !player.isPotionActive(Potion.blindness);
        sim.inWater = player.isInWater();
        sim.inWeb = ((IAccessorEntity) player).getIsInWeb();
        sim.depthStrider = (float) EnchantmentHelper.getDepthStriderModifier(player);
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

            /* World.handleMaterialAcceleration for water, read only: the flow is summed here, PlayerSim pushes. */
            @Override
            public boolean water(AxisAlignedBB area, double[] flow) {
                int minX = MathHelper.floor_double(area.minX);
                int maxX = MathHelper.floor_double(area.maxX + 1.0);
                int minY = MathHelper.floor_double(area.minY);
                int maxY = MathHelper.floor_double(area.maxY + 1.0);
                int minZ = MathHelper.floor_double(area.minZ);
                int maxZ = MathHelper.floor_double(area.maxZ + 1.0);
                boolean in = false;
                Vec3 sum = new Vec3(0.0, 0.0, 0.0);
                for (int x = minX; x < maxX; x++) {
                    for (int y = minY; y < maxY; y++) {
                        for (int z = minZ; z < maxZ; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            IBlockState state = world.getBlockState(pos);
                            Block block = state.getBlock();
                            if (block.getMaterial() != Material.water) {
                                continue;
                            }
                            double top = (double) ((float) (y + 1)
                                    - BlockLiquid.getLiquidHeightPercent((Integer) state.getValue(BlockLiquid.LEVEL)));
                            if ((double) maxY >= top) {
                                in = true;
                                sum = block.modifyAcceleration(world, pos, self, sum);
                            }
                        }
                    }
                }
                flow[0] += sum.xCoord;
                flow[1] += sum.yCoord;
                flow[2] += sum.zCoord;
                return in;
            }

            @Override
            public boolean lava(AxisAlignedBB area) {
                return world.isMaterialInBB(area, Material.lava);
            }

            @Override
            public boolean liquid(AxisAlignedBB area) {
                return world.isAnyLiquid(area);
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                return block == Blocks.ladder || block == Blocks.vine;
            }

            @Override
            public boolean slime(int x, int y, int z) {
                return world.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof BlockSlime;
            }

            /* Entity.doBlockCollisions: every cell the area (already in by 0.001) touches, ends included. */
            @Override
            public int touching(AxisAlignedBB area, PlayerSim.Touch kind) {
                int count = 0;
                for (int x = MathHelper.floor_double(area.minX); x <= MathHelper.floor_double(area.maxX); x++) {
                    for (int y = MathHelper.floor_double(area.minY); y <= MathHelper.floor_double(area.maxY); y++) {
                        for (int z = MathHelper.floor_double(area.minZ); z <= MathHelper.floor_double(area.maxZ); z++) {
                            Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                            if (kind == PlayerSim.Touch.WEB ? block instanceof BlockWeb : block instanceof BlockSoulSand) {
                                count++;
                            }
                        }
                    }
                }
                return count;
            }

            /* EntityPlayerSP.isOpenBlockSpace, as Forge left it: one cell. */
            @Override
            public boolean solid(int x, int y, int z) {
                return world.getBlockState(new BlockPos(x, y, z)).getBlock().isNormalCube();
            }

            /* The test moveEntityWithHeading makes before gravity. */
            @Override
            public boolean loaded(int x, int z) {
                BlockPos pos = new BlockPos(x, 0, z);
                return world.isBlockLoaded(pos) && world.getChunkFromBlockCoords(pos).isLoaded();
            }
        };
    }

    /** The keys the next move will get, asked before they are read this tick: from the keys held. */
    private static PlayerSim.Input inputAhead(EntityPlayerSP player) {
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
    private static PlayerSim.Input inputBeforeMove(EntityPlayerSP player) {
        return turnedAndSlowed(player, held(player));
    }

    /** The keys as they are in movementInput: this tick's move's once they are final. */
    private static PlayerSim.Input inputAsMoved(EntityPlayerSP player) {
        return held(player);
    }

    private static PlayerSim.Input look(EntityPlayerSP player) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = moveYaw(player);
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
