package atlas.script.raven.model;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;

/** Raven script API: a block in the world, or a block type by name (2026-11-02). */
public class Block {
    public String type;
    public String name;
    public boolean interactable;
    public int variant;
    public double height;
    public double width;
    public double length;
    public double x;
    public double y;
    public double z;

    public Block(net.minecraft.block.Block block, BlockPos blockPos) {
        this(block.getDefaultState(), blockPos);
    }

    public Block(IBlockState state, BlockPos blockPos) {
        net.minecraft.block.Block block = state.getBlock();
        this.type = block.getClass().getSimpleName();
        this.name = registryName(block);
        this.interactable = interactable(block);
        this.variant = block.getMetaFromState(state);
        /* A slab or a door works its box out from the world and keeps it on the block object, which every position
           of that block shares. So only for the block really there (a door a packet is about to place would throw),
           and only on the client thread: packets come in on the network thread while collisions use those bounds. */
        Minecraft mc = Minecraft.getMinecraft();
        if (blockPos != null && mc.theWorld != null && mc.isCallingFromMinecraftThread()
                && mc.theWorld.getBlockState(blockPos) == state) {
            block.setBlockBoundsBasedOnState(mc.theWorld, blockPos);
        }
        this.height = block.getBlockBoundsMaxY() - block.getBlockBoundsMinY();
        this.width = block.getBlockBoundsMaxX() - block.getBlockBoundsMinX();
        this.length = block.getBlockBoundsMaxZ() - block.getBlockBoundsMinZ();
        if (blockPos != null) {
            this.x = blockPos.getX();
            this.y = blockPos.getY();
            this.z = blockPos.getZ();
        }
    }

    /** The block in the world at that position. */
    public Block(int x, int y, int z) {
        this(stateAt(new BlockPos(x, y, z)), new BlockPos(x, y, z));
    }

    public Block(Vec3 position) {
        this((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
    }

    /** A block type by name ("stone", or "minecraft:stone"); no position (-1, -1, -1, as in Raven). */
    public Block(String name) {
        this(typeOf(name).getDefaultState(), null);
        this.x = -1.0;
        this.y = -1.0;
        this.z = -1.0;
    }

    private static IBlockState stateAt(BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld == null ? net.minecraft.init.Blocks.air.getDefaultState() : mc.theWorld.getBlockState(pos);
    }

    private static net.minecraft.block.Block typeOf(String name) {
        net.minecraft.block.Block block = name == null ? null
                : net.minecraft.block.Block.getBlockFromName(name.contains(":") ? name : "minecraft:" + name);
        return block == null ? net.minecraft.init.Blocks.air : block;
    }

    static String registryName(net.minecraft.block.Block block) {
        ResourceLocation location = net.minecraft.block.Block.blockRegistry.getNameForObject(block);
        return location == null ? block.getUnlocalizedName() : location.getResourcePath();
    }

    /** What Raven counts as a block that takes a right click: containers, doors, gates, beds, anvils, crafting
     *  tables (not levers and buttons). */
    static boolean interactable(net.minecraft.block.Block block) {
        return block instanceof BlockContainer || block instanceof BlockDoor || block instanceof BlockFenceGate
                || block instanceof BlockTrapDoor || block instanceof BlockWorkbench
                || block instanceof net.minecraft.block.BlockBed || block instanceof net.minecraft.block.BlockAnvil;
    }

    @Override
    public String toString() {
        return "Block{" + this.name + " at " + this.x + ", " + this.y + ", " + this.z + "}";
    }
}
