package atlas.script.raven.packet.serverbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.EnumFacing;

import java.util.Locale;

/**
 * Digging: status "START_DESTROY_BLOCK", "ABORT_DESTROY_BLOCK", "STOP_DESTROY_BLOCK", "DROP_ALL_ITEMS",
 * "DROP_ITEM" or "RELEASE_USE_ITEM"; facing "DOWN", "UP", "NORTH", "SOUTH", "WEST" or "EAST".
 */
public class C07 extends CPacket {
    public Vec3 position;
    public String status;
    public String facing;

    public C07(Vec3 position, String status, String facing) {
        super("C07PacketPlayerDigging");
        this.position = position;
        this.status = status;
        this.facing = facing;
    }

    static C07 of(C07PacketPlayerDigging packet) {
        C07 out = new C07(Vec3.convert(packet.getPosition()), packet.getStatus().name(), packet.getFacing().name());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.valueOf(this.status.toUpperCase(Locale.ROOT)),
                this.position == null ? net.minecraft.util.BlockPos.ORIGIN : Vec3.getBlockPos(this.position),
                EnumFacing.valueOf(this.facing == null ? "DOWN" : this.facing.toUpperCase(Locale.ROOT)));
    }
}
