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

public final class SimPlayer {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private SimPlayer() {
    }

    public static final class Snapshot {
        public final PlayerSim sim;

        public final PlayerSim.Input input;
        public final PlayerSim.World world;

        private Snapshot(PlayerSim sim, PlayerSim.Input input, PlayerSim.World world) {
            this.sim = sim;
            this.input = input;
            this.world = world;
        }
    }

    public static boolean supported(EntityPlayerSP player) {
        if (player == null || player.worldObj == null || player.movementInput == null) {
            return false;
        }
        return !player.isRiding() && !player.capabilities.isFlying && !player.noClip && !player.isPlayerSleeping()
                && player.isEntityAlive();
    }

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

    public static float moveYaw(EntityPlayerSP player) {
        return RotationState.isActived() ? RotationState.getSmoothedYaw() : player.rotationYaw;
    }

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

        sim.walkSpeed = player.isSprinting() ? attribute / (1.0 + (double) 0.3F) : attribute;
        PotionEffect jump = player.getActivePotionEffect(Potion.jump);
        sim.jumpBoost = jump == null ? 0.0F : (float) (jump.getAmplifier() + 1) * 0.1F;
        sim.stepHeight = player.stepHeight;
        sim.width = player.width;
        sim.height = player.height;
        sim.sprintTicksLeft = player.sprintingTicksLeft;
        sim.canSprint = (float) player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;

        sim.canStartSprint = !NoSlow.wouldSlowMove(player) && !player.isPotionActive(Potion.blindness);
        sim.inWater = player.isInWater();
        sim.inWeb = ((IAccessorEntity) player).getIsInWeb();
        sim.depthStrider = (float) EnchantmentHelper.getDepthStriderModifier(player);
        return sim;
    }

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

            @Override
            public boolean solid(int x, int y, int z) {
                return world.getBlockState(new BlockPos(x, y, z)).getBlock().isNormalCube();
            }

            @Override
            public boolean loaded(int x, int z) {
                BlockPos pos = new BlockPos(x, 0, z);
                return world.isBlockLoaded(pos) && world.getChunkFromBlockCoords(pos).isLoaded();
            }
        };
    }

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

    private static PlayerSim.Input inputBeforeMove(EntityPlayerSP player) {
        return turnedAndSlowed(player, held(player));
    }

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
