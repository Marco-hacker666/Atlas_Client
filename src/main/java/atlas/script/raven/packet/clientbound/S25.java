package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Block;
import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;

/** Someone breaking a block: progress 0..9, anything else clears it. */
public class S25 extends SPacket {
    public int entityId;
    public Block block;
    public int progress;

    public S25(int entityId, Block block, int progress) {
        super("S25PacketBlockBreakAnim");
        this.entityId = entityId;
        this.block = block;
        this.progress = progress;
    }

    public S25(int entityId, Vec3 position, int progress) {
        this(entityId, new Block(position), progress);
    }

    static S25 of(S25PacketBlockBreakAnim packet) {
        S25 out = new S25(packet.getBreakerId(), Vec3.convert(packet.getPosition()), packet.getProgress());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S25PacketBlockBreakAnim(this.entityId,
                new net.minecraft.util.BlockPos(this.block.x, this.block.y, this.block.z), this.progress);
    }
}
