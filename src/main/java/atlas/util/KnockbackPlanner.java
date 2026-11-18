package atlas.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockWeb;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.function.Predicate;

public final class KnockbackPlanner {
    public static final class Plan {
        public final int targetId;
        public final float yaw;
        public final double distance;
        public final double score;
        public final String hazard;

        Plan(int targetId, float yaw, double distance, double score, String hazard) {
            this.targetId = targetId;
            this.yaw = yaw;
            this.distance = distance;
            this.score = score;
            this.hazard = hazard;
        }

        @Override
        public String toString() {
            return String.format("%s score=%.1f yaw=%.1f dist=%.2f", this.hazard, this.score, this.yaw, this.distance);
        }
    }

    public static final double MIN_SCORE = 45.0;
    private static final int DIRECTIONS = 32;
    private static final double FIRST_STEP = 0.8;
    private static final double LAST_STEP = 5.0;
    private static final double STEP = 0.35;

    private static final int DROP_SEARCH = 24;

    private KnockbackPlanner() {
    }

    public static Plan plan(World world, EntityLivingBase target) {
        if (world == null || target == null) {
            return null;
        }
        AxisAlignedBB box = target.getEntityBoundingBox();
        Plan best = null;
        for (int i = 0; i < DIRECTIONS; i++) {
            double angle = Math.PI * 2.0 * i / DIRECTIONS;
            double dx = -Math.sin(angle);
            double dz = Math.cos(angle);
            for (double step = FIRST_STEP; step <= LAST_STEP; step += STEP) {
                AxisAlignedBB moved = box.offset(dx * step, 0.0, dz * step);
                Plan candidate = evaluate(world, target, moved, dx, dz, step);
                if (candidate != null && (best == null || candidate.score > best.score)) {
                    best = candidate;
                }
                if (!world.getCollidingBoundingBoxes(target, moved.contract(0.02, 0.0, 0.02)).isEmpty()) {
                    break;
                }
            }
        }
        return best != null && best.score >= MIN_SCORE ? best : null;
    }

    private static Plan evaluate(World world, EntityLivingBase target, AxisAlignedBB box, double dx, double dz,
                                 double distance) {
        AxisAlignedBB body = box.contract(0.05, 0.0, 0.05);
        AxisAlignedBB below = body.offset(0.0, -0.35, 0.0);
        String hazard;
        double drop = 0.0;
        if (contains(world, body, below, b -> b.getMaterial() == Material.lava)) {
            hazard = "Lava";
        } else if (contains(world, body, below, b -> b instanceof BlockWeb)) {
            hazard = "Web";
        } else if (contains(world, body, below, b -> b == Blocks.fire || b == Blocks.flowing_lava || b == Blocks.lava)) {
            hazard = "Fire";
        } else if (contains(world, body, below, b -> b == Blocks.cactus)) {
            hazard = "Cactus";
        } else {
            drop = dropDistance(world, below, DROP_SEARCH);
            if (drop < 0.0) {
                hazard = below.minY <= 8.0 ? "Void" : "Deep Drop";
            } else if (drop >= 4.0) {
                hazard = "Ditch";
            } else if (contains(world, body, below, b -> b.getMaterial() == Material.water)) {
                hazard = "Water";
            } else {
                return null;
            }
        }
        double score = score(hazard, distance, drop);
        return new Plan(target.getEntityId(), yawToward(dx, dz), distance, score, hazard);
    }

    public static double score(String hazard, double distance, double drop) {
        switch (hazard) {
            case "Lava":
                return 150.0 - distance * 8.0;
            case "Web":
                return 125.0 - distance * 7.0;
            case "Fire":
                return 100.0 - distance * 7.0;
            case "Cactus":
                return 96.0 - distance * 6.0;
            case "Void":
                return 145.0 - distance * 7.0;
            case "Deep Drop":
                return 108.0 - distance * 7.0;
            case "Ditch":
                return 88.0 + Math.min(drop, 10.0) * 3.5 - distance * 6.0;
            case "Water":
                return 58.0 + Math.max(0.0, drop) * 2.0 - distance * 5.0;
            default:
                return Double.NEGATIVE_INFINITY;
        }
    }

    public static float yawToward(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
    }

    private static double dropDistance(World world, AxisAlignedBB box, int search) {
        int minX = MathHelper.floor_double(box.minX + 1.0E-4);
        int maxX = MathHelper.floor_double(box.maxX - 1.0E-4);
        int minZ = MathHelper.floor_double(box.minZ + 1.0E-4);
        int maxZ = MathHelper.floor_double(box.maxZ - 1.0E-4);
        int top = MathHelper.floor_double(box.minY) - 1;
        int bottom = Math.max(0, top - search);
        double nearest = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean found = false;
                for (int y = top; y >= bottom; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    IBlockState state = world.getBlockState(pos);
                    AxisAlignedBB solid = state.getBlock().getCollisionBoundingBox(world, pos, state);
                    if (solid != null) {
                        nearest = Math.min(nearest, Math.max(0.0, box.minY - solid.maxY));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return -1.0;
                }
            }
        }
        return nearest == Double.POSITIVE_INFINITY ? -1.0 : nearest;
    }

    private static boolean contains(World world, AxisAlignedBB a, AxisAlignedBB b, Predicate<Block> test) {
        return containsIn(world, a, test) || containsIn(world, b, test);
    }

    private static boolean containsIn(World world, AxisAlignedBB box, Predicate<Block> test) {
        int minX = MathHelper.floor_double(box.minX + 1.0E-4);
        int maxX = MathHelper.floor_double(box.maxX - 1.0E-4);
        int minY = MathHelper.floor_double(box.minY + 1.0E-4);
        int maxY = MathHelper.floor_double(box.maxY - 1.0E-4);
        int minZ = MathHelper.floor_double(box.minZ + 1.0E-4);
        int maxZ = MathHelper.floor_double(box.maxZ - 1.0E-4);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (test.test(world.getBlockState(new BlockPos(x, y, z)).getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean fallingCrit(EntityLivingBase player) {
        return player != null && player.fallDistance > 0.0F && !player.onGround && !player.isOnLadder()
                && !player.isInWater() && !player.isPotionActive(net.minecraft.potion.Potion.blindness)
                && player.ridingEntity == null && player.motionY < 0.0;
    }

    public static boolean hasKnockbackSource(EntityLivingBase attacker, int knockbackEnchant) {
        return attacker != null && (attacker.isSprinting() || knockbackEnchant > 0);
    }
}
