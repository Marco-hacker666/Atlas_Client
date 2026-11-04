package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Block;
import atlas.script.raven.model.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S23PacketBlockChange;

/** A block changing: what is at the position now. */
public class S23 extends SPacket {
    public Vec3 position;
    public Block block;

    /** The change to whatever is at the position in this side's world. */
    public S23(Vec3 position) {
        super("S23PacketBlockChange");
        this.position = position;
        this.block = new Block(position);
    }

    private S23(Vec3 position, Block block) {
        super("S23PacketBlockChange");
        this.position = position;
        this.block = block;
    }

    static S23 of(S23PacketBlockChange packet) {
        Vec3 position = Vec3.convert(packet.getBlockPosition());
        S23 out = new S23(position, new Block(packet.getBlockState(), packet.getBlockPosition()));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return this.packet;
        }
        S23PacketBlockChange packet = new S23PacketBlockChange(mc.theWorld, Vec3.getBlockPos(this.position));
        net.minecraft.block.Block type = this.block == null ? null
                : net.minecraft.block.Block.getBlockFromName("minecraft:" + this.block.name);
        if (type != null) {
            packet.blockState = type.getStateFromMeta(this.block.variant);
        }
        return packet;
    }
}
