package atlas.script.raven.packet.serverbound;

import atlas.script.raven.model.ItemStack;
import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;

/**
 * Placing or using: the block clicked, the face (0 down .. 5 east; 255 for using the held item in the air) and
 * the point on it (0..1 offsets).
 */
public class C08 extends CPacket {
    public ItemStack itemStack;
    public Vec3 position;
    public int direction;
    public Vec3 offset;

    public C08(ItemStack itemStack, Vec3 position, int direction, Vec3 offset) {
        super("C08PacketPlayerBlockPlacement");
        this.itemStack = itemStack;
        this.position = position;
        this.direction = direction;
        this.offset = offset;
    }

    static C08 of(C08PacketPlayerBlockPlacement packet) {
        C08 out = new C08(ItemStack.convert(packet.getStack()), Vec3.convert(packet.getPosition()),
                packet.getPlacedBlockDirection(), new Vec3(packet.getPlacedBlockOffsetX(),
                packet.getPlacedBlockOffsetY(), packet.getPlacedBlockOffsetZ()));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        net.minecraft.item.ItemStack stack = this.itemStack == null ? null : this.itemStack.itemStack;
        if (this.direction == 255 || this.position == null) {
            return new C08PacketPlayerBlockPlacement(stack);
        }
        Vec3 at = this.offset == null ? new Vec3(0.5, 0.5, 0.5) : this.offset;
        return new C08PacketPlayerBlockPlacement(Vec3.getBlockPos(this.position), this.direction, stack,
                (float) at.x, (float) at.y, (float) at.z);
    }
}
