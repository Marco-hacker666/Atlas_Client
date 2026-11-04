package atlas.script.raven.model;

import com.mojang.authlib.GameProfile;
import net.minecraft.tileentity.TileEntitySkull;

/** Raven script API: a block entity -- a chest, a bed, a skull ... (2026-11-02). */
public class TileEntity {
    public String type;
    public String name;
    private final net.minecraft.tileentity.TileEntity tileEntity;

    public TileEntity(net.minecraft.tileentity.TileEntity tileEntity) {
        this.tileEntity = tileEntity;
        /* As Raven: the block's class (BlockChest), not the tile entity's. */
        net.minecraft.block.Block block = tileEntity.getBlockType();
        this.type = block == null ? tileEntity.getClass().getSimpleName() : block.getClass().getSimpleName();
        this.name = block == null ? this.type : Block.registryName(block);
    }

    public Vec3 getPosition() {
        return new Vec3(this.tileEntity.getPos());
    }

    /** For a skull: {skull type, rotation, owner's name, owner's UUID, null} (no owner: nulls); else null. */
    public Object[] getSkullData() {
        if (!(this.tileEntity instanceof TileEntitySkull)) {
            return null;
        }
        /* As Raven: {type, rotation, owner's name, owner's UUID, null}. */
        TileEntitySkull skull = (TileEntitySkull) this.tileEntity;
        GameProfile profile = skull.getPlayerProfile();
        return new Object[]{skull.getSkullType(), skull.getSkullRotation(), profile == null ? null : profile.getName(),
                profile == null ? null : profile.getId(), null};
    }
}
